package com.llzx373.foldreader.core.tts

import com.llzx373.foldreader.core.ai.AiTargetLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsLanguageTest {

    @Test
    fun `resolveTtsLanguage - 按书显式设置优先于译文视角与默认`() {
        assertEquals(
            TtsLanguage.JA,
            TtsLanguage.resolveTtsLanguage(
                explicit = TtsLanguage.JA,
                inTranslatedView = true,
                translationTarget = AiTargetLang.EN,
            ),
        )
        assertEquals(
            TtsLanguage.ZH_TW,
            TtsLanguage.resolveTtsLanguage(
                explicit = TtsLanguage.ZH_TW,
                inTranslatedView = false,
                translationTarget = null,
            ),
        )
    }

    @Test
    fun `resolveTtsLanguage - 无显式设置时译文视角跟随译本语言`() {
        assertEquals(
            TtsLanguage.EN,
            TtsLanguage.resolveTtsLanguage(
                explicit = null,
                inTranslatedView = true,
                translationTarget = AiTargetLang.EN,
            ),
        )
        assertEquals(
            TtsLanguage.ZH_TW,
            TtsLanguage.resolveTtsLanguage(
                explicit = null,
                inTranslatedView = true,
                translationTarget = AiTargetLang.ZH_HANT,
            ),
        )
    }

    @Test
    fun `resolveTtsLanguage - 原文视角或译本语言缺失时回落默认中文`() {
        assertEquals(
            TtsLanguage.DEFAULT,
            TtsLanguage.resolveTtsLanguage(
                explicit = null,
                inTranslatedView = false,
                translationTarget = AiTargetLang.JA,
            ),
        )
        assertEquals(
            TtsLanguage.DEFAULT,
            TtsLanguage.resolveTtsLanguage(
                explicit = null,
                inTranslatedView = true,
                translationTarget = null,
            ),
        )
    }

    @Test
    fun `fromNameOrNull - 合法枚举名还原，非法与 null 归 null`() {
        assertEquals(TtsLanguage.ZH_CN, TtsLanguage.fromNameOrNull("ZH_CN"))
        assertNull(TtsLanguage.fromNameOrNull("FR"))
        assertNull(TtsLanguage.fromNameOrNull(null))
    }

    @Test
    fun `locale 标签与展示名一一对应`() {
        assertEquals("zh-CN", TtsLanguage.ZH_CN.languageTag)
        assertEquals("zh-TW", TtsLanguage.ZH_TW.languageTag)
        assertEquals("en", TtsLanguage.EN.languageTag)
        assertEquals("ja", TtsLanguage.JA.languageTag)
        assertEquals("简体中文", TtsLanguage.ZH_CN.displayName)
    }
}
