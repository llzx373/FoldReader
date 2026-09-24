package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.ai.prompt.GlossaryBackfillPrompt
import com.llzx373.foldreader.core.ai.prompt.UnitTranslatePrompt
import com.llzx373.foldreader.core.data.db.GlossaryTermEntity
import com.llzx373.foldreader.core.data.db.TranslationDao
import com.llzx373.foldreader.core.data.db.TranslationEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.translate.GlossaryRepository
import com.llzx373.foldreader.core.translate.TranslationStore
import com.llzx373.foldreader.core.translate.TranslationUnit
import com.llzx373.foldreader.core.translate.splitIntoParagraphs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * 文本翻译引擎（M19 基础层 + M20 术语注入/回填）：单章（块）翻译 + 按页即时翻译。
 *
 * 本类只做「单位文本 → 段落结构化译文 → 落盘 + 台账状态机」这条确定性链路。
 *
 * - [provider] 可空：未配置 AI 服务时所有入口直接失败返回，不触碰任何网络组件（v2.6）；
 * - 翻译模型取「翻译模型」配置、未配时回落通用模型（同 M18 ReaderViewModel 口径）；
 * - M20 术语注入：每个单位调用时现取 [glossaryRepository] 合并后的**已确认**术语
 *   （书 > 系列 > 全局），术语表 UI 里的确认动作对下一个单位即时生效；
 * - M20 术语回填：本书前 3 个完成单位各触发一次模型回填候选（见
 *   [backfillGlossaryIfEarly]），候选未确认不参与注入（R6）；
 * - 段落结构化落盘：解析校验（数量一致）失败整体重试一次，再失败置 [TranslationEntity.STATUS_FAILED]。
 */
