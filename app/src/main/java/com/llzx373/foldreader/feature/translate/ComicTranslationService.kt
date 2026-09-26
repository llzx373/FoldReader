package com.llzx373.foldreader.feature.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.llzx373.foldreader.FoldReaderApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 漫画整卷翻译前台服务（M22）：与 [TranslationService] 同为壳服务 ——
 * 队列与状态都在 AppContainer 的 [ComicTranslationQueue] 里，本服务只做：
 *  1. 队列有活动书（排队/进行/暂停）时以前台 service（dataSync 类型）托住进程，
 *     退桌面/锁屏不断译；
 *  2. 进度通知：每本活动书一行「书名 done/total」，限频刷新（文本签名变化且距上次
 *     ≥ [NOTIFY_MIN_INTERVAL_MS] 才 notify，逐页推进不刷爆通知栏）；
 *  3. 通知动作「暂停/继续」整队切换（页粒度生效）；
 *  4. 全部到达终态时发一条完成通知（含失败页数），随后退出前台并 stopSelf。
 *
 * POST_NOTIFICATIONS 被拒绝时的降级与 TTS 一致：前台服务照常运行，通知不进抽屉。
 */
class ComicTranslationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var foreground = false
    /** 上次上屏通知的文本签名 + 上屏时刻（限频用）。 */
    private var lastNotificationText: String? = null
    private var lastNotifyAtMs = 0L
    private var hadActive = false
    /** bookId → 书名缓存（进度流里只有 id）。 */
    private val bookTitles = HashMap<Long, String>()

    override fun onCreate() {
        super.onCreate()
        val container = (application as FoldReaderApplication).container
        ensureNotificationChannel()
        scope.launch {
            container.comicTranslationQueue.progress.collect { progress ->
                onProgress(container.comicTranslationQueue, progress)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val queue = (application as FoldReaderApplication).container.comicTranslationQueue
        if (intent?.action == ACTION_TOGGLE) {
            if (queue.isPaused()) queue.resume() else queue.pause()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun onProgress(
        queue: ComicTranslationQueue,
        progress: Map<Long, ComicTranslationQueue.BookProgress>,
    ) {
        val active = progress.values.filter {
            it.status == ComicTranslationQueue.Status.QUEUED ||
                it.status == ComicTranslationQueue.Status.RUNNING ||
                it.status == ComicTranslationQueue.Status.PAUSED
        }
        if (active.isEmpty()) {
            // 曾有活动 → 现在全终态：发完成通知再退（服务首次冷启动无活动则直接退）
            if (hadActive) notifyCompletion(progress)
            hadActive = false
            if (foreground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foreground = false
            }
            lastNotificationText = null
            stopSelf()
            return
        }
        hadActive = true
        val text = active.joinToString("\n") { p ->
            val title = bookTitles[p.bookId] ?: "书 #${p.bookId}"
            val state = when (p.status) {
                ComicTranslationQueue.Status.QUEUED -> "排队中"
                ComicTranslationQueue.Status.PAUSED -> "已暂停"
                else -> "翻译中"
            }
            "$title：${p.done}/${p.total}（$state）"
        }
        // 异步补书名（首次出现的书先显示占位，下次限频窗口自然带上真名）
        scope.launch(Dispatchers.IO) {
            val container = (application as FoldReaderApplication).container
            active.forEach { p ->
                if (!bookTitles.containsKey(p.bookId)) {
                    bookTitles[p.bookId] =
                        container.bookshelfRepository.getBook(p.bookId)?.title ?: "书 #${p.bookId}"
                }
            }
        }
        val now = System.currentTimeMillis()
        if (!foreground) {
            lastNotificationText = text
            lastNotifyAtMs = now
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildProgressNotification(queue, text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            foreground = true
        } else if (text != lastNotificationText && now - lastNotifyAtMs >= NOTIFY_MIN_INTERVAL_MS) {
            lastNotificationText = text
            lastNotifyAtMs = now
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildProgressNotification(queue, text))
        }
    }

    private fun buildProgressNotification(queue: ComicTranslationQueue, text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val toggleAction = Notification.Action.Builder(
            null,
            if (queue.isPaused()) "继续翻译" else "暂停翻译",
            controlPendingIntent(ACTION_TOGGLE),
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("漫画翻译")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .addAction(toggleAction)
            .setOngoing(true)
            .build()
    }

    /** 全终态时的一次性完成通知（独立 id，前台通知随即被 stopForeground 收走）。 */
    private fun notifyCompletion(progress: Map<Long, ComicTranslationQueue.BookProgress>) {
        val finished = progress.values.filter { it.status == ComicTranslationQueue.Status.DONE }
        val failedBooks = progress.values.count { it.status == ComicTranslationQueue.Status.FAILED }
        val text = buildString {
            finished.forEach { p ->
                if (isNotEmpty()) append("；")
                append("《${bookTitles[p.bookId] ?: "书 #${p.bookId}"}》翻译完成")
                if (p.failedPages > 0) append("，${p.failedPages} 页失败")
            }
            if (failedBooks > 0) {
                if (isNotEmpty()) append("；")
                append("${failedBooks} 本失败")
            }
            if (isEmpty()) append("翻译结束")
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("漫画翻译")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    private fun controlPendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, ComicTranslationService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "漫画翻译", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "comic_translation"
        private const val NOTIFICATION_ID = 45
        private const val COMPLETION_NOTIFICATION_ID = 46
        private const val ACTION_TOGGLE = "com.llzx373.foldreader.comic_translation.TOGGLE"
        /** 进度通知最小刷新间隔：逐页推进时最多每 2s 刷一次。 */
        private const val NOTIFY_MIN_INTERVAL_MS = 2_000L
    }
}
