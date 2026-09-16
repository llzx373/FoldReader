package com.llzx373.foldreader.core.data.db

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaV9Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/9.json").readText()
    }

    @Test
    fun `v9 schema 导出文件存在且版本为 9`() {
        assertTrue(schema.contains("\"version\": 9"))
    }

    @Test
    fun `books 新增 source 列且默认 IMPORT`() {
        val start = schema.indexOf("\"tableName\": \"books\"")
        require(start >= 0) { "9.json 中缺少表 books" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        val block = schema.substring(start, if (next >= 0) next else schema.length)
        assertTrue(block.contains("\"columnName\": \"source\""))
        assertTrue(block.contains("\"defaultValue\": \"'IMPORT'\""))
    }
}
