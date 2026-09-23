package com.llzx373.foldreader.core.tts.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.llzx373.foldreader.FoldReaderApplication
import com.llzx373.foldreader.core.tts.TtsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TTS 听书前台服务（M13.1）。引擎与状态都在 AppContainer 持有的
 * [ReaderTtsController] 里，本服务只做壳：
 *  1. 朗读期间以前台 service（mediaPlayback 类型）托住进程，锁屏/退桌面不中断；
 *  2. MediaStyle 通知：播放/暂停切换 + 停止，标题为书名与当前章节；
 *  3. 框架 MediaSession（不引 media3）：耳机按键与锁屏控制路由到
 *     onPlay/onPause/onStop → 控制器 resume/pause/stop；
 *  4. 观察 [ReaderTtsController.state]，不在播放即退出前台并 stopSelf。
 *
 * POST_NOTIFICATIONS 被拒绝时的降级：前台服务照常运行（FGS 豁免），
 * 通知不进抽屉，MediaSession 控制不受影响。
 */
class TtsPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSession: MediaSession? = null
    private var foreground = false
    /** 上次上屏通知的签名（暂停态/标题变化才重建通知，逐句的 charOffset 推进不刷通知）。 */
    private var lastNotificationSig: Triple<Boolean, String, String>? = null

    override fun onCreate() {
        super.onCreate()
        val container = (application as FoldReaderApplication).container
        ensureNotificationChannel()
        val session = MediaSession(this, SESSION_TAG)
        mediaSession = session
        session.setCallback(object : MediaSession.Callback() {
            // 耳机按键/锁屏控制入口：框架默认把媒体键映射到这三个回调
            override fun onPlay() = container.ttsController.resume()
            override fun onPause() = container.ttsController.pause()
            override fun onStop() = container.ttsController.stop()
        })
        scope.launch {
            container.ttsState.collect { state -> onPlaybackState(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as FoldReaderApplication).container
        // 通知动作按钮（显式 intent，不走 MediaButtonReceiver，避免 media compat 依赖）
        when (intent?.action) {
            ACTION_TOGGLE -> {
                val state = container.ttsState.value
                if (state.paused) container.ttsController.resume() else container.ttsController.pause()
            }
            ACTION_STOP -> container.ttsController.stop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    private fun onPlaybackState(state: TtsState) {
        syncMediaSession(state)
        if (!state.playing) {
            if (foreground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foreground = false
            }
            lastNotificationSig = null
            stopSelf()
            return
        }
        val sig = Triple(state.paused, state.bookTitle, state.chapterTitle)
        if (!foreground || sig != lastNotificationSig) {
            lastNotificationSig = sig
            val notification = buildNotification(state)
            if (foreground) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            } else {
                // API 34+ 必须在 startForeground 时给出 foregroundServiceType
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                foreground = true
            }
        }
    }

    private fun syncMediaSession(state: TtsState) {
        val session = mediaSession ?: return
        session.isActive = state.playing
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP,
                )
                .setState(
                    when {
                        !state.playing -> PlaybackState.STATE_STOPPED
                        state.paused -> PlaybackState.STATE_PAUSED
                        else -> PlaybackState.STATE_PLAYING
                    },
                    // position 语义是毫秒，字符偏移不能冒充；不谎报进度
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    1.0f,
                )
                .build(),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.bookTitle.ifBlank { "FoldReader 朗读" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.chapterTitle)
                .build(),
        )
    }

    private fun buildNotification(state: TtsState): Notification {
        val session = mediaSession ?: error("MediaSession 未初始化")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val toggleAction = Notification.Action.Builder(
            null,
            if (state.paused) "继续朗读" else "暂停朗读",
            controlPendingIntent(ACTION_TOGGLE),
        ).build()
        val stopAction = Notification.Action.Builder(
            null,
            "停止朗读",
            controlPendingIntent(ACTION_STOP),
        ).build()
        val style = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(state.bookTitle.ifBlank { "FoldReader 朗读" })
            .setContentText(
                buildList {
                    if (state.chapterTitle.isNotBlank()) add(state.chapterTitle)
                    add(if (state.paused) "已暂停" else "朗读中")
                }.joinToString(" · "),
            )
            .setContentIntent(contentIntent)
            .addAction(toggleAction)
            .addAction(stopAction)
            .setStyle(style)
            .setOngoing(true)
            .build()
    }

    private fun controlPendingIntent(action: String): PendingIntent =
        PendingIntent.getService(
            this,
            action.hashCode(),
            Intent(this, TtsPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            // 低优先级：朗读是后台持续态，不该每次状态刷新都出声音/震动
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "听书朗读", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val SESSION_TAG = "FoldReaderTts"
        private const val CHANNEL_ID = "tts_playback"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_TOGGLE = "com.llzx373.foldreader.tts.TOGGLE"
        private const val ACTION_STOP = "com.llzx373.foldreader.tts.STOP"
    }
}
