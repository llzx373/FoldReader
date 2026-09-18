package com.llzx373.foldreader.core.format.clean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharRulesTest {

    @Test
    fun `不可见字符被清除`() {
        val input = "他\u200B停下\uFEFF脚步\u00AD，\u2060然后\u200C继续\u200D走。"
        assertEquals("他停下脚步，然后继续走。", CharRules.normalize(input))
    }

    @Test
    fun `全角空格与制表符归一为半角空格`() {
        assertEquals("他 停 下", CharRules.normalize("他\u3000停\t下"))
    }

    @Test
    fun `全角数字与字母转半角`() {
        assertEquals("第12章 ABC", CharRules.normalize("第１２章 ＡＢＣ"))
    }

    @Test
    fun `中文标点不被半角化`() {
        val input = "他停下脚步，然后说：“好。”"
        assertEquals(input, CharRules.normalize(input))
    }

    @Test
    fun `HTML 实体解码含命名与数字形式`() {
        assertEquals(" &。 ", CharRules.normalize("&nbsp;&amp;&#12290;&#x3000;"))
    }

    @Test
    fun `认不出的实体原样保留`() {
        assertEquals("&unknownname123;", CharRules.normalize("&unknownname123;"))
    }

    @Test
    fun `HTML 标签剥离`() {
        assertEquals("ab", CharRules.normalize("<p>a<br/>b</p>"))
        assertEquals("文本", CharRules.normalize("<!-- 注释 -->文本"))
    }

    @Test
    fun `正文里的尖括号不算标签`() {
        val input = "当 a<b 且 1<2 时成立"
        assertEquals(input, CharRules.normalize(input))
    }

    @Test
    fun `UBB 标签剥离`() {
        assertEquals("粗红", CharRules.normalize("[b]粗[/b][color=red]红[/color]"))
    }

    @Test
    fun `控制字符被清除`() {
        assertEquals("ab", CharRules.normalize("a\u0000\u0007\u001Fb"))
    }

    @Test
    fun `isCjkLike 覆盖汉字中文标点与中英引号`() {
        assertTrue(CharRules.isCjkLike('他'))
        assertTrue(CharRules.isCjkLike('，'))
        assertTrue(CharRules.isCjkLike('“'))
        assertTrue(CharRules.isCjkLike('…'))
        assertFalse(CharRules.isCjkLike('a'))
        assertFalse(CharRules.isCjkLike('1'))
    }
}
