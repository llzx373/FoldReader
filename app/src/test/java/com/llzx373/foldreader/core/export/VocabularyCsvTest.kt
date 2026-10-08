package com.llzx373.foldreader.core.export

import com.llzx373.foldreader.core.data.db.WordEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabularyCsvTest {

    private fun entry(
        word: String,
        definition: String,
        context: String,
    ) = WordEntryEntity(
        id = 1,
        bookId = 7,
        word = word,
        definition = definition,
        contextSentence = context,
        charOffset = 0,
        source = "牛津",
        createdAt = 1,
    )

    @Test
    fun `表头与行序——Anki 三列`() {
        val csv = VocabularyCsv.render(
            listOf(entry("apple", "n. 苹果", "an apple a day")),
        )
        val lines = csv.lines()
        assertEquals(VocabularyCsv.HEADER, lines[0])
        assertEquals("apple,n. 苹果,an apple a day", lines[1])
    }

    @Test
    fun `逗号引号换行按 RFC4180 转义`() {
        val csv = VocabularyCsv.render(
            listOf(entry("a,b", "say \"hi\"\n第二行", "plain")),
        )
        // 释义含换行，整个字段被引号包住，物理行变多——只校验转义形态
        assertTrue(csv.contains("\"a,b\""))
        assertTrue(csv.contains("\"say \"\"hi\"\"\\n第二行\"".replace("\\n", "\n")))
        assertEquals(3, csv.trimEnd('\r', '\n').lines().size)
    }

    @Test
    fun `空表只有表头`() {
        assertEquals(VocabularyCsv.HEADER + "\r\n", VocabularyCsv.render(emptyList()))
    }

    @Test
    fun `文件名带时间戳`() {
        assertTrue(VocabularyCsv.fileName(0L).matches(Regex("生词本-\\d{8}-\\d{6}\\.csv")))
    }
}
