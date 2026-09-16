package com.llzx373.foldreader.core.data.db

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaV10Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/10.json").readText()
    }

    @Test
    fun `v10 schema 导出文件存在且版本为 10`() {
        assertTrue(schema.contains("\"version\": 10"))
    }

    @Test
    fun `books 新增 EPUB 扩展元数据列且均可空`() {
        val start = schema.indexOf("\"tableName\": \"books\"")
        require(start >= 0) { "10.json 中缺少表 books" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        val block = schema.substring(start, if (next >= 0) next else schema.length)
        val newColumns = listOf(
            "description", "publisher", "language", "pubDate",
            "subjects", "identifier", "seriesName", "seriesIndex", "coverPath",
        )
        for (column in newColumns) {
            val idx = block.indexOf("\"fieldPath\": \"$column\"")
            require(idx >= 0) { "books 表缺少列 $column" }
            // Room schema：可空字段省略 notNull 键，非空字段写 "notNull": true
            val fieldBlock = block.substring(idx, idx + 200)
            assertTrue("列 $column 应可空", !fieldBlock.contains("\"notNull\": true"))
        }
    }
}
