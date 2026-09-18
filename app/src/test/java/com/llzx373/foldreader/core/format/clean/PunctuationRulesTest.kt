package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuationRulesTest {

    @Test
    fun `省略号各种写法统一为两个省略号字符`() {
        assertEquals("他站在原地……", PunctuationRules.normalizePre("他站在原地......"))
        assertEquals("他站在原地……", PunctuationRules.normalizePre("他站在原地。。。"))
        assertEquals("他站在原地……", PunctuationRules.normalizePre("他站在原地…"))
        assertEquals("他站在原地……", PunctuationRules.normalizePre("他站在原地．．．"))
    }

    @Test
    fun `破折号写法统一`() {
        assertEquals("他说——好", PunctuationRules.normalizePre("他说--好"))
        assertEquals("他说——好", PunctuationRules.normalizePre("他说————好"))
    }

    @Test
    fun `数字区间的连字符不动`() {
        val input = "2020-2021年"
        assertEquals(input, PunctuationRules.normalizePre(input))
    }

    @Test
    fun `引号内的空格被删除`() {
        assertEquals("他说：“你来了。”", PunctuationRules.normalizeQuotes("他说：“ 你 来了。 ”"))
    }

    @Test
    fun `引号内侧紧贴的空格被删除`() {
        assertEquals("“你好”", PunctuationRules.normalizeQuotes("“ 你好 ”"))
    }

    @Test
    fun `引号外的空格保留`() {
        val input = "他说 “hello world” 然后离开"
        assertEquals(input, PunctuationRules.normalizeQuotes(input))
    }

    @Test
    fun `拉丁词句在引号内的空格保留`() {
        val input = "他说：“I love this city.”"
        assertEquals(input, PunctuationRules.normalizeQuotes(input))
    }

    @Test
    fun `日式引号同样处理`() {
        assertEquals("「你好」", PunctuationRules.normalizeQuotes("「 你好 」"))
    }

    @Test
    fun `重复标点折叠但保留省略号与破折号`() {
        assertEquals("他来了！", PunctuationRules.collapseRepeated("他来了！！！"))
        assertEquals("他停下脚步，看着远方。", PunctuationRules.collapseRepeated("他停下脚步，，看着远方。。"))
        assertEquals("他站在原地……", PunctuationRules.collapseRepeated("他站在原地……"))
        assertEquals("他说——好", PunctuationRules.collapseRepeated("他说——好"))
    }

    @Test
    fun `重复标点折叠在省略号归位之后跑`() {
        // 先 `......` → `……`，再折叠：`……` 必须活着出来
        val normalized = PunctuationRules.normalizePre("他站在原地......")
        assertEquals("他站在原地……", PunctuationRules.collapseRepeated(normalized))
    }

    @Test
    fun `省略号规整幂等`() {
        val once = PunctuationRules.normalizePre("他站在原地......")
        assertEquals(once, PunctuationRules.normalizePre(once))
    }
}
