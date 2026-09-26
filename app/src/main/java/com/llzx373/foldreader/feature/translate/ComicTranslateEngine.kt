package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.ai.prompt.ComicTranslatePrompt
import com.llzx373.foldreader.core.ai.prompt.ComicVisionTranslatePrompt
import com.llzx373.foldreader.core.ai.prompt.PartialJsonArray
import com.llzx373.foldreader.core.data.db.ComicPageTranslationDao
import com.llzx373.foldreader.core.data.db.ComicPageTranslationEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrTextLine
import com.llzx373.foldreader.core.translate.ComicTranslationStore
import com.llzx373.foldreader.core.translate.CrossPageMerge
import com.llzx373.foldreader.core.translate.GlossaryRepository
import kotlinx.coroutines.CancellationException

/**
 * 漫画页翻译引擎（M22）：单页「气泡 → 逐气泡流式译文 → 落盘 + 台账状态机」链路。
 *
 * 与 TranslateEngine 同一套约定：
 * - [provider] 可空：未配置 AI 服务时所有入口直接失败返回，不触碰任何网络组件（v2.6）；
 * - 翻译模型取「翻译模型」配置、未配时回落通用模型；
 * - 术语注入现取 [glossaryRepository] 合并后的已确认术语（书 > 系列 > 全局，
 *   系列层级经 [seriesKeyFor] 由漫画主干 `comicSeriesStem` 产出，跨卷共享，R6）；
 * - OCR 不在这条链路上：[bubblesFor] 由调用方注入（生产 = 缓存优先、缺失才跑
 *   OcrEngine 并落 `.ocr.json`；测试 = 合成气泡），换模型/提示词重译不重跑 OCR；
 * - 解析校验（数量与气泡数一致）失败整体重试一次，再失败置
 *   [ComicPageTranslationEntity.STATUS_FAILED]；取消直接上抛不留 failed。
 */
