package com.llzx373.foldreader.feature.summary

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
 * 章节摘要批量预生成前台服务（M29）：与 [com.llzx373.foldreader.feature.translate.TranslationService]
 * 同为壳服务——队列与状态都在 AppContainer 的 [BookSummaryQueue] 里，本服务只做：
 *  1. 队列有活动书（排队/进行/暂停）时以前台 service（dataSync 类型）托住进程；
 *  2. 进度通知：每本活动书一行「书名 done/total」，限频刷新（≥ [NOTIFY_MIN_INTERVAL_MS]）；
 *  3. 通知动作「暂停/继续」整队切换（单位粒度生效）；
 *  4. 全部到达终态时发一条完成通知，随后退出前台并 stopSelf。
 */
class SummaryService : Service() {

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
            container.bookSummaryQueue.progress.collect { progress ->
                onProgress(container.bookSummaryQueue, progress)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val queue = (application as FoldReaderApplication).container.bookSummaryQueue
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
        queue: BookSummaryQueue,
        progress: Map<Long, BookSummaryQueue.BookProgress>,
    ) {
        val active = progress.values.filter {
            it.status == BookSummaryQueue.Status.QUEUED ||
                it.status == BookSummaryQueue.Status.RUNNING ||
                it.status == BookSummaryQueue.Status.PAUSED
        }
        if (active.isEmpty()) {
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
                BookSummaryQueue.Status.QUEUED -> "排队中"
                BookSummaryQueue.Status.PAUSED -> "已暂停"
                else -> "摘要中"
            }
            "$title：${p.done}/${p.total}（$state）"
        }
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

    private fun buildProgressNotification(queue: BookSummaryQueue, text: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val toggleAction = Notification.Action.Builder(
            null,
            if (queue.isPaused()) "继续摘要" else "暂停摘要",
            controlPendingIntent(ACTION_TOGGLE),
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("章节摘要")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .addAction(toggleAction)
            .setOngoing(true)
            .build()
    }

    /** 全终态时的一次性完成通知（独立 id，前台通知随即被 stopForeground 收走）。 */
    private fun notifyCompletion(progress: Map<Long, BookSummaryQueue.BookProgress>) {
        val done = progress.values.count { it.status == BookSummaryQueue.Status.DONE }
        val failed = progress.values.count { it.status == BookSummaryQueue.Status.FAILED }
        val text = buildString {
            if (done > 0) append("${done} 本书摘要完成")
            if (failed > 0) {
                if (isNotEmpty()) append("，")
                append("${failed} 本失败")
            }
            if (isEmpty()) append("摘要结束")
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
            .setContentTitle("章节摘要")
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
            Intent(this, SummaryService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "章节摘要", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "book_summary"
        private const val NOTIFICATION_ID = 45
        private const val COMPLETION_NOTIFICATION_ID = 46
        private const val ACTION_TOGGLE = "com.llzx373.foldreader.summary.TOGGLE"
        /** 进度通知最小刷新间隔：逐单位推进时最多每 2s 刷一次。 */
        private const val NOTIFY_MIN_INTERVAL_MS = 2_000L
    }
}
