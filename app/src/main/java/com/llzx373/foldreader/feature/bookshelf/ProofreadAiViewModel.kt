package com.llzx373.foldreader.feature.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.ai.AiException
import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.prompt.ProofreadIssue
import com.llzx373.foldreader.core.ai.prompt.ProofreadPrompt
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanToggles
import com.llzx373.foldreader.core.translate.computeUnits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * M30「AI 校对」状态机：
 * 空闲 → 加载正文/切单位（[UiState.Preparing]）→ 首次外发确认（[UiState.AwaitConfirmation]，仅首次）
 * → 按单位逐个送校（[UiState.Running]）→ [UiState.Done]（结果同时已落成批注）/ [UiState.Failure]。
 *
 * 硬约束「只标不改」：引擎只写**批注**（note 里写明建议），正文一个字节不动；
 * 用户逐条确认后生成清洗配方交 M16 既有预览/物化链路执行（见 [confirmations]/[buildRecipeProfile]）。
 *
 * 单位划分复用 M19 翻译单位的切块（[computeUnits]）：单位内偏移 + 单位 charStart = 全书批注锚点，
 * 与阅读器批注同一坐标系。重跑先清掉上一轮校对批注（note 前缀 [NOTE_PREFIX]），不堆叠。
 *
 * 所有外部依赖都是注入的 lambda，构造层面不碰 Android（factory 除外），纯 JVM 可单测。
 */
