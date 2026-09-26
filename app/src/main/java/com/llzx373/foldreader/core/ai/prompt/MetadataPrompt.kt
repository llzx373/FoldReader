package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.metadata.GenreTags
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AI 返回的一份元数据补全建议。字段均可缺省：模型认不出来的就别说（宁缺毋滥）。
 * [genreTag] 必须命中 [GenreTags] 固定枚举；[synopsis] 是一两句话的简介。
 */
@Serializable
data class MetadataSuggestion(
    val author: String? = null,
    val synopsis: String? = null,
    val genreTag: String? = null,
)

/**
 * M17「AI 元数据补全」的提示词与响应解析。
 *
 * 纯 JVM、不碰 Android；模型输出契约由 [parseSuggestion] 的单测锁定。
 * 解析失败（JSON 畸形）返回 null；空白字段归一为 null、未知题材标签丢弃、
 * 简介截断到 [MAX_SYNOPSIS_CHARS]，绝不抛异常。
 *
 * 书名不在输出契约里：书架标题以导入文件名为准，AI 改书名风险大于收益（见 TODO M17 验收记录）。
 */
object MetadataPrompt {
    const val MAX_SYNOPSIS_CHARS = 140

    /** 系统提示词全文。改动即行为变更：进 CHANGELOG，输出格式变了同步改解析与单测。 */
    val SYSTEM_PROMPT: String = buildString {
        append(
            """
            你是电子书元数据整理助手。用户会给你一本书的文件名，以及从该书开头采样的一段文本。

            你的任务：判断这本书的作者、一句话简介与题材标签。

            要求：
            1. author：作者名。采样文本或文件名里找不到可靠依据时输出 null，不要猜；
            2. synopsis：一两句话的中文简介（不超过 $MAX_SYNOPSIS_CHARS 字），根据开头内容概括；拿不准可输出 null；
            3. genreTag：题材标签，必须原样取自下方固定清单，选最接近的一个；清单里都不合适时输出 null；
            4. 不要输出书名——书名以用户的文件名为准。

            只输出如下 JSON，不要输出任何其他文字、解释或 Markdown 代码围栏：
            {"author": "...", "synopsis": "...", "genreTag": "..."}

            题材标签清单（只能从中选）：
            """.trimIndent(),
        )
        append('\n')
        append(GenreTags.ALL.joinToString("、"))
    }

    /** SYSTEM 给任务、输出契约与题材清单，USER 附上文件名提示与开头采样文本。 */
    fun buildMessages(headSample: String, titleHint: String): List<AiMessage> = listOf(
        AiMessage.of(AiRole.SYSTEM, SYSTEM_PROMPT),
        AiMessage.of(AiRole.USER, "文件名：$titleHint\n\n开头采样文本：\n$headSample"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析模型输出的元数据建议。
     *
     * 容忍散文包裹与 ```json 代码围栏：截取第一个 `{` 到最后一个 `}` 再解析。
     * JSON 畸形 → null；空白字段归一为 null；[MetadataSuggestion.genreTag] 经
     * [GenreTags.normalize] 归一（未知标签丢弃）；简介截断到 [MAX_SYNOPSIS_CHARS]。
     */
    fun parseSuggestion(raw: String): MetadataSuggestion? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return null

        val parsed = runCatching {
            json.decodeFromString<MetadataSuggestion>(raw.substring(start, end + 1))
        }.getOrNull() ?: return null

        return parsed.copy(
            author = parsed.author?.trim()?.takeIf { it.isNotEmpty() },
            synopsis = parsed.synopsis?.trim()?.takeIf { it.isNotEmpty() }
                ?.take(MAX_SYNOPSIS_CHARS),
            genreTag = GenreTags.normalize(parsed.genreTag),
        )
    }
}
