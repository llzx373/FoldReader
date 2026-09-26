package com.llzx373.foldreader.core.translate

import com.llzx373.foldreader.core.ocr.OcrBubble
import com.llzx373.foldreader.core.ocr.OcrRect

/** 一个气泡的覆盖层条目：[text] 为 null 表示已识别未译出（流式中途或该页未译）。 */
data class TranslatedBubble(
    val rect: OcrRect,
    val text: String?,
    /** 低置信（检测/识别把握不足）：覆盖层给边框标记，提醒人工核对。 */
    val lowConfidence: Boolean,
    /**
     * 抹除条目（R10 跨页合并）：只铺底色不写字——上一页被切气泡的续段在本页顶部，
     * 文字已并入上一页气泡翻译，本页这里用底色把原文续段盖掉。
     */
    val erased: Boolean = false,
)

/**
 * 一页的翻译覆盖层数据（视角①）：气泡框 + 译文，坐标为归一化页内坐标。
 *
 * 由 [of] 把 OCR 气泡与译文（完整或流式局部）合并而成；底色/排版在绘制端
 * 按页图片现算（见 `BubbleRender`），这里只带几何与文本。
 */
data class ComicPageTranslation(
    val bubbles: List<TranslatedBubble>,
) {
    /** 追加续段抹除条目（R10）：跟在真气泡之后，不占气泡序号语义。 */
    fun withErasedContinuations(rects: List<OcrRect>): ComicPageTranslation =
        if (rects.isEmpty()) {
            this
        } else {
            copy(
                bubbles = bubbles + rects.map { TranslatedBubble(rect = it, text = null, lowConfidence = false, erased = true) },
            )
        }

    companion object {
        /** 气泡置信度低于此值即标低置信边框。 */
        const val LOW_CONFIDENCE = 0.6f

        /**
         * 合并 OCR 气泡与译文数组。[texts] 下标 = 气泡序号（`OcrBubble.index`），
         * 允许短于气泡数（流式渲染中）；null = 全部未译。
         */
        fun of(bubbles: List<OcrBubble>, texts: List<String>?): ComicPageTranslation =
            ComicPageTranslation(
                bubbles.map { bubble ->
                    TranslatedBubble(
                        rect = bubble.rect,
                        text = texts?.getOrNull(bubble.index),
                        lowConfidence = bubble.confidence < LOW_CONFIDENCE,
                    )
                },
            )
    }
}