class ProofreadAiViewModel(
    private val aiProvider: suspend () -> AiProvider?,
    /** 读当前阅读文本（有清洗副本时读副本——批注锚点要与读者看到的文本同一坐标系）。 */
    private val loadFullText: suspend (bookId: Long) -> String?,
    private val chaptersFor: suspend (bookId: Long) -> List<Chapter>,
    private val bookTitle: suspend (bookId: Long) -> String,
    private val preferences: suspend () -> ReadingPreferences,
    private val markProofreadConfirmed: suspend () -> Unit,
    private val recordOutbound: (feature: String, scope: String, estimatedTokens: Int) -> Unit,
    /** 重跑前清除该书上一轮的校对批注。 */
    private val clearProofreadAnnotations: suspend (bookId: Long) -> Unit,
    private val addAnnotation: suspend (AnnotationEntity) -> Unit,
) : ViewModel() {

    /** 一条校对结果（全书坐标）：供批注落库与确认列表展示。 */
    data class IssueEntry(
        val offset: Long,
        val length: Int,
        val original: String,
        val suggestion: String,
        val type: ProofreadIssue.Type,
        val unitTitle: String,
    )

    /** 确认列表的一行：同一「原文→建议」合并展示（一处确认 = 全书该处全部替换）。 */
    data class Confirmation(
        val original: String,
        val suggestion: String,
        val type: ProofreadIssue.Type,
        val occurrences: Int,
        val checked: Boolean = true,
    )

    sealed interface UiState {
        data object Idle : UiState

        /** 加载正文 / 切单位中。 */
        data object Preparing : UiState

        /** 首次外发前的一次性确认：明示数据去向、范围与成本估算。 */
        data class AwaitConfirmation(
            val baseUrl: String,
            val totalChars: Long,
            val estimatedTokens: Long,
        ) : UiState

        /** 按单位送校中。 */
        data class Running(val doneUnits: Int, val totalUnits: Int, val found: Int) : UiState

        /** 全部单位跑完；[issues] 已落成批注，[confirmations] 供逐条勾选生成配方。 */
        data class Done(
            val issues: List<IssueEntry>,
            val confirmations: List<Confirmation>,
            val failedUnits: Int,
        ) : UiState

        data class Failure(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bookId: Long = 0
    private var fullText: String = ""
    private var job: Job? = null

    /** 入口发起：加载正文 → 切单位 →（首次需确认）→ 逐单位送校。进行中重复调用忽略。 */
    fun start(id: Long) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            bookId = id
            _state.value = UiState.Preparing
            val text = loadFullText(id)
            if (text.isNullOrBlank()) {
                _state.value = UiState.Failure("无法读取该书正文，AI 校对仅支持 TXT 书籍")
                return@launch
            }
            fullText = text
            val prefs = preferences()
            if (prefs.aiProofreadConfirmed) {
                run()
            } else {
                _state.value = UiState.AwaitConfirmation(
                    baseUrl = prefs.aiBaseUrl,
                    totalChars = text.length.toLong(),
                    estimatedTokens = text.length / 2L,
                )
            }
        }
    }

    /** 确认框「同意外发并开始」：落一次性确认标记后开始送校。 */
    fun confirmAndRun() {
        if (_state.value !is UiState.AwaitConfirmation) return
        job = viewModelScope.launch {
            markProofreadConfirmed()
            run()
        }
    }

    private suspend fun run() {
        val provider = aiProvider()
        if (provider == null) {
            _state.value = UiState.Failure("请先在设置中完成 AI 服务配置")
            return
        }
        val chapters = chaptersFor(bookId)
        val units = computeUnits(chapters, fullText.length.toLong(), read = { range ->
            fullText.substring(range.first.toInt(), (range.last + 1).toInt())
        })
        if (units.isEmpty()) {
            _state.value = UiState.Failure("未能切出校对单位")
            return
        }
        val title = bookTitle(bookId)
        val model = preferences().aiModelGeneral
        // 重跑不堆叠：先清上一轮校对批注
        clearProofreadAnnotations(bookId)

        val issues = ArrayList<IssueEntry>()
        var failedUnits = 0
        _state.value = UiState.Running(0, units.size, 0)
        units.forEachIndexed { index, unit ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val unitText = fullText.substring(unit.charStart.toInt(), unit.charEnd.toInt())
            if (unitText.isBlank()) return@forEachIndexed
            recordOutbound(FEATURE_PROOFREAD, "$title · ${unit.title}", unitText.length / 2)
            val unitIssues = proofreadUnit(provider, model, unitText)
            if (unitIssues == null) {
                failedUnits++
            } else {
                val now = System.currentTimeMillis()
                unitIssues.forEach { issue ->
                    val start = unit.charStart + issue.offset
                    issues += IssueEntry(
                        offset = start,
                        length = issue.length,
                        original = issue.original,
                        suggestion = issue.suggestion,
                        type = issue.type,
                        unitTitle = unit.title,
                    )
                    // 只标不改：结果落批注，正文不动；note 写明建议供阅读时核对
                    addAnnotation(
                        AnnotationEntity(
                            bookId = bookId,
                            startCharOffset = start,
                            endCharOffset = start + issue.length,
                            selectedText = issue.original,
                            color = ANNOTATION_COLOR,
                            note = "【AI 校对·${issue.type.label}】「${issue.original}」→「${issue.suggestion}」",
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                }
            }
            _state.value = UiState.Running(index + 1, units.size, issues.size)
        }
        _state.value = UiState.Done(
            issues = issues,
            confirmations = confirmationsOf(issues),
            failedUnits = failedUnits,
        )
    }

    /** 单单位送校：流异常或解析为 null 整体重试一次（同 SummaryEngine 口径）；仍败返回 null。 */
    private suspend fun proofreadUnit(
        provider: AiProvider,
        model: String,
        unitText: String,
    ): List<ProofreadIssue>? {
        repeat(2) {
            try {
                val reply = StringBuilder()
                withTimeout(UNIT_TIMEOUT_MS) {
                    provider.chat(ProofreadPrompt.buildMessages(unitText), model)
                        .collect { reply.append(it) }
                }
                ProofreadPrompt.parseIssues(reply.toString(), unitText)?.let { return it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                // 服务商错误（余额/限流等）没必要再试一次，直接记失败单位
                return null
            } catch (e: Exception) {
                // 网络/超时：重试一次
            }
        }
        return null
    }

    /** 逐条勾选/取消确认列表的一行（生成配方的原料）。 */
    fun toggleConfirmation(index: Int) {
        val s = _state.value as? UiState.Done ?: return
        _state.value = s.copy(
            confirmations = s.confirmations.mapIndexed { i, c ->
                if (i == index) c.copy(checked = !c.checked) else c
            },
        )
    }

    /**
     * 把勾选的确认项生成清洗配方（M30 后半）：纯替换规则——toggles 全关、不加广告正则，
     * 配方里只有用户逐条确认的修改，交 M16 既有预览/物化链路执行（不新增执行路径）。
     *
     * 幂等过滤：建议文本包含原文片段的规则会让自己再次命中（再跑一遍结果会变），丢弃；
     * 重复（原文,建议）去重。没有可用勾选返回 null。
     */
    fun buildRecipeProfile(): CleanProfile? {
        val s = _state.value as? UiState.Done ?: return null
        val rules = s.confirmations
            .filter { it.checked }
            .filter { it.original.isNotBlank() && it.suggestion.isNotEmpty() && it.original != it.suggestion }
            .filter { !it.suggestion.contains(it.original) }
            .distinctBy { it.original to it.suggestion }
            .take(MAX_RECIPE_RULES)
            .map { Regex(Regex.escape(it.original)) to it.suggestion }
        if (rules.isEmpty()) return null
        return CleanProfile(
            level = CleanLevel.CUSTOM,
            toggles = CleanToggles.NONE,
            replacements = rules,
        )
    }

    companion object {
        /** 外发台账的 feature 名（与内置提示词登记表一致）。 */
        const val FEATURE_PROOFREAD = "AI 校对"

        /** 校对批注的 note 前缀：重跑清理与「这条批注来自 AI 校对」的识别标记。 */
        const val NOTE_PREFIX = "【AI 校对"

        /** 校对批注的高亮色（橙，与「存为批注」的黄区分）。 */
        const val ANNOTATION_COLOR = 0xFFFFB74DL

        private const val UNIT_TIMEOUT_MS = 90_000L

        /** 一份校对配方最多带入的替换规则数（防极端确认列表拖慢清洗）。 */
        private const val MAX_RECIPE_RULES = 100

        /** 确认列表：同一「原文→建议」出现多次合并成一行，计数展示。 */
        internal fun confirmationsOf(issues: List<IssueEntry>): List<Confirmation> =
            issues.groupBy { Triple(it.original, it.suggestion, it.type) }
                .map { (key, group) ->
                    Confirmation(
                        original = key.first,
                        suggestion = key.second,
                        type = key.third,
                        occurrences = group.size,
                    )
                }

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ProofreadAiViewModel(
                    aiProvider = container::aiProvider,
                    loadFullText = { bookId ->
                        ChapterRuleAiViewModel.loadBookFullText(container, bookId)
                    },
                    chaptersFor = { bookId -> container.bookshelfRepository.getChapters(bookId) },
                    bookTitle = { bookId ->
                        container.bookshelfRepository.getBook(bookId)?.title ?: "未知书籍"
                    },
                    preferences = { container.settingsRepository.preferences.first() },
                    markProofreadConfirmed = {
                        container.settingsRepository.setAiProofreadConfirmed(true)
                    },
                    recordOutbound = { feature, scope, tokens ->
                        container.aiContentGate.record(feature, scope, tokens)
                    },
                    clearProofreadAnnotations = { bookId ->
                        container.database.annotationDao()
                            .deleteByBookAndNotePrefix(bookId, NOTE_PREFIX)
                    },
                    addAnnotation = { container.bookshelfRepository.addAnnotation(it) },
                )
            }
        }
    }
}
