package com.llzx373.foldreader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.llzx373.foldreader.AppContainer
import com.llzx373.foldreader.core.format.ChapterRulePreview
import com.llzx373.foldreader.core.format.ChapterRuleSynthesizer
import com.llzx373.foldreader.feature.bookshelf.ChapterRuleAiViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 「选中行生成章节规则」状态机：
 * 空闲 → 加载正文/吸附行/合成候选（[UiState.Preparing]）→ 候选预览（[UiState.Preview]，
 * 每条候选含本地试切报告）→ 保存规则并重扫（[UiState.Saving]）→ [UiState.Done] / [UiState.Failure]。
 *
 * 与 M15「AI 识别章节」的差别只在候选来源（本地合成，不外发）；试切预览、
 * 保存重建链路完全同构。所有外部依赖都是注入的 lambda，构造层面不碰 Android
 * （factory 除外），状态机可纯 JVM 单测。
 */
class ChapterRuleFromSelectionViewModel(
    /** 读全书正文；非文本型书或读取失败返回 null。 */
    private val loadFullText: suspend (bookId: Long) -> String?,
    private val saveChapterRules: suspend (bookId: Long, rules: List<String>) -> Unit,
    private val rebuildChapters: suspend (bookId: Long) -> Unit,
    private val chapterCount: suspend (bookId: Long) -> Int,
) : ViewModel() {

    /** 一条候选规则：正则 + 来源说明 + 本地试切预览报告。 */
    data class CandidateUi(
        val regex: String,
        val explanation: String,
        val preview: ChapterRulePreview.RulePreview,
    )

    sealed interface UiState {
        data object Idle : UiState

        /** 加载正文 / 吸附行 / 合成并试切候选中。 */
        data object Preparing : UiState

        data class Preview(val sourceLine: String, val candidates: List<CandidateUi>) : UiState

        /** 已选定，正在保存规则并重扫目录。 */
        data object Saving : UiState
        data class Done(val chapterCount: Int) : UiState
        data class Failure(val message: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bookId: Long = 0
    private var job: Job? = null

    /** 入口发起：加载正文 → 吸附选中点所在原始行 → 合成候选并逐条试切。进行中重复调用忽略。 */
    fun start(id: Long, anchorOffset: Long) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            bookId = id
            _state.value = UiState.Preparing
            val text = loadFullText(id)
            if (text == null) {
                _state.value = UiState.Failure("无法读取该书正文，章节规则生成仅支持 TXT 书籍")
                return@launch
            }
            val line = ChapterRuleSynthesizer.sourceLineAt(text, anchorOffset)
            if (line == null) {
                _state.value = UiState.Failure("选中位置定位不到有效行")
                return@launch
            }
            val patterns = ChapterRuleSynthesizer.synthesize(line)
            if (patterns.isEmpty()) {
                _state.value = UiState.Failure("该行过长，不像章节标题，请换一行试试")
                return@launch
            }
            _state.value = UiState.Preview(
                sourceLine = line,
                candidates = patterns.mapIndexed { index, pattern ->
                    CandidateUi(
                        regex = pattern,
                        explanation = if (patterns.size > 1 && index == 0) {
                            "数字泛化：匹配同格式的所有编号标题（推荐）"
                        } else {
                            "精确匹配：只命中与该行完全一致的标题行"
                        },
                        preview = ChapterRulePreview.preview(text, pattern),
                    )
                },
            )
        }
    }

    /** 用户选定候选：存为本书自定义章节规则（替换语义，同 M15）→ 重扫目录 → 完成。 */
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
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ChapterRuleFromSelectionViewModel(
                    loadFullText = { bookId ->
                        ChapterRuleAiViewModel.loadBookFullText(container, bookId)
                    },
                    saveChapterRules = container.bookPrefsRepository::setChapterRules,
                    rebuildChapters = container::rebuildBookChapters,
                    chapterCount = { bookId ->
                        container.bookshelfRepository.getChapters(bookId).size
                    },
                )
            }
        }
    }
}
