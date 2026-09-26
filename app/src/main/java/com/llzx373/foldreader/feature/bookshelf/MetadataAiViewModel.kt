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
import com.llzx373.foldreader.core.ai.prompt.MetadataPrompt
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.format.EncodingDetector
import com.llzx373.foldreader.core.format.MetadataHeadSampler
import com.llzx373.foldreader.core.format.txt.UriChannels
import com.llzx373.foldreader.core.metadata.AiMetadataWrite
import com.llzx373.foldreader.core.metadata.BookMetaSnapshot
import com.llzx373.foldreader.core.metadata.BookMetaSources
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
 * M17「AI 元数据补全」状态机（单书与书架批量共用一台）：
 *
 * 单书：空闲 → 采样开头（[UiState.Preparing]）→ 首次外发确认（[UiState.AwaitConfirmation]，仅首次）
 * → AI 生成（[UiState.Generating]）→ [UiState.Done]（本次补上的字段）/ [UiState.Failure]（可重试）。
 *
 * 批量（[startBatch]）：确认后串行逐本跑同一条管线，进度走 [UiState.BatchRunning]（N/M + 当前书名），
 * [cancelBatch] 中途可取消；单本失败/跳过不中断队列，汇总落 [UiState.BatchDone]。
 *
 * 写回纪律（全局规则）：AI 只补「当前为空且未被用户锁定」的字段——决策在
 * [BookMetaSources.planAiMetadataWrite]（纯 JVM），DAO 层「只填空值」的 CASE WHEN 再兜一层；
 * 用户编辑过的字段打了 user 标，单书与批量都不会再改它。
 *
 * 所有外部依赖都是注入的 lambda，构造层面不碰 Android（factory 除外），纯 JVM 可单测。
 */
