package com.llzx373.foldreader.core.ocr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OCR 产物的落盘编解码（M21/M22 共用）。
 *
 * 扫描 PDF 文本层（`filesDir/pdf_ocr/`）与漫画气泡缓存（`filesDir/comic_translate/`）
 * 落的是同一套结构；版本字段用于管线变更时整体失效（不跨版本读旧缓存）。
 */
object OcrPageCodec {

    /** 管线版本：det/rec 后处理或字段结构变更时 +1，旧缓存整体作废。 */
    const val OCR_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RectDto(val left: Float, val top: Float, val right: Float, val bottom: Float)

    @Serializable
    private data class LineDto(val text: String, val box: RectDto, val confidence: Float)

    @Serializable
    private data class PageDto(val version: Int, val lines: List<LineDto>)

    @Serializable
    private data class BubbleDto(
        val index: Int,
        val rect: RectDto,
        val lines: List<LineDto>,
        val confidence: Float,
        /** 跨页合并（R10）：下一页顶部续段矩形；可选字段，旧缓存无此字段按 null 读。 */
        val continuation: RectDto? = null,
    )

    @Serializable
    private data class BubblesDto(val version: Int, val bubbles: List<BubbleDto>)

    fun encodePage(page: OcrPage): String =
        json.encodeToString(PageDto(OCR_VERSION, page.lines.map { it.toDto() }))

    /** 解码失败或版本不符返回 null（调用方视为未缓存，重新识别）。 */
    fun decodePage(text: String): OcrPage? = runCatching {
        val dto = json.decodeFromString<PageDto>(text)
        if (dto.version != OCR_VERSION) return null
        OcrPage(dto.lines.map { it.toLine() })
    }.getOrNull()

    fun encodeBubbles(bubbles: List<OcrBubble>): String =
        json.encodeToString(BubblesDto(OCR_VERSION, bubbles.map { it.toDto() }))

    fun decodeBubbles(text: String): List<OcrBubble>? = runCatching {
        val dto = json.decodeFromString<BubblesDto>(text)
        if (dto.version != OCR_VERSION) return null
        dto.bubbles.map { b ->
            OcrBubble(b.index, b.rect.toRect(), b.lines.map { it.toLine() }, b.confidence, b.continuation?.toRect())
        }
    }.getOrNull()

    private fun OcrRect.toDto() = RectDto(left, top, right, bottom)
    private fun RectDto.toRect() = OcrRect(left, top, right, bottom)
    private fun OcrTextLine.toDto() = LineDto(text, box.toDto(), confidence)
    private fun LineDto.toLine() = OcrTextLine(text, box.toRect(), confidence)
    private fun OcrBubble.toDto() =
        BubbleDto(index, rect.toDto(), lines.map { it.toDto() }, confidence, continuation?.toDto())
}
