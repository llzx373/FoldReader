package com.llzx373.foldreader.core.ai.prompt

import com.llzx373.foldreader.core.ai.AiMessage
import com.llzx373.foldreader.core.ai.AiRole
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanProfile
import com.llzx373.foldreader.core.format.clean.CleanToggles
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AI 返回的一份清洗配方建议。
 *
 * [toggles] 只需要**偏离标准档**的开关覆盖（key 取自 [CleanToggles.ENTRIES]）；
 * [adPatterns] 是 0~3 条自定义广告行正则；[explanation] 是问题说明。
 */
@Serializable
data class CleanRecipeSuggestion(
    val toggles: Map<String, Boolean> = emptyMap(),
    val adPatterns: List<String> = emptyList(),
    val explanation: String = "",
)

/**
 * M16「AI 清洗配方推荐」的提示词与响应解析。
 *
 * 纯 JVM、不碰 Android；模型输出契约由 [parseSuggestion] 的单测锁定。
 * 解析失败（JSON 畸形）返回 null；未知开关 key、编译不过的正则逐条丢弃，绝不抛异常。
 */
object CleanRecipePrompt {
    const val MAX_AD_PATTERNS = 3

    /**
     * 系统提示词全文（开关清单由 [CleanToggles.ENTRIES] 生成，两处共用一份真相）。
     * 改动即行为变更：进 CHANGELOG，输出格式变了同步改解析与单测。
     */
    val SYSTEM_PROMPT: String = buildString {
        val standard = CleanToggles.preset(CleanLevel.STANDARD)
        append(
            """
            你是电子书 TXT 清洗配方顾问。用户会给你一段从同一本书采样的原文（开头 / 中段 / 结尾三段拼接，头尾是站点广告与防盗声明的高发区），以及一份可选清洗开关清单。

            你的任务：判断这本书存在哪些排版与噪音问题，给出一份清洗配方建议。

            要求：
            1. toggles 只列需要**偏离标准档**的开关（标准档已默认开启的不必重复列出）；key 必须原样取自下方清单，不要自造；
            2. adPatterns 给 0~$MAX_AD_PATTERNS 条自定义广告/噪音行正则（Kotlin/Java Regex 语法），用于整行匹配并删除该行；宁缺毋滥，拿不准的模式不要给，以免误删正文；
            3. explanation 用中文一两句话说明这本书的问题与你的建议理由；
            4. 若样本干净、标准档即可，toggles 与 adPatterns 都可以为空，只在 explanation 里说明。

            只输出如下 JSON，不要输出任何其他文字、解释或 Markdown 代码围栏：
            {"toggles": {"开关key": true}, "adPatterns": ["..."], "explanation": "..."}

            可选开关清单（key：名称 — 说明（标准档默认开/关））：
            """.trimIndent(),
        )
        append('\n')
        CleanToggles.ENTRIES.forEach { entry ->
            val defaultOn = entry.get(standard)
            append("- ${entry.key}：${entry.label} — ${entry.hint}（标准档默认${if (defaultOn) "开" else "关"}）\n")
        }
    }

    /** SYSTEM 给任务、输出契约与开关清单，USER 原样附上采样文本。 */
    fun buildMessages(sampleText: String): List<AiMessage> = listOf(
        AiMessage.of(AiRole.SYSTEM, SYSTEM_PROMPT),
        AiMessage.of(AiRole.USER, "采样文本（开头 / 中段 / 结尾三段拼接）：\n$sampleText"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 解析模型输出的清洗配方建议。
     *
     * 容忍散文包裹与 ```json 代码围栏：截取第一个 `{` 到最后一个 `}` 再解析。
     * JSON 畸形 → null；未知开关 key 丢弃；regex 空白或编译失败 → 丢弃该条；
     * 广告正则截断到 [MAX_AD_PATTERNS]。
     */
    fun parseSuggestion(raw: String): CleanRecipeSuggestion? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end < start) return null

        val parsed = runCatching {
            json.decodeFromString<CleanRecipeSuggestion>(raw.substring(start, end + 1))
        }.getOrNull() ?: return null

        val knownKeys = CleanToggles.ENTRIES.map { it.key }.toSet()
        return parsed.copy(
            toggles = parsed.toggles.filterKeys { it in knownKeys },
            adPatterns = parsed.adPatterns
                .filter { it.isNotBlank() && runCatching { Regex(it) }.isSuccess }
                .take(MAX_AD_PATTERNS),
        )
    }

    /**
     * 把建议落成一份普通 [CleanProfile]：标准档预设为基线，逐项应用建议覆盖，
     * 档位落到 [CleanLevel.CUSTOM]；广告正则此处均已通过编译校验。
     *
     * 产物与设置页逐项手调的配方**同构**，后续预览/物化走现有清洗链路，无新执行路径。
     */
    fun buildProfile(suggestion: CleanRecipeSuggestion): CleanProfile {
        var toggles = CleanToggles.preset(CleanLevel.STANDARD)
        suggestion.toggles.forEach { (key, value) ->
            CleanToggles.ENTRIES.firstOrNull { it.key == key }?.let { toggles = it.set(toggles, value) }
        }
        return CleanProfile(
            level = CleanLevel.CUSTOM,
            toggles = toggles,
            adPatterns = suggestion.adPatterns.map { Regex(it) },
        )
    }
}
