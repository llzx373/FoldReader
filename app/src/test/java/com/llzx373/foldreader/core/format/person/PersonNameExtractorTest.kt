package com.llzx373.foldreader.core.format.person

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonNameExtractorTest {

    private fun extract(text: String): List<PersonMention> {
        val extractor = PersonNameExtractor()
        extractor.feed(text, 0)
        return extractor.result()
    }

    @Test
    fun `人名重复三次以上被收录且首出场偏移与计数正确`() {
        val text = "叶修走进网吧。苏沐橙正在吧台后忙碌,叶修打了个招呼。苏沐橙抬起头,韩文清推门而入。" +
            "叶修回头看了一眼,苏沐橙笑了笑。韩文清点了杯咖啡,韩文清又看向叶修。"
        val result = extract(text)

        assertEquals(listOf("叶修", "苏沐橙", "韩文清"), result.map { it.name })
        assertEquals(4, result[0].count)
        assertEquals(0L, result[0].firstOffset)
        assertEquals(3, result[1].count)
        assertEquals(text.indexOf("苏沐橙").toLong(), result[1].firstOffset)
        assertEquals(3, result[2].count)
        assertEquals(text.indexOf("韩文清").toLong(), result[2].firstOffset)
    }

    @Test
    fun `结果按出场次数降序排列`() {
        val text = "唐三睁开眼,唐三站起身,唐三望向窗外,唐三握紧拳头,唐三走出房门。" +
            "叶修看着这一切,叶修没有说话,叶修转身离开,叶修的身影消失了。" +
            "韩文清点点头,韩文清又摇摇头,韩文清转身走了。"
        val result = extract(text)

        assertEquals(listOf("唐三", "叶修", "韩文清"), result.map { it.name })
        assertEquals(listOf(5, 4, 3), result.map { it.count })
    }

    @Test
    fun `虚词与称谓反复出现也不会误判为人名`() {
        val paragraph = "我们不知道他们在做什么。老师说什么都没有,先生也说没关系。" +
            "他们点点头,我们转身就走。大家都笑了。"
        val text = paragraph.repeat(4) +
            "王子拔出宝剑。王子骑上白马。王子冲向恶龙。王子救出公主。"
        val result = extract(text)

        assertTrue("误识别出人名: ${result.map { it.name }}", result.isEmpty())
        listOf("我们", "他们", "什么", "大家", "老师", "先生", "王子").forEach { word ->
            assertFalse(word, result.any { it.name == word })
        }
    }

    @Test
    fun `出场次数不足阈值不收录`() {
        val result = extract("叶修登场。叶修离场。这里再无别人。")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `跨章喂入累积计数且偏移叠加 baseOffset`() {
        val ch1 = "叶修登场,叶修笑了。"
        val ch2 = "韩文清出场。叶修与韩文清对视,韩文清点头。"
        val extractor = PersonNameExtractor()
        extractor.feed(ch1, 0)
        extractor.feed(ch2, ch1.length.toLong())
        val result = extractor.result()

        assertEquals(2, result.size)
        val yeXiu = result.first { it.name == "叶修" }
        val hanWenQing = result.first { it.name == "韩文清" }
        assertEquals(3, yeXiu.count)
        assertEquals(0L, yeXiu.firstOffset)
        assertEquals(3, hanWenQing.count)
        assertEquals((ch1.length + ch2.indexOf("韩文清")).toLong(), hanWenQing.firstOffset)
    }

    @Test
    fun `称谓引导可收录姓氏称呼且称谓本身不入名`() {
        val text = "王警官来了。王警官查看现场。王警官摇了摇头。" +
            "李老师走进教室。李老师放下课本。李老师开始讲课。"
        val result = extract(text)

        val wang = result.first { it.name == "王" }
        assertEquals(0L, wang.firstOffset)
        assertEquals(3, wang.count)
        val li = result.first { it.name == "李" }
        assertEquals(3, li.count)
        // 称谓本身及「姓名+称谓」整体都不入名
        assertFalse(result.any { it.name.contains("警官") || it.name.contains("老师") })
    }

    @Test
    fun `长名优先归并前缀短名`() {
        val text = "苏沐橙笑了。苏沐橙怒了。苏沐橙哭了。沐橙姐来了。沐橙姐走了。沐橙姐又回来了。"
        val result = extract(text)

        assertEquals(listOf("苏沐橙", "沐橙"), result.map { it.name })
        // 「苏沐」恒为「苏沐橙」前缀,被归并不再单独计
        assertFalse(result.any { it.name == "苏沐" })
        assertEquals(3, result[0].count)
        assertEquals(3, result[1].count)
    }

    @Test
    fun `复姓可识别`() {
        val result = extract("欧阳修提笔。欧阳修落笔。欧阳修搁笔。")

        assertEquals(listOf("欧阳修"), result.map { it.name })
        assertEquals(3, result[0].count)
    }
}
