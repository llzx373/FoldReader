package com.llzx373.foldreader.feature.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.prompt.CleanRecipePrompt
import com.llzx373.foldreader.core.ai.prompt.CleanRecipeSuggestion
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanSampleSampler
import com.llzx373.foldreader.core.format.clean.CleanToggles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * M16「AI 清洗配方推荐」状态机：
 * 空闲 → 加载正文/采样（[UiState.Preparing]）→ 首次外发确认（[UiState.AwaitConfirmation]，仅首次）
 * → AI 生成（[UiState.Generating]）→ 配方预览（[UiState.Ready]）→ [UiState.Failure]（可重试）。
 *
 * AI **只推荐、不执行**：用户在 [UiState.Ready] 点「应用推荐」后，配方经对话框回调交回
 * 「智能整理」的既有预览/确认/物化链路（[BookshelfViewModel.previewReclean] /
 * [BookshelfViewModel.recleanBook] 的配方重载），执行路径与档位重洗完全是同一条。
 *
 * 所有外部依赖都是注入的 lambda，构造层面不碰 Android（factory 除外），纯 JVM 可单测。
 */
class CleanRecipeAiViewModel(
    private val aiProvider: suspend () -> AiProvider?,
    /** 读全书原始正文（脏文本）；非文本型书或读取失败返回 null。 */
    private val loadFullText: suspend (bookId: Long) -> String?,
    private val bookTitle: suspend (bookId: Long) -> String,
    private val preferences: suspend () -> ReadingPreferences,
    private val markCleanRecipeConfirmed: suspend () -> Unit,
    private val recordOutbound: (feature: String, scope: String, estimatedTokens: Int) -> Unit,
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState

        /** 加载正文 / 采样中。 */
        data object Preparing : UiState

        /** 首次外发前的一次性确认：明示数据去向（服务商地址）与数据范围（采样字符数）。 */
        data class AwaitConfirmation(val baseUrl: String, val sampleChars: Int) : UiState

        data object Generating : UiState

        /** 配方就绪：[profile] 待用户「应用推荐」后交给既有预览链路；[summaryLines] 供对话框展示。 */
        data class Ready(
            val profile: CleanProfile,
            val explanation: String,
            val summaryLines: List<String>,
        ) : UiState

        data class Failure(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bookId: Long = 0
    private var sampleText: String = ""
    private var job: Job? = null

    /** 入口发起：加载正文 → 采样 →（首次需确认）→ 生成。进行中重复调用忽略。 */
    fun start(id: Long) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            bookId = id
            _state.value = UiState.Preparing
            val text = loadFullText(id)
            if (text == null) {
                _state.value = UiState.Failure("无法读取该书正文，AI 推荐配方仅支持 TXT 书籍")
                return@launch
            }
            val sample = CleanSampleSampler.sample(text)
            if (sample.isEmpty()) {
                _state.value = UiState.Failure("未能从书中采样到文本")
                return@launch
            }
            sampleText = sample
            val prefs = preferences()
            if (prefs.aiCleanRecipeConfirmed) {
                generate()
            } else {
                _state.value = UiState.AwaitConfirmation(prefs.aiBaseUrl, sample.length)
            }
        }
    }

    /** 确认框「同意并生成」：落一次性确认标记后继续。 */
    fun confirmAndGenerate() {
        if (_state.value !is UiState.AwaitConfirmation) return
        viewModelScope.launch {
            markCleanRecipeConfirmed()
            generate()
        }
    }

    /** 失败重试：采样已在内存，直接重走生成，不重读正文。 */
    fun retry() {
        if (_state.value !is UiState.Failure || sampleText.isEmpty()) return
        viewModelScope.launch { generate() }
    }

    private suspend fun generate() {
        val provider = aiProvider()
        if (provider == null) {
            _state.value = UiState.Failure("请先在设置中完成 AI 服务配置")
            return
        }
        _state.value = UiState.Generating
        // 台账 token 估算口径与 M15 一致：字符数 / 2，只用于审计量级。
        recordOutbound("清洗配方", bookTitle(bookId), sampleText.length / 2)
        val reply = StringBuilder()
        try {
            withTimeout(AI_TIMEOUT_MS) {
                provider.chat(
                    CleanRecipePrompt.buildMessages(sampleText),
                    preferences().aiModelGeneral,
                ).collect { reply.append(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            _state.value = UiState.Failure(e.message ?: "调用失败")
            return
        } catch (e: Exception) {
            _state.value = UiState.Failure("网络不可达或响应异常")
            return
        }
        val suggestion = CleanRecipePrompt.parseSuggestion(reply.toString())
        if (suggestion == null ||
            (suggestion.toggles.isEmpty() && suggestion.adPatterns.isEmpty() && suggestion.explanation.isBlank())
        ) {
            _state.value = UiState.Failure("AI 未能给出可用建议，可重试或直接在上方选择档位")
            return
        }
        // 用户全局自定义广告正则照常并入（与 CleanProfileFactory 同口径），AI 给的排前面
        val globalAdPatterns = preferences().adCleanRules
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
        val profile = CleanRecipePrompt.buildProfile(suggestion)
            .copy(adPatterns = suggestion.adPatterns.map { Regex(it) } + globalAdPatterns)
        _state.value = UiState.Ready(
            profile = profile,
            explanation = suggestion.explanation,
            summaryLines = summaryOf(suggestion),
        )
    }

    companion object {
        private const val AI_TIMEOUT_MS = 90_000L

        /** 给对话框看的建议摘要：每条开关覆盖一行「名称：开/关」，广告正则逐条列出。 */
        private fun summaryOf(suggestion: CleanRecipeSuggestion): List<String> {
            val lines = suggestion.toggles.mapNotNull { (key, value) ->
                CleanToggles.ENTRIES
                    .firstOrNull { it.key == key }
                    ?.let { "${it.label}：${if (value) "开" else "关"}" }
            }
            return lines + suggestion.adPatterns.map { "广告正则：$it" }
        }

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CleanRecipeAiViewModel(
                    aiProvider = container::aiProvider,
                    loadFullText = { bookId ->
                        ChapterRuleAiViewModel.loadBookFullText(container, bookId)
                    },
                    bookTitle = { bookId ->
                        container.bookshelfRepository.getBook(bookId)?.title ?: "未知书籍"
                    },
                    preferences = { container.settingsRepository.preferences.first() },
                    markCleanRecipeConfirmed = {
                        container.settingsRepository.setAiCleanRecipeConfirmed(true)
                    },
                    recordOutbound = { feature, scope, tokens ->
                        container.aiContentGate.record(feature, scope, tokens)
                    },
                )
            }
        }
    }
}
