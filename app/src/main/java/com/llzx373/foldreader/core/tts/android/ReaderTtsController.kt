package com.llzx373.foldreader.core.tts.android

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.llzx373.foldreader.core.tts.TtsSegment
import com.llzx373.foldreader.core.tts.TtsState
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * TTS 听书引擎控制器（M13.1）。进程级单例，由 AppContainer 持有，
 * [TtsPlaybackService] 只是保活壳（步骤 9 在此之上加前台/MediaSession，引擎不动）。
 *
 * 线程模型：公开方法一律经主线程 Handler 归位后操作引擎与状态，
 * UtteranceProgressListener 回调（binder 线程）也先 post 回主线程再处理，
 * 因此内部字段不需要额外同步。
 */
class ReaderTtsController(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state.asStateFlow()

    // 以下字段全部只在主线程读写
    private var engine: TextToSpeech? = null
    private var engineReady = false
    /** 初始化/语种检查一旦失败不再重试，直接走错误文案（避免每次点朗读都重建引擎）。 */
    private var engineFailed = false
    /** 播放代次：stop/重新 speak 都会 +1，迟到回调凭 utteranceId 里的代次丢弃。 */
    private var generation = 0
    private var segments: List<TtsSegment> = emptyList()
    /** 引擎异步初始化期间到达的播放请求，初始化完成后补放。 */
    private var pendingLaunch: (() -> Unit)? = null

    /** 从 [segments] 第一句开始整段朗读（QUEUE_ADD 逐句排队）。 */
    fun speak(bookId: Long, segments: List<TtsSegment>) {
        main.post {
            if (segments.isEmpty()) return@post
            this.segments = segments
            generation++
            _state.value = TtsState(
                bookId = bookId,
                charOffset = segments.first().charOffset,
                playing = true,
            )
            // 保活壳：朗读期间进程持有 started service；播完/停止由服务观察 state 后 stopSelf
            runCatching { appContext.startService(Intent(appContext, TtsPlaybackService::class.java)) }
            val tts = ensureEngine()
            if (tts == null) {
                if (engineFailed) {
                    failNow(engineFailMessage)
                } else {
                    pendingLaunch = { startQueue() }
                }
                return@post
            }
            startQueue()
        }
    }

    fun stop() {
        main.post {
            generation++
            segments = emptyList()
            pendingLaunch = null
            engine?.stop()
            _state.value = TtsState()
        }
    }

    /** 初始化完成后的引擎；未就绪（初始化中）返回 null，失败记 [engineFailed]。 */
    private fun ensureEngine(): TextToSpeech? {
        engine?.let { return if (engineReady) it else null }
        if (engineFailed) return null
        val created = runCatching {
            TextToSpeech(appContext) { status ->
                // OnInitListener 在主线程回调
                if (status != TextToSpeech.SUCCESS) {
                    engineFailed = true
                    engine = null
                    failNow(engineFailMessage)
                    return@TextToSpeech
                }
                val tts = engine ?: return@TextToSpeech
                // 语种检查：优先中文（按书语言选择是未来扩展点）；缺数据/不支持 → 降级报错
                val lang = tts.setLanguage(Locale.CHINESE)
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engineFailed = true
                    tts.shutdown()
                    engine = null
                    failNow("当前设备不支持中文 TTS")
                    return@TextToSpeech
                }
                tts.setOnUtteranceProgressListener(utteranceListener)
                engineReady = true
                pendingLaunch?.invoke()
                pendingLaunch = null
            }
        }.getOrNull()
        if (created == null) {
            engineFailed = true
            return null
        }
        engine = created
        return null
    }

    private fun startQueue() {
        val tts = engine ?: return
        tts.stop()
        val gen = generation
        segments.forEachIndexed { index, segment ->
            tts.speak(segment.text, TextToSpeech.QUEUE_ADD, null, utteranceId(gen, index))
        }
    }

    private fun failNow(message: String) {
        segments = emptyList()
        pendingLaunch = null
        _state.value = TtsState(error = message)
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}

        override fun onDone(utteranceId: String) {
            main.post {
                val (gen, index) = parseUtteranceId(utteranceId) ?: return@post
                if (gen != generation || !_state.value.playing) return@post
                val next = index + 1
                if (next < segments.size) {
                    // 推进到下一句起点，阅读器据此做翻页联动
                    _state.update { it.copy(charOffset = segments[next].charOffset) }
                } else {
                    _state.update { it.copy(playing = false) }
                }
            }
        }

        @Deprecated("UtteranceProgressListener 抽象方法；错误码版默认回调到这里")
        override fun onError(utteranceId: String) {
            main.post { failNow("TTS 朗读出错") }
        }
    }

    private companion object {
        const val engineFailMessage = "TTS 引擎初始化失败"

        fun utteranceId(generation: Int, index: Int) = "g$generation-seg$index"

        fun parseUtteranceId(id: String): Pair<Int, Int>? {
            val body = id.removePrefix("g")
            val sep = body.indexOf("-seg")
            if (sep <= 0) return null
            val gen = body.substring(0, sep).toIntOrNull() ?: return null
            val index = body.substring(sep + 4).toIntOrNull() ?: return null
            return gen to index
        }
    }
}
