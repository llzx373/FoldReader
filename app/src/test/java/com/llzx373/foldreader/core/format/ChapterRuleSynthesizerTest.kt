package com.llzx373.foldreader.core.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「选中行生成章节规则」合成器单测：行吸附（\n 边界 / CRLF / 全角空格 trim / 越界钳制）
 * 与候选正则合成（精确匹配 / 数字泛化 / 编号语境 / 元字符转义 / 超长行拒绝）。
 */
class ChapterRuleSynthesizerTest {

    // ---------- sourceLineAt ----------

    @Test
    fun `吸附行中间的偏移`() {
        val text = "abc\ndef\nghi"
        assertEquals("def", ChapterRuleSynthesizer.sourceLineAt(text, 5L))
    }

    @Test
    fun `行首偏移吸附到本行`() {
        val text = "abc\ndef\nghi"
        assertEquals("def", ChapterRuleSynthesizer.sourceLineAt(text, 4L))
    }

    @Test
    fun `落在换行符上的偏移属于前一行`() {
        val text = "abc\ndef"
        assertEquals("abc", ChapterRuleSynthesizer.sourceLineAt(text, 3L))
    }

    @Test
    fun `CRLF 的行尾回车被 trim 掉`() {
        val text = "ab\r\ncd"
        assertEquals("ab", ChapterRuleSynthesizer.sourceLineAt(text, 1L))
        assertEquals("cd", ChapterRuleSynthesizer.sourceLineAt(text, 4L))
    }

    @Test
    fun `行首尾的全角空格被 trim 掉`() {
        val text = "x\n　第 1 章　\ny"
        assertEquals("第 1 章", ChapterRuleSynthesizer.sourceLineAt(text, 4L))
    }

    @Test
    fun `空白行返回 null`() {
        val text = "abc\n\ndef"
        assertNull(ChapterRuleSynthesizer.sourceLineAt(text, 4L))
    }

    @Test
    fun `空文本返回 null`() {
        assertNull(ChapterRuleSynthesizer.sourceLineAt("", 0L))
    }

    @Test
    fun `越界偏移钳制到文本末尾`() {
        assertEquals("abc", ChapterRuleSynthesizer.sourceLineAt("abc", 1000L))
    }

    // ---------- synthesize ----------

    @Test
    fun `空白行产出空候选`() {
        assertTrue(ChapterRuleSynthesizer.synthesize("  　 ").isEmpty())
    }

    @Test
    fun `超过标题长度上限的行产出空候选`() {
        val longLine = "第1章 " + "很长的标题".repeat(8)
        assertTrue(longLine.trim().length > ChapterRules.MAX_TITLE_LENGTH)
        assertTrue(ChapterRuleSynthesizer.synthesize(longLine).isEmpty())
    }

    @Test
    fun `无数字行只有精确匹配候选`() {
        val candidates = ChapterRuleSynthesizer.synthesize("楔子")
        assertEquals(1, candidates.size)
        val rule = Regex(candidates[0])
        assertTrue(rule.matches("楔子"))
        assertTrue(!rule.matches("楔子二"))
    }

    @Test
    fun `含阿拉伯数字行产出泛化加精确两条候选`() {
        val candidates = ChapterRuleSynthesizer.synthesize("第123章 雨夜")
        assertEquals(2, candidates.size)
        val generalized = Regex(candidates[0])
        val exact = Regex(candidates[1])
        // 泛化：同格式不同编号都命中；样本有空白的位置兼容空白变体（无空白的位置不放宽）
        assertTrue(generalized.matches("第123章 雨夜"))
        assertTrue(generalized.matches("第125章 雨夜"))
        assertTrue(generalized.matches("第125章  雨夜"))
        assertTrue(!generalized.matches("第125回 雨夜"))
        // 精确：只命中编号一致的行（空白折叠仍生效）
        assertTrue(exact.matches("第123章 雨夜"))
        assertTrue(exact.matches("第123章  雨夜"))
        assertTrue(!exact.matches("第124章 雨夜"))
    }

    @Test
    fun `有编号语境的中文数字被泛化`() {
        val candidates = ChapterRuleSynthesizer.synthesize("第十二章 开始")
        assertEquals(2, candidates.size)
        val generalized = Regex(candidates[0])
        assertTrue(generalized.matches("第十三章 开始"))
        assertTrue(generalized.matches("第一百二十三章 开始"))
        assertTrue(!generalized.matches("第十二回 开始"))
    }

    @Test
    fun `无编号语境的中文数字保持字面量`() {
        val candidates = ChapterRuleSynthesizer.synthesize("两个陌生人的地方")
        assertEquals(1, candidates.size)
        assertTrue(Regex(candidates[0]).matches("两个陌生人的地方"))
    }

    @Test
    fun `全角数字同样泛化`() {
        val candidates = ChapterRuleSynthesizer.synthesize("第１２章")
        assertEquals(2, candidates.size)
        val generalized = Regex(candidates[0])
        assertTrue(generalized.matches("第３４章"))
        assertTrue(generalized.matches("第34章"))
    }

    @Test
    fun `正则元字符被转义不误配`() {
        val candidates = ChapterRuleSynthesizer.synthesize("1.5 倍速（完）")
        val exact = Regex(candidates.last())
        assertTrue(exact.matches("1.5 倍速（完）"))
        assertTrue(!exact.matches("1x5 倍速（完）"))
        // 泛化候选里的点号也必须是字面量
        val generalized = Regex(candidates[0])
        assertTrue(generalized.matches("2.5 倍速（完）"))
        assertTrue(!generalized.matches("2x5 倍速（完）"))
    }

    @Test
    fun `生成的候选都能编译且是整行匹配`() {
        val lines = listOf("第1章", "Chapter 2: Start", "楔子", "123、标题", "第 三 章")
        for (line in lines) {
            for (pattern in ChapterRuleSynthesizer.synthesize(line)) {
                val rule = Regex(pattern)
                assertTrue("$pattern 应命中样本行 $line", rule.matches(line))
                assertTrue("$pattern 不应命中带前后文的行", !rule.matches("前${line}后"))
            }
        }
    }
}
