package com.llzx373.foldreader.core.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流式 JSON 段落数组的增量提取：完整 / 半截 / 转义 / 粘包（SSE 增量任意切片拼接）。
 */
class PartialJsonArrayTest {

    @Test
    fun `完整输入提取全部元素`() {
        assertEquals(
            listOf("a", "b", "c"),
            PartialJsonArray.extractCompleteStrings("""{"paragraphs":["a","b","c"]}"""),
        )
    }

    @Test
    fun `末尾半截元素被忽略`() {
        assertEquals(
            listOf("a", "b"),
            PartialJsonArray.extractCompleteStrings("""{"paragraphs":["a","b","c...未闭合"""),
        )
        // 半截转义序列也算未闭合
        assertEquals(
            listOf("a"),
            PartialJsonArray.extractCompleteStrings("""["a","b\"""),
        )
        // 半截 \uXXXX 同样丢弃
        assertEquals(
            emptyList<String>(),
            PartialJsonArray.extractCompleteStrings("""["\u4f"""),
        )
    }

    @Test
    fun `转义序列正确解码`() {
        assertEquals(
            listOf("a\"b", "c\\d", "e\nf", "g\th", "你好"),
            PartialJsonArray.extractCompleteStrings("""["a\"b","c\\d","e\nf","g\th","你好"]"""),
        )
    }

    @Test
    fun `找不到数组起点返回空`() {
        assertEquals(emptyList<String>(), PartialJsonArray.extractCompleteStrings("""{"par"""))
        assertEquals(emptyList<String>(), PartialJsonArray.extractCompleteStrings(""))
    }

    @Test
    fun `粘包：SSE 增量任意切片逐次拼接只多出已闭合元素`() {
        val deltas = listOf("""{"par""", """agraphs":["hel""", """lo","世""", """界","th""", """ird","末""", """尾"]}""")
        val acc = StringBuilder()
        val seen = mutableListOf<List<String>>()
        for (delta in deltas) {
            acc.append(delta)
            seen += PartialJsonArray.extractCompleteStrings(acc.toString())
        }

        assertEquals(emptyList<String>(), seen[0])
        assertEquals(emptyList<String>(), seen[1])
        assertEquals(listOf("hello"), seen[2])
        assertEquals(listOf("hello", "世界"), seen[3])
        assertEquals(listOf("hello", "世界", "third"), seen[4])
        assertEquals(listOf("hello", "世界", "third", "末尾"), seen[5])
    }
}
