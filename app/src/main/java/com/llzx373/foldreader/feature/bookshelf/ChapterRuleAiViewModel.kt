package com.llzx373.foldreader.feature.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.prompt.ChapterRulePrompt
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.format.ChapterRulePreview
import com.llzx373.foldreader.core.format.ChapterTitleSampler
import com.llzx373.foldreader.core.format.EncodingDetector
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * M15「AI 章节规则生成」状态机：
 * 空闲 → 加载正文/采样（[UiState.Preparing]）→ 首次外发确认（[UiState.AwaitConfirmation]，仅首次）
 * → AI 生成（[UiState.Generating]）→ 候选预览（[UiState.Preview]，每条候选含本地试切报告）
 * → 保存规则并重扫（[UiState.Saving]）→ [UiState.Done] / [UiState.Failure]（可重试）。
 *
 * 所有外部依赖都是注入的 lambda，构造层面不碰 Android（factory 除外），
 * 因此状态机与「候选 → 预览」组装可以纯 JVM 单测。
 */
class ChapterRuleAiViewModel(
    private val aiProvider: suspend () -> AiProvider?,
    /** 读全书正文；非文本型书或读取失败返回 null。 */
    private val loadFullText: suspend (bookId: Long) -> String?,
    private val bookTitle: suspend (bookId: Long) -> String,
    private val preferences: suspend () -> ReadingPreferences,
    private val markChapterRuleConfirmed: suspend () -> Unit,
    private val recordOutbound: (feature: String, scope: String, estimatedTokens: Int) -> Unit,
    private val saveChapterRules: suspend (bookId: Long, rules: List<String>) -> Unit,
    private val rebuildChapters: suspend (bookId: Long) -> Unit,
    private val chapterCount: suspend (bookId: Long) -> Int,
) : ViewModel() {

    /** 一条候选规则：正则 + AI 说明 + 本地试切预览报告。 */
    data class CandidateUi(
        val regex: String,
        val explanation: String,
        val preview: ChapterRulePreview.RulePreview,
    )

    sealed interface UiState {
        data object Idle : UiState

        /** 加载正文 / 粗筛采样中。 */
        data object Preparing : UiState

        /** 首次外发前的一次性确认：明示数据去向（服务商地址）与数据范围（采样行数）。 */
        data class AwaitConfirmation(val baseUrl: String, val sampleLines: Int) : UiState

        data object Generating : UiState
        data class Preview(val candidates: List<CandidateUi>) : UiState

        /** 已选定，正在保存规则并重扫目录。 */
        data object Saving : UiState
        data class Done(val chapterCount: Int) : UiState
        data class Failure(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bookId: Long = 0
    private var fullText: String = ""
    private var sampleLines: List<String> = emptyList()
    private var job: Job? = null

    /** 入口发起：加载正文 → 采样 →（首次需确认）→ 生成。进行中重复调用忽略。 */
    fun start(id: Long) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            bookId = id
            _state.value = UiState.Preparing
            val text = loadFullText(id)
            if (text == null) {
                _state.value = UiState.Failure("无法读取该书正文，AI 识别章节仅支持 TXT 书籍")
                return@launch
            }
            val sample = ChapterTitleSampler.sample(text)
            if (sample.isEmpty()) {
                _state.value = UiState.Failure("未能从书中采样到疑似章节标题行")
                return@launch
            }
            fullText = text
            sampleLines = sample
            val prefs = preferences()
            if (prefs.aiChapterRuleConfirmed) {
                generate()
            } else {
                _state.value = UiState.AwaitConfirmation(prefs.aiBaseUrl, sample.size)
            }
        }
    }

    /** 确认框「同意并生成」：落一次性确认标记后继续。 */
    fun confirmAndGenerate() {
        if (_state.value !is UiState.AwaitConfirmation) return
        viewModelScope.launch {
            markChapterRuleConfirmed()
            generate()
        }
    }

    /** 失败重试：采样已在内存，直接重走生成，不重读正文。 */
    fun retry() {
        if (_state.value !is UiState.Failure || sampleLines.isEmpty()) return
        viewModelScope.launch { generate() }
    }

    private suspend fun generate() {
        val provider = aiProvider()
        if (provider == null) {
            _state.value = UiState.Failure("请先在设置中完成 AI 服务配置")
            return
        }
        _state.value = UiState.Generating
        val sampleChars = sampleLines.sumOf { it.length }
        // 外发台账 token 估算口径：采样行以中文为主，中文约 1 token/字、英文约 1 token/2~4 字符，
        // 取「字符数 / 2」作保守下限级估算——台账只用于审计量级，不求精确。
        recordOutbound("章节规则", bookTitle(bookId), sampleChars / 2)
        val reply = StringBuilder()
        try {
            withTimeout(AI_TIMEOUT_MS) {
                provider.chat(
                    ChapterRulePrompt.buildMessages(sampleLines),
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
        val candidates = ChapterRulePrompt.parseChapterRuleCandidates(reply.toString())
        if (candidates.isEmpty()) {
            _state.value = UiState.Failure("AI 未能给出可用规则，可重试或在设置中手动添加章节规则")
            return
        }
        _state.value = UiState.Preview(
            candidates.map { candidate ->
                CandidateUi(
                    regex = candidate.regex,
                    explanation = candidate.explanation,
                    preview = ChapterRulePreview.preview(fullText, candidate.regex),
                )
            },
        )
    }

    /** 用户选定候选：存为本书自定义章节规则 → 重扫目录 → 完成（带回新章节数）。 */
    fun select(candidate: CandidateUi) {
        if (_state.value !is UiState.Preview) return
        _state.value = UiState.Saving
        viewModelScope.launch {
            val ok = runCatching {
                saveChapterRules(bookId, listOf(candidate.regex))
                rebuildChapters(bookId)
            }.isSuccess
            _state.value = if (ok) {
                UiState.Done(chapterCount(bookId))
            } else {
                UiState.Failure("保存规则或重建目录失败，请重试")
            }
        }
    }

    companion object {
        private const val AI_TIMEOUT_MS = 90_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChapterRuleAiViewModel(
                    aiProvider = container::aiProvider,
                    loadFullText = { bookId -> loadBookFullText(container, bookId) },
                    bookTitle = { bookId ->
                        container.bookshelfRepository.getBook(bookId)?.title ?: "未知书籍"
                    },
                    preferences = { container.settingsRepository.preferences.first() },
                    markChapterRuleConfirmed = {
                        container.settingsRepository.setAiChapterRuleConfirmed(true)
                    },
                    recordOutbound = { feature, scope, tokens ->
                        container.aiContentGate.record(feature, scope, tokens)
                    },
                    saveChapterRules = container.bookPrefsRepository::setChapterRules,
                    rebuildChapters = container::rebuildBookChapters,
                    chapterCount = { bookId ->
                        container.bookshelfRepository.getChapters(bookId).size
                    },
                )
            }
        }

        /**
         * 读全书正文：等偏移索引封口再整体读出；非文本型书（漫画）或读取失败返回 null。
         *
         * 只按 charOffset 坐标取文本——页式 PDF 的目录锚点是页序号，规则切分对它没有意义，
         * 入口侧已限制 TXT，这里再兜一层「读不出就当不支持」。
         */
        private suspend fun loadBookFullText(container: AppContainer, bookId: Long): String? =
            withContext(Dispatchers.IO) {
                val book = container.bookshelfRepository.getBook(bookId) ?: return@withContext null
                val parser = runCatching { container.bookParsers.parserFor(book.format) }.getOrNull()
                    ?: return@withContext null
                val content = runCatching {
                    parser.openContent(
                        Uri.parse(book.fileUri),
                        EncodingDetector.forNameOrNull(book.encoding),
                        bookId,
                    )
                }.getOrNull() ?: return@withContext null
                try {
                    // 实时索引期间 charCount 会增长：等索引封口（失败/卡住时读到的部分也够用，
                    // 采样器本来就不要求全文完整），最多等 600 轮兜底。
                    var guard = 0
                    while (!content.isCharCountFinal && guard++ < 600) {
                        val before = content.charCount
                        content.awaitCharsAbove(before)
                        if (content.charCount == before && !content.isCharCountFinal) break
                    }
                    val total = content.charCount
                    if (total <= 0) null else content.read(0L until total)
                } finally {
                    (content as? Closeable)?.close()
                }
            }
    }
}
