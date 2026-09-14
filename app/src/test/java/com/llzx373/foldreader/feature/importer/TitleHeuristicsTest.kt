package com.llzx373.foldreader.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleHeuristicsTest {

    @Test
    fun `书名作者标注行优先`() {
        val head = "书名：雪中悍刀行\n作者：烽火戏诸侯\n第一章 白马出凉州\n正文……"
        val (title, author) = TitleHeuristics.infer("random-name.txt", head)

        assertEquals("雪中悍刀行", title)
        assertEquals("烽火戏诸侯", author)
    }

    @Test
    fun `全角冒号与空格变体`() {
        val head = "  书名:  斗破苍穹  \n作者：  天蚕土豆\n"
        val (title, author) = TitleHeuristics.infer(null, head)

        assertEquals("斗破苍穹", title)
        assertEquals("天蚕土豆", author)
    }

    @Test
    fun `书名号首行推断书名`() {
        val head = "《庆余年》\n正文从这里开始，没有作者标注。"
        val (title, author) = TitleHeuristics.infer(null, head)

        assertEquals("庆余年", title)
        assertNull(author)
    }

    @Test
    fun `头部无标注回落文件名`() {
        val head = "第一章 直接开始\n没有任何书名作者信息的正文。"
        val (title, author) = TitleHeuristics.infer("我的小说", head)

        assertEquals("我的小说", title)
        assertNull(author)
    }

    @Test
    fun `文件名也为空时兜底未知书名`() {
        val (title, _) = TitleHeuristics.infer(null, "正文内容。")
        assertEquals("未知书名", title)
    }

    @Test
    fun `长正文行中的书名号不误判`() {
        val longLine = "他在《那本书》里读到" + "很长的内容".repeat(30)
        val head = "$longLine\n正文继续。"
        val (title, _) = TitleHeuristics.infer("备用名", head)

        assertEquals("备用名", title)
    }
}
