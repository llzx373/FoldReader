package com.llzx373.foldreader.core.tts

import com.llzx373.foldreader.core.ai.AiTargetLang
import java.util.Locale

/**
 * TTS 朗读语言（M26）的取值域与解析规则（纯 JVM）。
 *
 * 语言优先级：按书显式设置（`book_prefs.ttfLang`）> 译文视角跟随当前译本语言 > 默认简体中文。
 * 按书设置存 DB 可空列，null 即「跟随默认」。
 */
enum class TtsLanguage(val displayName: String, val languageTag: String) {
    ZH_CN("简体中文", "zh-CN"),
    ZH_TW("繁体中文", "zh-TW"),
    EN("英语", "en"),
    JA("日语", "ja"),
    KO("韩语", "ko"),
    FR("法语", "fr"),
    DE("德语", "de"),
    ES("西班牙语", "es"),
    ;

    fun toLocale(): Locale = Locale.forLanguageTag(languageTag)

    companion object {
        val DEFAULT = ZH_CN

        fun fromNameOrNull(name: String?): TtsLanguage? =
            name?.let { n -> entries.firstOrNull { it.name == n } }

        /** AI 翻译目标语言 → TTS 语言；无对应时 null。 */
        fun fromAiTarget(lang: AiTargetLang?): TtsLanguage? =
            when (lang) {
                AiTargetLang.ZH_HANS -> ZH_CN
                AiTargetLang.ZH_HANT -> ZH_TW
                AiTargetLang.EN -> EN
                AiTargetLang.JA -> JA
                AiTargetLang.KO -> KO
                AiTargetLang.FR -> FR
                AiTargetLang.DE -> DE
                AiTargetLang.ES -> ES
                null -> null
            }

        /**
         * 解析本次朗读用哪个语言。
         *
         * @param explicit 按书显式设置（book_prefs.ttfLang），null = 跟随默认
         * @param inTranslatedView 当前是否处于译文视角
         * @param translationTarget 译本的目标语言（译文视角下才有意义）
         */
        fun resolveTtsLanguage(
            explicit: TtsLanguage?,
            inTranslatedView: Boolean,
            translationTarget: AiTargetLang?,
        ): TtsLanguage =
            explicit
                ?: if (inTranslatedView) fromAiTarget(translationTarget) ?: DEFAULT else DEFAULT
    }
}