class ComicTranslateEngine(
    private val provider: AiProvider?,
    private val contentGate: AiContentGate,
    private val store: ComicTranslationStore,
    private val pageDao: ComicPageTranslationDao,
    private val preferences: suspend () -> ReadingPreferences,
    private val glossaryRepository: GlossaryRepository,
    /** 系列术语层级：返回该书的 seriesKey（漫画主干），无系列概念返回 null。 */
    private val seriesKeyFor: suspend (bookId: Long) -> String?,
    /** 取一页的气泡（缓存优先；缺失由注入方识别并落缓存）。空列表 = 该页无文字。 */
    private val bubblesFor: suspend (bookId: Long, pageIndex: Int) -> List<OcrBubble>,
    /**
     * 视觉模式（M23）：取一页的 JPEG base64（注入方负责打开内容源、降采样、编码）；
     * 返回 null = 取不到页图像。默认实现恒 null（未接线时视觉入口按失败返回）。
     */
    private val pageImageBase64For: suspend (bookId: Long, pageIndex: Int) -> String? = { _, _ -> null },
) {

    /**
     * 翻译一页并落盘。返回气泡数；失败返回 [Result.failure]。
     *
     * [onBubble] 每闭合一个气泡译文回调一次（气泡序号, 译文）——流式渲染覆盖层
     * 与对照面板用；重试时已回调的局部结果作废（调用方按页重置）。
     */
    suspend fun translatePage(
        bookId: Long,
        bookTitle: String,
        pageIndex: Int,
        lang: AiTargetLang,
        systemOverride: String? = null,
        onBubble: (bubbleIndex: Int, text: String) -> Unit = { _, _ -> },
    ): Result<Int> {
        val provider = provider
            ?: return Result.failure(IllegalStateException("AI 服务未配置"))
        val rawBubbles = bubblesFor(bookId, pageIndex)
        if (rawBubbles.isEmpty()) {
            return Result.failure(IllegalArgumentException("该页未识别到文字气泡"))
        }
        val langKey = lang.name
        // 条漫跨页气泡合并（R10）：页边被切断的气泡与下一页顶部续段并成一个再翻
        val bubbles = maybeMergeCrossPage(bookId, pageIndex, langKey, rawBubbles)
        val prefs = preferences()
        val model = prefs.aiModelTranslation.ifBlank { prefs.aiModelGeneral }
        val totalChars = bubbles.sumOf { it.text.length }
        // 外发台账：token 估算口径同其他功能——字符数 / 2 的保守量级估算
        contentGate.record(FEATURE_COMIC_TRANSLATION, "$bookTitle 第${pageIndex + 1}页", totalChars / 2)
        pageDao.upsert(
            ComicPageTranslationEntity(
                bookId = bookId,
                lang = langKey,
                pageIndex = pageIndex,
                status = ComicPageTranslationEntity.STATUS_TRANSLATING,
                model = model,
                bubbleCount = 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        // 术语注入现取（书 > 系列 > 全局；确认动作对下一页即时生效，R6）
        val glossary = glossaryRepository.mergedConfirmed(bookId.toString(), seriesKeyFor(bookId))
        val messages = ComicTranslatePrompt.buildMessages(
            bubbleTexts = bubbles.map { it.text },
            targetLang = lang,
            glossary = glossary,
            systemOverride = systemOverride,
        )
        var lastError: Throwable = IllegalStateException("翻译失败")
        // 首次 + 整体重试 1 次：流异常或气泡数量校验失败都算一次失败
        repeat(2) {
            try {
                val reply = StringBuilder()
                var delivered = 0
                provider.chat(messages, model).collect { delta ->
                    reply.append(delta)
                    // 流式提取已闭合的译文元素：气泡译文逐个出现
                    val closed = PartialJsonArray.extractCompleteStrings(reply.toString())
                    while (delivered < closed.size && delivered < bubbles.size) {
                        onBubble(delivered, closed[delivered])
                        delivered++
                    }
                }
                val translated = ComicTranslatePrompt.parseTranslations(reply.toString(), bubbles.size)
                if (translated != null) {
                    store.saveTranslation(bookId, langKey, pageIndex, translated)
                    pageDao.updateStatus(
                        bookId = bookId,
                        lang = langKey,
                        pageIndex = pageIndex,
                        status = ComicPageTranslationEntity.STATUS_DONE,
                        model = model,
                        bubbleCount = translated.size,
                        updatedAt = System.currentTimeMillis(),
                    )
                    return Result.success(translated.size)
                }
                lastError = IllegalStateException("模型输出气泡数与输入不一致或 JSON 畸形")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        pageDao.updateStatus(
            bookId = bookId,
            lang = langKey,
            pageIndex = pageIndex,
            status = ComicPageTranslationEntity.STATUS_FAILED,
            model = model,
            bubbleCount = 0,
            updatedAt = System.currentTimeMillis(),
        )
        return Result.failure(lastError)
    }

    /**
     * 视觉模式翻译一页（M23）：页图像直接发给视觉模型，一次产出气泡框 + 原文 + 译文，
     * 跳过本地 OCR；识别产物照常落 `.ocr.json`（confidence=1f，原文单行），
     * 覆盖层/对照面板/微调与文本链路完全复用。模型取「视觉模型」配置、未配时回落通用模型
     * （与翻译模型同一约定）；回落的通用模型不支持图像输入时由服务商报错、按失败呈现。
     *
     * [onBubble] 无流式（数组元素级流式解析不划算）：全部译出后按序一次性回调。
     */
    suspend fun translatePageVision(
        bookId: Long,
        bookTitle: String,
        pageIndex: Int,
        lang: AiTargetLang,
        onBubble: (bubbleIndex: Int, text: String) -> Unit = { _, _ -> },
    ): Result<Int> {
        val provider = provider
            ?: return Result.failure(IllegalStateException("AI 服务未配置"))
        val prefs = preferences()
        // 视觉模型未配时回落通用模型（与翻译模型同一约定）：多模态通用模型一次配置全场景可用
        val model = prefs.aiModelVision.ifBlank { prefs.aiModelGeneral }
        if (model.isBlank()) {
            return Result.failure(IllegalStateException("未配置视觉模型（且通用模型也未配置）"))
        }
        val imageBase64 = pageImageBase64For(bookId, pageIndex)
            ?: return Result.failure(IllegalStateException("无法读取页面图像"))
        // 外发台账：图像外发——估算口径 = base64 字符 / 16 的粗略 token 量级
        contentGate.record(
            FEATURE_COMIC_VISION,
            "$bookTitle 第${pageIndex + 1}页（页图像）",
            imageBase64.length / 16,
        )
        val langKey = lang.name
        pageDao.upsert(
            ComicPageTranslationEntity(
                bookId = bookId,
                lang = langKey,
                pageIndex = pageIndex,
                status = ComicPageTranslationEntity.STATUS_TRANSLATING,
                model = model,
                bubbleCount = 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        val glossary = glossaryRepository.mergedConfirmed(bookId.toString(), seriesKeyFor(bookId))
        val messages = ComicVisionTranslatePrompt.buildMessages(imageBase64, lang, glossary)
        var lastError: Throwable = IllegalStateException("翻译失败")
        // 首次 + 整体重试 1 次：流异常或输出契约校验失败都算一次失败
        repeat(2) {
            try {
                val reply = StringBuilder()
                provider.chat(messages, model).collect { delta -> reply.append(delta) }
                val parsed = ComicVisionTranslatePrompt.parseBubbles(reply.toString())
                when {
                    parsed == null ->
                        lastError = IllegalStateException("视觉模型输出不符合契约（JSON 畸形或坐标越界）")
                    parsed.isEmpty() ->
                        lastError = IllegalStateException("视觉模型未在该页识别到文字气泡")
                    else -> {
                        val bubbles = parsed.mapIndexed { index, vb ->
                            OcrBubble(
                                index = index,
                                rect = vb.rect,
                                lines = listOf(OcrTextLine(vb.source, vb.rect, 1f)),
                                confidence = 1f,
                            )
                        }
                        val texts = parsed.map { it.translation }
                        // saveOcr 会连带作废同页微调（框重新生成，旧微调失去意义）
                        store.saveOcr(bookId, pageIndex, bubbles)
                        store.saveTranslation(bookId, langKey, pageIndex, texts)
                        texts.forEachIndexed { index, text -> onBubble(index, text) }
                        pageDao.updateStatus(
                            bookId = bookId,
                            lang = langKey,
                            pageIndex = pageIndex,
                            status = ComicPageTranslationEntity.STATUS_DONE,
                            model = model,
                            bubbleCount = texts.size,
                            updatedAt = System.currentTimeMillis(),
                        )
                        return Result.success(texts.size)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        pageDao.updateStatus(
            bookId = bookId,
            lang = langKey,
            pageIndex = pageIndex,
            status = ComicPageTranslationEntity.STATUS_FAILED,
            model = model,
            bubbleCount = 0,
            updatedAt = System.currentTimeMillis(),
        )
        return Result.failure(lastError)
    }

    /**
     * 跨页合并判定与落盘（R10）：只在**两页都未译**时做——已译页的译文按下标对气泡，
     * 合并会改动页 N+1 的气泡表（序号前移），动了就错位。无可配对片段原样返回。
     */
    private suspend fun maybeMergeCrossPage(
        bookId: Long,
        pageIndex: Int,
        langKey: String,
        page: List<OcrBubble>,
    ): List<OcrBubble> {
        if (page.none { it.rect.bottom > 1f - CrossPageMerge.EDGE_EPS }) return page
        if (store.loadTranslation(bookId, langKey, pageIndex) != null) return page
        if (store.loadTranslation(bookId, langKey, pageIndex + 1) != null) return page
        val next = bubblesFor(bookId, pageIndex + 1)
        if (next.isEmpty()) return page
        val plan = CrossPageMerge.plan(page, next) ?: return page
        store.saveOcr(bookId, pageIndex, plan.owner)
        store.saveOcr(bookId, pageIndex + 1, plan.next)
        return plan.owner
    }

    companion object {
        /** 外发台账的 feature 名（与内置提示词登记表一致）。 */
        const val FEATURE_COMIC_TRANSLATION = "漫画翻译"

        /** 视觉模式的外发台账 feature 名（图像外发，与文本链路分开记）。 */
        const val FEATURE_COMIC_VISION = "漫画视觉翻译"
    }
}
