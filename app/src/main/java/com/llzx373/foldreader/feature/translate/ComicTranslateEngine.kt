package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.ai.AiProvider
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ai.gate.AiContentGate
import com.llzx373.foldreader.core.ai.prompt.ComicTranslatePrompt
import com.llzx373.foldreader.core.ai.prompt.PartialJsonArray
import com.llzx373.foldreader.core.data.db.ComicPageTranslationDao
import com.llzx373.foldreader.core.data.db.ComicPageTranslationEntity
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.translate.ComicTranslationStore
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
        val bubbles = bubblesFor(bookId, pageIndex)
        if (bubbles.isEmpty()) {
            return Result.failure(IllegalArgumentException("该页未识别到文字气泡"))
        }
        val prefs = preferences()
        val model = prefs.aiModelTranslation.ifBlank { prefs.aiModelGeneral }
        val totalChars = bubbles.sumOf { it.text.length }
        // 外发台账：token 估算口径同其他功能——字符数 / 2 的保守量级估算
        contentGate.record(FEATURE_COMIC_TRANSLATION, "$bookTitle 第${pageIndex + 1}页", totalChars / 2)
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

    companion object {
        /** 外发台账的 feature 名（与内置提示词登记表一致）。 */
        const val FEATURE_COMIC_TRANSLATION = "漫画翻译"
    }
}
