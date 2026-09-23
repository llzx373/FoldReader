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
 * [TtsPlaybackService] 只是前台/MediaSession 壳（引擎与状态都在这里，不进 Composable/Activity）。
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
    /** 正在朗读（或暂停断点）的 segment 下标；onDone 推进，pause/resume 据此续播。 */
    private var currentIndex = 0
    /** 引擎异步初始化期间到达的播放请求，初始化完成后补放。 */
    private var pendingLaunch: (() -> Unit)? = null

    /** 从 [segments] 第一句开始整段朗读（QUEUE_ADD 逐句排队）。标题只用于通知/MediaSession。 */
    fun speak(
        bookId: Long,
        segments: List<TtsSegment>,
        bookTitle: String = "",
        chapterTitle: String = "",
    ) {
        main.post {
            if (segments.isEmpty()) return@post
            this.segments = segments
            currentIndex = 0
            generation++
            _state.value = TtsState(
                bookId = bookId,
                charOffset = segments.first().charOffset,
                playing = true,
                bookTitle = bookTitle,
                chapterTitle = chapterTitle,
            )
            // 前台壳：朗读期间进程持有前台 service；播完/停止由服务观察 state 后退出前台并 stopSelf
            runCatching { appContext.startService(Intent(appContext, TtsPlaybackService::class.java)) }
            val tts = ensureEngine()
            if (tts == null) {
                if (engineFailed) {
                    failNow(engineFailMessage)
                } else {
                    pendingLaunch = { startQueueFrom(0) }
                }
                return@post
            }
            startQueueFrom(0)
        }
    }

    /**
     * 暂停。TextToSpeech 没有真暂停：记录当前 segment 下标后停掉引擎队列，
     * [resume] 时从断点重新入队（该句会从头重读，是 TTS 引擎能力内的可行口径）。
     */
    fun pause() {
        main.post {
            if (!_state.value.playing || _state.value.paused) return@post
            generation++
            pendingLaunch = null
            engine?.stop()
            _state.update { it.copy(paused = true) }
        }
    }

    /** 从 pause 记录的断点重新入队续播。 */
    fun resume() {
        main.post {
            val state = _state.value
            if (!state.playing || !state.paused || segments.isEmpty()) return@post
            generation++
            val from = currentIndex.coerceIn(0, segments.lastIndex)
            _state.update { it.copy(paused = false, charOffset = segments[from].charOffset) }
            val tts = ensureEngine()
            if (tts == null) {
                if (engineFailed) failNow(engineFailMessage) else pendingLaunch = { startQueueFrom(from) }
                return@post
            }
            startQueueFrom(from)
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

    private fun startQueueFrom(index: Int) {
        val tts = engine ?: return
        tts.stop()
        currentIndex = index
        val gen = generation
        for (i in index until segments.size) {
            tts.speak(segments[i].text, TextToSpeech.QUEUE_ADD, null, utteranceId(gen, i))
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
                if (gen != generation || !_state.value.playing || _state.value.paused) return@post
                val next = index + 1
                if (next < segments.size) {
                    // 推进到下一句起点，阅读器据此做翻页联动
                    currentIndex = next
                    _state.update { it.copy(charOffset = segments[next].charOffset) }
                } else {
                    _state.update { it.copy(playing = false, paused = false) }
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
