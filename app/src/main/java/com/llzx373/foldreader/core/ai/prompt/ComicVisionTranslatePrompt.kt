package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiContent
import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.ocr.OcrRect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 视觉模型返回的一个气泡：归一化框 + 原文 + 译文。 */
data class VisionBubble(
    val rect: OcrRect,
    val source: String,
    val translation: String,
)

@Serializable
private data class VisionBubbleDto(
    val box: List<Float>,
    val source: String,
    val translation: String,
)

/**
 * M23「漫画视觉翻译」的提示词：页图像直接发给视觉模型，一次产出
 * 「气泡框 + 原文 + 译文」，跳过本地 RT-DETR/OCR 链路（可选模式，逐书明示确认后可用）。
 *
 * 输出契约：JSON 数组，元素 `{"box":[l,t,r,b],"source":...,"translation":...}`，
 * box 为 0..1 归一化坐标。解析校验（box 四元、界内、l<r/t<b、文非空）任一失败
 * 即整页重试——不做事后修补（同 M22 数量校验口径）。
 *
 * 纯 JVM、不碰 Android；图像以 base64 注入 USER 消息的图片块。
 */
object ComicVisionTranslatePrompt {

    /** 系统提示词中的目标语言占位符。 */
    const val TARGET_LANG_PLACEHOLDER = "{{目标语言}}"

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG，输出格式变了同步改解析与单测。 */
    val SYSTEM_PROMPT =
        """
        你是漫画翻译器。用户会给你一页漫画的图片。

        你的任务：找出图中所有含文字的气泡（对白、旁白与拟声词），识别原文并翻译成$TARGET_LANG_PLACEHOLDER。

        要求：
        1. 只输出如下 JSON 数组，不要输出任何其他文字、解释或 Markdown 代码围栏：
        [{"box": [左, 上, 右, 下], "source": "原文", "translation": "译文"}, ...]
        2. box 是气泡外接框的归一化坐标（0 到 1，相对图宽图高，左上为原点），四元顺序固定为左、上、右、下；
        3. 按漫画阅读顺序（从右上到左下）排列元素，不得遗漏含文字的气泡；没有文字的图输出 []；
        4. source 保留原文原样（可含换行）；translation 要放进漫画气泡里：保持原意、尽量简短口语化；
        5. 拟声词翻译为目标语言习惯的拟声词（无法翻译时给简短描述）；
        6. 人名、地名、专有名词按目标语言惯例翻译并全卷保持一致；
        7. 原文语言与目标语言相同时，translation 原样返回原文。
        """.trimIndent()

    /**
     * SYSTEM 给任务与输出契约，USER 带页图像（JPEG base64）+ 一句指令。
     *
     * [glossary] 非空时在 SYSTEM 末尾追加「术语对照」节（同 M22 口径）。
     */
    fun buildMessages(
        imageJpegBase64: String,
        targetLang: AiTargetLang,
        glossary: List<Pair<String, String>> = emptyList(),
    ): List<AiMessage> {
        val system = buildString {
            append(SYSTEM_PROMPT.replace(TARGET_LANG_PLACEHOLDER, SelectionTranslatePrompt.displayName(targetLang)))
            if (glossary.isNotEmpty()) {
                append("\n\n术语对照（翻译时严格采用以下译法）：\n")
                glossary.forEach { (source, target) -> append(source).append(" → ").append(target).append('\n') }
            }
        }
        return listOf(
            AiMessage.of(AiRole.SYSTEM, system),
            AiMessage(
                AiRole.USER,
                listOf(
                    AiContent.Image("image/jpeg", imageJpegBase64),
                    AiContent.Text("翻译这一页漫画里的所有文字气泡。"),
                ),
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析视觉模型输出的气泡数组。
     *
     * 容忍散文包裹与 ```json 代码围栏：截取第一个 `[` 到最后一个 `]` 再解析。
     * JSON 畸形、box 不是四元、坐标越界（0..1 外）或 l≥r / t≥b、source/translation 为空
     * → null（调用方据此重试该页）。空数组合法返回空表（该页无文字气泡）。
     */
    fun parseBubbles(raw: String): List<VisionBubble>? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end < start) return null

        val parsed = runCatching {
            json.decodeFromString<List<VisionBubbleDto>>(raw.substring(start, end + 1))
        }.getOrNull() ?: return null

        return parsed.map { dto ->
            if (dto.box.size != 4) return null
            val (l, t, r, b) = dto.box
            val inBounds = listOf(l, t, r, b).all { it in 0f..1f }
            if (!inBounds || l >= r || t >= b) return null
            if (dto.source.isBlank() || dto.translation.isBlank()) return null
            VisionBubble(rect = OcrRect(l, t, r, b), source = dto.source, translation = dto.translation)
        }
    }
}
