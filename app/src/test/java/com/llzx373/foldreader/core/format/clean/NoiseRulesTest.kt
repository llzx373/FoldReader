package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NoiseRulesTest {

    private fun noise(line: String, inlineExcise: Boolean = false): String? =
        NoiseRules.isNoiseLine(line, emptyList(), inlineExcise)

    @Test
    fun `整行网址删除`() {
        assertNotNull(noise("www.example-novel.com"))
        assertNotNull(noise("https://example.com/chapter/1"))
    }

    @Test
    fun `整行站点推广删除`() {
        assertNotNull(noise("请记住本站域名"))
        assertNotNull(noise("最新章节请访问书友群"))
        assertNotNull(noise("无弹窗全文字免费阅读"))
    }

    @Test
    fun `防盗尾标行删除`() {
        assertNotNull(noise("（本章未完，请点击下一页）"))
        assertNotNull(noise("未完待续"))
    }

    @Test
    fun `字数统计行删除`() {
        assertNotNull(noise("本章共3245字"))
        assertNotNull(noise("3245字"))
    }

    @Test
    fun `作者的话与求票行删除`() {
        assertNotNull(noise("作者的话：今天两更"))
        assertNotNull(noise("PS：求推荐票和月票"))
        assertNotNull(noise("求各位读者投推荐票支持一下"))
    }

    @Test
    fun `论坛残留删除`() {
        assertNotNull(noise("3楼: 同感，追了好久了"))
        assertNotNull(noise("UID: 123456"))
        assertNotNull(noise("发表于 2020-01-01 12:30"))
        assertNotNull(noise("引用: 楼主说得对"))
    }

    @Test
    fun `装饰线删除`() {
        assertNotNull(noise("-----------------------------"))
        assertNotNull(noise("☆★☆★☆★☆"))
        assertNotNull(noise("=============="))
    }

    @Test
    fun `省略号不会被当成装饰线`() {
        assertNull(noise("......"))
        assertNull(noise("他站在原地......"))
    }

    @Test
    fun `正常正文不误判`() {
        assertNull(noise("他停下脚步，看着远方。"))
        assertNull(noise("他把那枚邮票小心地收藏起来。"))
        assertNull(noise("这一章的剧情很精彩。"))
    }

    @Test
    fun `自定义正则命中即删整行`() {
        val patterns = listOf(Regex("公众号"))
        assertNotNull(NoiseRules.isNoiseLine("请关注公众号xx", patterns))
    }

    @Test
    fun `开启行内切除时整行网址规则让位`() {
        val line = "他抬头望向窗外，夜色正浓。www.example-novel.com"
        assertNotNull(noise(line, inlineExcise = false))
        assertNull(noise(line, inlineExcise = true))
    }

    @Test
    fun `行内网址切除保留正文`() {
        assertEquals(
            "他抬头望向窗外，夜色正浓。",
            NoiseRules.exciseInline("他抬头望向窗外，夜色正浓。www.example-novel.com"),
        )
    }

    @Test
    fun `行内切除后收敛重复标点`() {
        assertEquals(
            "他停下脚步，继续走。",
            NoiseRules.exciseInline("他停下脚步，，继续走。http://a.com"),
        )
    }

    @Test
    fun `遮蔽符号规整为一对全角星号`() {
        assertEquals("你这个＊＊，真是＊＊！", NoiseRules.normalizeMaskRuns("你这个***，真是****！"))
        assertEquals("你这个＊＊。", NoiseRules.normalizeMaskRuns("你这个＊＊。"))
    }

    @Test
    fun `单个星号不动`() {
        assertEquals("a*b", NoiseRules.normalizeMaskRuns("a*b"))
    }
}