class MetadataAiViewModel(
    private val aiProvider: suspend () -> AiProvider?,
    /** 读头部采样（源文件前几 KB 解码后截取）；非 TXT 或读取失败返回 null。 */
    private val sampleHead: suspend (bookId: Long) -> String?,
    private val bookMeta: suspend (bookId: Long) -> BookMetaSnapshot?,
    private val bookTitle: suspend (bookId: Long) -> String,
    private val preferences: suspend () -> ReadingPreferences,
    private val markMetadataConfirmed: suspend () -> Unit,
    private val recordOutbound: (feature: String, scope: String, estimatedTokens: Int) -> Unit,
    private val applyWrite: suspend (bookId: Long, write: AiMetadataWrite) -> Unit,
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState

        /** 单书：加载头部采样中。 */
        data object Preparing : UiState

        /**
         * 首次外发前的一次性确认（单书与批量共用）：[scopeText] 明示数据范围——
         * 单书是「该书开头采样」，批量是「所选 N 本书各自的开头采样」。
         */
        data class AwaitConfirmation(val baseUrl: String, val scopeText: String) : UiState

        data object Generating : UiState

        /** 单书完成：[filledFields] 为本次补上的字段（BookMetaSources.FIELD_*），空 = 没有可补的。 */
        data class Done(val filledFields: List<String>) : UiState

        data class Failure(val message: String) : UiState

        /** 批量进行中：[done] 已处理本数 / [total] 总本数 / [currentTitle] 当前书名。 */
        data class BatchRunning(val done: Int, val total: Int, val currentTitle: String) : UiState

        data class BatchDone(
            val processed: Int,
            val filled: Int,
            val skipped: Int,
            val failed: Int,
            val cancelled: Boolean,
        ) : UiState
    }

    /** 单本跑完的三种结果（批量据此计数）。 */
    private sealed interface OneOutcome {
        data class Filled(val fields: List<String>) : OneOutcome

        /** 没有可补字段（已完整或被用户锁定），未打 AI。 */
        data object NothingToFill : OneOutcome

        /** 不支持/读不出来（非 TXT、源不可读）。 */
        data object Skipped : OneOutcome
        data class Failed(val message: String) : OneOutcome
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var bookId: Long = 0
    private var sampleText: String = ""
    private var pendingBatch: List<Long>? = null
    private var job: Job? = null

    /** 单书入口：采样开头 →（首次需确认）→ 生成。进行中重复调用忽略。 */
    fun start(id: Long) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            bookId = id
            _state.value = UiState.Preparing
            val sample = sampleHead(id)
            if (sample == null) {
                _state.value = UiState.Failure("无法读取该书开头文本，AI 补全信息仅支持 TXT 书籍")
                return@launch
            }
            sampleText = sample
            val prefs = preferences()
            if (prefs.aiMetadataConfirmed) {
                generate()
            } else {
                _state.value = UiState.AwaitConfirmation(
                    prefs.aiBaseUrl,
                    "该书开头的采样文本（约 ${sample.length} 字），不包含正文全文",
                )
            }
        }
    }

    /** 书架批量入口：确认后串行逐本补全。进行中重复调用忽略。 */
    fun startBatch(bookIds: List<Long>) {
        if (job?.isActive == true || bookIds.isEmpty()) return
        job = viewModelScope.launch {
            val prefs = preferences()
            if (prefs.aiMetadataConfirmed) {
                runBatch(bookIds)
            } else {
                pendingBatch = bookIds
                _state.value = UiState.AwaitConfirmation(
                    prefs.aiBaseUrl,
                    "所选 ${bookIds.size} 本书各自的开头采样文本（逐本外发），不包含正文全文",
                )
            }
        }
    }

    /** 确认框「同意并生成」：落一次性确认标记后继续（单书或挂起的批量）。 */
    fun confirmAndGenerate() {
        if (_state.value !is UiState.AwaitConfirmation) return
        viewModelScope.launch {
            markMetadataConfirmed()
            val batch = pendingBatch
            if (batch != null) {
                pendingBatch = null
                runBatch(batch)
            } else {
                generate()
            }
        }
    }

    /** 失败重试：采样已在内存，直接重走生成，不重读文件。 */
    fun retry() {
        if (_state.value !is UiState.Failure || sampleText.isEmpty()) return
        viewModelScope.launch { generate() }
    }

    /** 批量取消：当前这本跑不完就停（协程取消点生效），汇总为已取消。 */
    fun cancelBatch() {
        job?.cancel()
    }

    private suspend fun generate() {
        val provider = aiProvider()
        if (provider == null) {
            _state.value = UiState.Failure("请先在设置中完成 AI 服务配置")
            return
        }
        _state.value = UiState.Generating
        when (val outcome = completeOne(bookId, provider, preferences().aiModelGeneral, sampleText)) {
            is OneOutcome.Filled -> _state.value = UiState.Done(outcome.fields)
            OneOutcome.NothingToFill -> _state.value = UiState.Done(emptyList())
            OneOutcome.Skipped ->
                _state.value = UiState.Failure("无法读取该书开头文本，AI 补全信息仅支持 TXT 书籍")
            is OneOutcome.Failed -> _state.value = UiState.Failure(outcome.message)
        }
    }

    private suspend fun runBatch(bookIds: List<Long>) {
        val provider = aiProvider()
        if (provider == null) {
            _state.value = UiState.Failure("请先在设置中完成 AI 服务配置")
            return
        }
        val model = preferences().aiModelGeneral
        var filled = 0
        var skipped = 0
        var failed = 0
        var done = 0
        try {
            bookIds.forEach { id ->
                _state.value = UiState.BatchRunning(done, bookIds.size, bookTitle(id))
                when (completeOne(id, provider, model, cachedSample = null)) {
                    is OneOutcome.Filled -> filled++
                    OneOutcome.NothingToFill -> skipped++
                    OneOutcome.Skipped -> skipped++
                    is OneOutcome.Failed -> failed++
                }
                done++
            }
            _state.value = UiState.BatchDone(done, filled, skipped, failed, cancelled = false)
        } catch (e: CancellationException) {
            _state.value = UiState.BatchDone(done, filled, skipped, failed, cancelled = true)
            throw e
        }
    }

    /**
     * 单本管线：快照判可补 → 采样（[cachedSample] 优先，批量逐本现采）→ 台账 → AI →
     * 只补空且未锁定的字段。无可补字段时**不打 AI**（省 token，也让批量跳过静默）。
     */
    private suspend fun completeOne(
        bookId: Long,
        provider: AiProvider,
        model: String,
        cachedSample: String?,
    ): OneOutcome {
        val snapshot = bookMeta(bookId) ?: return OneOutcome.Failed("书籍不存在")
        val fillable = BookMetaSources.FIELDS.any { field ->
            val current = when (field) {
                BookMetaSources.FIELD_AUTHOR -> snapshot.author
                BookMetaSources.FIELD_SYNOPSIS -> snapshot.synopsis
                else -> snapshot.genreTag
            }
            current.isNullOrBlank() && !BookMetaSources.isUserOwned(snapshot.metaSource, field)
        }
        if (!fillable) return OneOutcome.NothingToFill

        val sample = cachedSample ?: sampleHead(bookId) ?: return OneOutcome.Skipped
        val title = bookTitle(bookId)
        // 台账 token 估算口径与 M15 一致：字符数 / 2，只用于审计量级。
        recordOutbound("元数据补全", title, sample.length / 2)
        val reply = StringBuilder()
        try {
            withTimeout(AI_TIMEOUT_MS) {
                provider.chat(MetadataPrompt.buildMessages(sample, title), model)
                    .collect { reply.append(it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            return OneOutcome.Failed(e.message ?: "调用失败")
        } catch (e: Exception) {
            return OneOutcome.Failed("网络不可达或响应异常")
        }
        val suggestion = MetadataPrompt.parseSuggestion(reply.toString())
        if (suggestion == null ||
            (suggestion.author == null && suggestion.synopsis == null && suggestion.genreTag == null)
        ) {
            return OneOutcome.Failed("AI 未能给出可用信息，可重试")
        }
        val write = BookMetaSources.planAiMetadataWrite(
            snapshot,
            suggestion.author,
            suggestion.synopsis,
            suggestion.genreTag,
        ) ?: return OneOutcome.NothingToFill
        applyWrite(bookId, write)
        return OneOutcome.Filled(write.filledFields)
    }

    companion object {
        private const val AI_TIMEOUT_MS = 90_000L

        /** 头部读取字节预算：够解码出 [MetadataHeadSampler.HEAD_CHARS] 个字符（GBK 2 字节/字兜底）。 */
        private const val HEAD_BYTES = 16 * 1024

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MetadataAiViewModel(
                    aiProvider = container::aiProvider,
                    sampleHead = { bookId -> sampleBookHead(container, bookId) },
                    bookMeta = { bookId ->
                        container.bookshelfRepository.getBook(bookId)?.let {
                            BookMetaSnapshot(it.author, it.description, it.genreTag, it.metaSource)
                        }
                    },
                    bookTitle = { bookId ->
                        container.bookshelfRepository.getBook(bookId)?.title ?: "未知书籍"
                    },
                    preferences = { container.settingsRepository.preferences.first() },
                    markMetadataConfirmed = {
                        container.settingsRepository.setAiMetadataConfirmed(true)
                    },
                    recordOutbound = { feature, scope, tokens ->
                        container.aiContentGate.record(feature, scope, tokens)
                    },
                    applyWrite = { bookId, write ->
                        container.bookshelfRepository.applyAiMetadata(
                            bookId, write.author, write.synopsis, write.genreTag, write.metaSource,
                        )
                    },
                )
            }
        }

        /**
         * 读头部采样：只读源文件前 [HEAD_BYTES] 字节解码截取，**不整本加载**
         * （与 M15/M16 的 `loadBookFullText` 不同——补全只需要开头几 KB）。
         * 仅 TXT：EPUB/漫画/PDF 的头部是容器二进制，采出来没有意义。
         * 编码优先取书籍记录的编码，未记录时按头部字节自动检测。
         */
        internal suspend fun sampleBookHead(container: AppContainer, bookId: Long): String? =
            withContext(Dispatchers.IO) {
                val book = container.bookshelfRepository.getBook(bookId) ?: return@withContext null
                if (book.format != BookFormat.TXT) return@withContext null
                runCatching {
                    val bytes = UriChannels.open(container.appContext, Uri.parse(book.fileUri))
                        .use { channel -> UriChannels.readHead(channel, HEAD_BYTES) }
                    val charset = EncodingDetector.forNameOrNull(book.encoding)
                        ?: EncodingDetector.detect(bytes).charset
                    MetadataHeadSampler.sample(String(bytes, charset))
                }.getOrNull()?.takeIf { it.isNotBlank() }
            }
    }
}
