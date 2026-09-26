package com.llzx373.foldreader.feature.translate

import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.data.db.ComicPageTranslationDao
import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrRect
import com.llzx373.foldreader.core.translate.BubbleAdjust
import com.llzx373.foldreader.core.translate.BubbleAdjustments
import com.llzx373.foldreader.core.translate.ComicPageTranslation
import com.llzx373.foldreader.core.translate.ComicTranslationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 一本书的漫画翻译门面（M22）：阅读器 ViewModel 只跟它打交道——
 * 覆盖层数据（OCR 气泡 + 译文合并）、单页翻译（流式逐气泡回调）、页状态流。
 *
 * 整卷批量不走这里：那是 [ComicTranslationQueue]（前台服务托底）。
 * 纯 JVM 可测；[engineFor] 返回 null（AI 未配置）时翻译入口直接失败返回，不触碰网络（v2.6）。
 */
class ComicTranslationController(
    private val bookId: Long,
    private val bookTitleFor: suspend () -> String,
    private val store: ComicTranslationStore,
    private val pageDao: ComicPageTranslationDao,
    /** 引擎按当前配置现装配（Provider 廉价）；返回 null = AI 未配置。 */
    private val engineFor: suspend () -> ComicTranslateEngine?,
) {

    /**
     * 一页的覆盖层数据：OCR 气泡与该语言译文都在且数量一致才有；
     * 任一侧缺失 / 损坏 / 数量不符返回 null（不画覆盖层）。
     * 跨页合并（R10）的续段抹除条目（上一页被切气泡在本页顶部的残段）一并附上。
     */
    suspend fun overlayFor(lang: AiTargetLang, pageIndex: Int): ComicPageTranslation? {
        val bubbles = adjustedBubblesFor(pageIndex) ?: return null
        if (bubbles.isEmpty()) return null
        val texts = store.loadTranslation(bookId, lang.name, pageIndex)?.texts ?: return null
        if (texts.size != bubbles.size) return null
        return ComicPageTranslation.of(bubbles, texts).withErasedContinuations(erasedContinuations(pageIndex))
    }

    /** 上一页被切气泡在本页顶部的续段矩形（R10）：渲染本页时铺底色抹掉原文续段。 */
    private suspend fun erasedContinuations(pageIndex: Int): List<OcrRect> {
        if (pageIndex <= 0) return emptyList()
        return store.loadOcr(bookId, pageIndex - 1).orEmpty().mapNotNull { it.continuation }
    }

    /** 对照面板（视角③）：气泡原文 + 译文（未译出为 null）；该页无 OCR 缓存返回 null。 */
    suspend fun bubblePairsFor(lang: AiTargetLang, pageIndex: Int): List<Pair<OcrBubble, String?>>? {
        val bubbles = adjustedBubblesFor(pageIndex) ?: return null
        if (bubbles.isEmpty()) return null
        val texts = store.loadTranslation(bookId, lang.name, pageIndex)?.texts
        return bubbles.map { it to texts?.getOrNull(it.index) }
    }

    /** 该页的气泡（流式渲染覆盖层时要先有框）：无缓存返回 null。未套用微调（翻译语义按原始框）。 */
    suspend fun bubblesFor(pageIndex: Int): List<OcrBubble>? = store.loadOcr(bookId, pageIndex)

    /** 套用过手动微调（M23）的气泡：覆盖层与对照面板一律走这里。 */
    suspend fun adjustedBubblesFor(pageIndex: Int): List<OcrBubble>? {
        val bubbles = store.loadOcr(bookId, pageIndex) ?: return null
        return BubbleAdjust.apply(bubbles, store.loadAdjustments(bookId, pageIndex))
    }

    /** 该页是否有手动微调（「恢复自动识别位置」按钮的显隐判据）。 */
    suspend fun hasAdjustments(pageIndex: Int): Boolean =
        store.loadAdjustments(bookId, pageIndex)?.rects?.isNotEmpty() == true

    /** 写入一个气泡的微调矩形（与既有微调合并后整体落盘）。 */
    suspend fun saveAdjustment(pageIndex: Int, bubbleIndex: Int, rect: OcrRect) {
        val current = store.loadAdjustments(bookId, pageIndex) ?: BubbleAdjustments()
        store.saveAdjustments(
            bookId,
            pageIndex,
            current.copy(rects = current.rects + (bubbleIndex to rect)),
        )
    }

    /** 作废该页全部微调（恢复自动识别位置）。 */
    suspend fun resetAdjustments(pageIndex: Int) {
        store.deleteAdjustments(bookId, pageIndex)
    }

    /**
     * 翻译一页（台账 + 落盘由引擎负责）。[onBubble] 每闭合一个气泡译文回调
     * （气泡序号, 译文）——流式渲染用；重试时已回调的局部结果作废，调用方按页重置。
     */
    suspend fun translatePage(
        pageIndex: Int,
        lang: AiTargetLang,
        systemOverride: String? = null,
        onBubble: (bubbleIndex: Int, text: String) -> Unit = { _, _ -> },
    ): Result<Int> {
        val engine = engineFor() ?: return Result.failure(IllegalStateException("AI 服务未配置"))
        return engine.translatePage(
            bookId = bookId,
            bookTitle = bookTitleFor(),
            pageIndex = pageIndex,
            lang = lang,
            systemOverride = systemOverride,
            onBubble = onBubble,
        )
    }

    /** 重译单页：作废旧译文与台账后再翻（OCR 缓存保留，不重跑识别）。 */
    suspend fun retranslatePage(
        pageIndex: Int,
        lang: AiTargetLang,
        systemOverride: String? = null,
        onBubble: (bubbleIndex: Int, text: String) -> Unit = { _, _ -> },
    ): Result<Int> {
        store.deleteTranslation(bookId, lang.name, pageIndex)
        pageDao.deletePage(bookId, lang.name, pageIndex)
        return translatePage(pageIndex, lang, systemOverride, onBubble)
    }

    /**
     * 视觉模式翻译一页（M23）：页图像外发给视觉模型，气泡框 + 译文一次产出并落盘
     * （覆盖层/微调照常可用）。[onBubble] 在全部译出后按序一次性回调（无流式）。
     */
    suspend fun translatePageVision(
        pageIndex: Int,
        lang: AiTargetLang,
        onBubble: (bubbleIndex: Int, text: String) -> Unit = { _, _ -> },
    ): Result<Int> {
        val engine = engineFor() ?: return Result.failure(IllegalStateException("AI 服务未配置"))
        return engine.translatePageVision(
            bookId = bookId,
            bookTitle = bookTitleFor(),
            pageIndex = pageIndex,
            lang = lang,
            onBubble = onBubble,
        )
    }

    /** 页状态流（pageIndex → status）：菜单「已译 x/y」与页状态标记用。 */
    fun observePageStatus(lang: AiTargetLang): Flow<Map<Int, String>> =
        pageDao.observeForBook(bookId, lang.name).map { list ->
            list.associate { it.pageIndex to it.status }
        }

    /** 该书该语言已译页数（菜单进度展示）。 */
    suspend fun translatedPages(lang: AiTargetLang): Int = store.translatedPages(bookId, lang.name)

    /** 该书该语言是否已有可展示的译文（视角切换/对照面板的可用判据）。 */
    suspend fun hasAnyTranslation(lang: AiTargetLang): Boolean = store.hasAny(bookId, lang.name)
}