class TranslateEngine(
    private val provider: AiProvider?,
    private val contentGate: AiContentGate,
    private val store: TranslationStore,
    private val translationDao: TranslationDao,
    private val preferences: suspend () -> ReadingPreferences,
    /** M20 术语表仓库；null = 不注入不回填（行为同 M19）。 */
    private val glossaryRepository: GlossaryRepository? = null,
) {

    /**
     * 翻译一个单位（章 / 块）并落盘。返回段落数；失败返回 [Result.failure]。
     *
     * 状态机：upsert translating →（成功）saveUnit + done（带回模型与段落数）
     * /（重试后仍失败）failed。取消（[CancellationException]）直接上抛，不留 failed。
     */
    suspend fun translateUnit(
        bookId: Long,
        bookTitle: String,
        unit: TranslationUnit,
        unitText: String,
        lang: AiTargetLang,
        systemOverride: String? = null,
    ): Result<Int> {
        val provider = provider
            ?: return Result.failure(IllegalStateException("AI 服务未配置"))
        val prefs = preferences()
        val model = prefs.aiModelTranslation.ifBlank { prefs.aiModelGeneral }
        val paragraphs = splitIntoParagraphs(unitText)
        if (paragraphs.isEmpty()) {
            return Result.failure(IllegalArgumentException("单位文本为空或无有效段落"))
        }
        // 外发台账：token 估算口径同「选中即译」——字符数 / 2 的保守量级估算，台账只用于审计
        contentGate.record(FEATURE_UNIT_TRANSLATION, bookTitle, unitText.length / 2)
        val langKey = lang.name
        translationDao.upsert(
            TranslationEntity(
                bookId = bookId,
                lang = langKey,
                unitKind = unit.kind.name.lowercase(),
                unitIndex = unit.index,
                status = TranslationEntity.STATUS_TRANSLATING,
                model = model,
                paragraphCount = 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        // 术语注入现取：确认动作对下一个单位即时生效（R6 优先级合并在仓库层）
        val glossary = glossaryRepository?.mergedConfirmed(bookId.toString()).orEmpty()
        val messages = UnitTranslatePrompt.buildMessages(
            paragraphs = paragraphs,
            targetLang = lang,
            glossary = glossary,
            systemOverride = systemOverride,
        )
        var lastError: Throwable = IllegalStateException("翻译失败")
        // 首次 + 整体重试 1 次：流异常或段落数量校验失败都算一次失败
        repeat(2) {
            try {
                val reply = StringBuilder()
                provider.chat(messages, model).collect { delta -> reply.append(delta) }
                val translated = UnitTranslatePrompt.parseParagraphs(reply.toString(), paragraphs.size)
                if (translated != null) {
                    store.saveUnit(bookId, langKey, unit, translated)
                    translationDao.updateStatus(
                        bookId = bookId,
                        lang = langKey,
                        unitIndex = unit.index,
                        status = TranslationEntity.STATUS_DONE,
                        model = model,
                        paragraphCount = translated.size,
                        updatedAt = System.currentTimeMillis(),
                    )
                    backfillGlossaryIfEarly(provider, prefs, bookId, bookTitle, lang, unitText, translated)
                    return Result.success(translated.size)
                }
                lastError = IllegalStateException("模型输出段落数与输入不一致或 JSON 畸形")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        translationDao.updateStatus(
            bookId = bookId,
            lang = langKey,
            unitIndex = unit.index,
            status = TranslationEntity.STATUS_FAILED,
            model = model,
            paragraphCount = 0,
            updatedAt = System.currentTimeMillis(),
        )
        return Result.failure(lastError)
    }

    /**
     * M20 术语回填：本书前 3 个完成单位（含本次，按 done 计数）各触发一次，
     * 原文/译文各取开头样本让模型抽专名对照，候选以 origin=auto / confirmed=0
     * 落术语表（确认后才参与注入，R6）。回填是增强不是主链路：失败静默。
     */
    private suspend fun backfillGlossaryIfEarly(
        provider: AiProvider,
        prefs: ReadingPreferences,
        bookId: Long,
        bookTitle: String,
        lang: AiTargetLang,
        unitText: String,
        translated: List<String>,
    ) {
        val repository = glossaryRepository ?: return
        try {
            val doneCount =
                translationDao.countByStatus(bookId, lang.name, TranslationEntity.STATUS_DONE)
            if (doneCount > BACKFILL_UNIT_LIMIT) return
            val sourceSample = unitText.take(BACKFILL_SAMPLE_CHARS)
            val translatedSample = translated.joinToString("\n").take(BACKFILL_SAMPLE_CHARS)
            if (sourceSample.isBlank() || translatedSample.isBlank()) return
            contentGate.record(
                FEATURE_GLOSSARY_BACKFILL,
                bookTitle,
                (sourceSample.length + translatedSample.length) / 2,
            )
            val reply = StringBuilder()
            provider.chat(
                GlossaryBackfillPrompt.buildMessages(sourceSample, translatedSample),
                prefs.aiModelGeneral,
            ).collect { reply.append(it) }
            repository.upsertCandidates(
                GlossaryTermEntity.SCOPE_BOOK,
                bookId.toString(),
                GlossaryBackfillPrompt.parseTerms(reply.toString()),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 回填失败静默：候选只是增强，下一次成功单位还会再触发
        }
    }

    /**
     * 按页即时翻译（R11）：页面文本切段落后透传 provider 的 SSE 增量流。
     *
     * 结果**不落译本副本**——按页翻译是即时查看与调优手段（对照面板流式渲染，
     * 增量解析用 `core/ai/prompt/PartialJsonArray`）。外发台账由调用方记。
     * 未配置 AI 服务时收集即抛 [IllegalStateException]。
     */
    fun translateTextStream(
        text: String,
        lang: AiTargetLang,
        systemOverride: String? = null,
    ): Flow<String> = flow {
        val provider = provider ?: throw IllegalStateException("AI 服务未配置")
        val paragraphs = splitIntoParagraphs(text)
        check(paragraphs.isNotEmpty()) { "页面文本为空或无有效段落" }
        val prefs = preferences()
        val model = prefs.aiModelTranslation.ifBlank { prefs.aiModelGeneral }
        emitAll(
            provider.chat(
                UnitTranslatePrompt.buildMessages(
                    paragraphs = paragraphs,
                    targetLang = lang,
                    systemOverride = systemOverride,
                ),
                model,
            ),
        )
    }

    companion object {
        /** 外发台账的 feature 名（与内置提示词登记表一致）。 */
        const val FEATURE_UNIT_TRANSLATION = "章节翻译"

        /** 外发台账：术语回填（原文/译文对照样本外发）。 */
        const val FEATURE_GLOSSARY_BACKFILL = "术语回填"

        /** 一本书触发回填的完成单位上限（前 3 个 done 单位各一次）。 */
        private const val BACKFILL_UNIT_LIMIT = 3

        /** 回填样本长度：原文/译文各取开头这么多字符。 */
        private const val BACKFILL_SAMPLE_CHARS = 500
    }
}
