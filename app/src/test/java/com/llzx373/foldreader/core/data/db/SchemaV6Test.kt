package com.llzx373.foldreader.core.data.db

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaV6Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/6.json").readText()
    }

    private fun entityBlock(tableName: String): String {
        val start = schema.indexOf("\"tableName\": \"$tableName\"")
        require(start >= 0) { "6.json 中缺少表 $tableName" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        return schema.substring(start, if (next >= 0) next else schema.length)
    }

    @Test
    fun `v6 schema 导出文件存在且版本为 6`() {
        assertTrue(schema.contains("\"version\": 6"))
    }

    @Test
    fun `annotations 新增 style 列且默认 highlight`() {
        val block = entityBlock("annotations")
        assertTrue(block.contains("\"columnName\": \"style\""))
        assertTrue(block.contains("\"defaultValue\": \"'highlight'\""))
        assertTrue(block.contains("`style` TEXT NOT NULL DEFAULT 'highlight'"))
        val ann = AnnotationEntity(
            bookId = 1,
            startCharOffset = 0,
            endCharOffset = 5,
            selectedText = "",
            color = 0,
            note = null,
            createdAt = 0,
            updatedAt = 0,
        )
        assertEquals(AnnotationEntity.STYLE_HIGHLIGHT, ann.style)
    }

    @Test
    fun `其余表结构沿用 v5`() {
        assertTrue(entityBlock("books").contains("\"columnName\": \"groupName\""))
        assertTrue(entityBlock("reading_progress").contains("\"columnName\": \"charsReadTotal\""))
        assertTrue(entityBlock("annotations").contains("\"onDelete\": \"NO ACTION\""))
        assertTrue(entityBlock("chapters").contains("\"onDelete\": \"CASCADE\""))
    }
}
