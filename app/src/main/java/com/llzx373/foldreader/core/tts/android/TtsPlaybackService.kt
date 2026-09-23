package com.llzx373.foldreader.core.tts.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.llzx373.foldreader.FoldReaderApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TTS 听书保活壳（M13.1）。引擎与状态都在 AppContainer 持有的
 * [ReaderTtsController] 里，本服务只做两件事：
 *  1. 朗读期间以 started service 身份托住进程（用户退到桌面/锁屏不被立刻回收）；
 *  2. 观察 [ReaderTtsController.state]，不在播放即 stopSelf。
 *
 * 步骤 9 在这里升级前台服务 + 通知 + MediaSession，引擎层不需要动。
 */
class TtsPlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val container = (application as FoldReaderApplication).container
        scope.launch {
            container.ttsState.collect { state ->
                if (!state.playing) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
