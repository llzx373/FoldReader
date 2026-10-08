package com.llzx373.foldreader.feature.summary

import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.ai.prompt.BookOutlinePrompt
import com.llzx373.foldreader.core.ai.prompt.ChapterSummaryPrompt
import com.llzx373.foldreader.core.data.db.BookOutlineDao
import com.llzx373.foldreader.core.data.db.BookOutlineEntity
import com.llzx373.foldreader.core.data.db.ChapterSummaryDao
import com.llzx373.foldreader.core.data.db.ChapterSummaryEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.translate.TranslationUnit
import kotlinx.coroutines.CancellationException

/**
 * 章节摘要引擎（M29）：单位摘要 + 全书大纲聚合这条确定性链路。
 *
 * - [provider] 可空：未配置 AI 服务时所有入口直接失败返回，不触碰任何网络组件；
 * - 摘要模型取「通用模型」（摘要不是翻译任务，不用翻译模型）；
 * - 状态机：upsert summarizing →（成功）done（带回摘要正文与模型）/（重试后仍失败）failed。
 *   取消（[CancellationException]）直接上抛，不留 failed（同 TranslateEngine 口径）；
 * - 外发台账：feature=章节摘要 / 全书大纲，token 估算口径同翻译（字符数 / 2）。
 */
class SummaryEngine(
    private val provider: AiProvider?,
    private val contentGate: AiContentGate,
    private val summaryDao: ChapterSummaryDao,
    private val outlineDao: BookOutlineDao,
    private val preferences: suspend () -> ReadingPreferences,
) {

    /**
     * 摘要一个单位（章 / 块）并落库。返回摘要字符数；失败返回 [Result.failure]。
     * 空输出（解析为 null）整体重试一次，再失败置 [ChapterSummaryEntity.STATUS_FAILED]。
     */
    suspend fun summarizeUnit(
        bookId: Long,
        bookTitle: String,
        unit: TranslationUnit,
        unitText: String,
        lang: AiTargetLang,
    ): Result<Int> {
        val provider = provider
            ?: return Result.failure(IllegalStateException("AI 服务未配置"))
        if (unitText.isBlank()) {
            return Result.failure(IllegalArgumentException("单位文本为空"))
        }
        val model = preferences().aiModelGeneral
        // 外发台账：token 估算口径同「选中即译」——字符数 / 2 的保守量级估算，台账只用于审计
        contentGate.record(FEATURE_CHAPTER_SUMMARY, bookTitle, unitText.length / 2)
        val langKey = lang.name
        summaryDao.upsert(
            ChapterSummaryEntity(
                bookId = bookId,
                lang = langKey,
                unitIndex = unit.index,
                unitKind = unit.kind.name.lowercase(),
                unitTitle = unit.title,
                status = ChapterSummaryEntity.STATUS_SUMMARIZING,
                summary = "",
                model = model,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        var lastError: Throwable = IllegalStateException("摘要失败")
        // 首次 + 整体重试 1 次：流异常或空输出都算一次失败
        repeat(2) {
            try {
                val reply = StringBuilder()
                provider.chat(
                    ChapterSummaryPrompt.buildMessages(unit.title, unitText, lang),
                    model,
                ).collect { delta -> reply.append(delta) }
                val summary = ChapterSummaryPrompt.parseSummary(reply.toString())
                if (summary != null) {
                    summaryDao.updateStatus(
                        bookId = bookId,
                        lang = langKey,
                        unitIndex = unit.index,
                        status = ChapterSummaryEntity.STATUS_DONE,
                        summary = summary,
                        model = model,
                        updatedAt = System.currentTimeMillis(),
                    )
                    return Result.success(summary.length)
                }
                lastError = IllegalStateException("模型输出为空或畸形")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        summaryDao.updateStatus(
            bookId = bookId,
            lang = langKey,
            unitIndex = unit.index,
            status = ChapterSummaryEntity.STATUS_FAILED,
            summary = "",
            model = model,
            updatedAt = System.currentTimeMillis(),
        )
        return Result.failure(lastError)
    }

    /**
     * 全书大纲（M29）：聚合该书当前语言下全部已完成摘要（按单位号升序）。
     * 返回纳入聚合的摘要条数；尚无已完成摘要 / 模型输出为空返回 [Result.failure]。
     */
    suspend fun generateOutline(
        bookId: Long,
        bookTitle: String,
        lang: AiTargetLang,
    ): Result<Int> {
        val provider = provider
            ?: return Result.failure(IllegalStateException("AI 服务未配置"))
        val done = summaryDao.getDoneForBook(bookId, lang.name)
        if (done.isEmpty()) {
            return Result.failure(IllegalStateException("还没有已生成的章节摘要"))
        }
        val model = preferences().aiModelGeneral
        val chain = done.map { it.unitTitle to it.summary }
        contentGate.record(
            FEATURE_BOOK_OUTLINE,
            bookTitle,
            done.sumOf { it.summary.length } / 2,
        )
        var lastError: Throwable = IllegalStateException("大纲生成失败")
        repeat(2) {
            try {
                val reply = StringBuilder()
                provider.chat(BookOutlinePrompt.buildMessages(bookTitle, chain, lang), model)
                    .collect { delta -> reply.append(delta) }
                val outline = BookOutlinePrompt.parseOutline(reply.toString())
                if (outline != null) {
                    outlineDao.upsert(
                        BookOutlineEntity(
                            bookId = bookId,
                            lang = lang.name,
                            outline = outline,
                            summaryCount = done.size,
                            model = model,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    return Result.success(done.size)
                }
                lastError = IllegalStateException("模型输出为空或畸形")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        return Result.failure(lastError)
    }

    companion object {
        /** 外发台账的 feature 名（与内置提示词登记表一致）。 */
        const val FEATURE_CHAPTER_SUMMARY = "章节摘要"
        const val FEATURE_BOOK_OUTLINE = "全书大纲"
    }
}
