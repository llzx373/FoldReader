package com.llzx373.foldreader.core.data.db

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v13 = `books` 新增 `contentPreparedAt`（EPUB/FB2 压平就绪时间，书架角标用）。
 */
class SchemaV13Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/13.json").readText()
    }

    private fun booksBlock(): String {
        val start = schema.indexOf("\"tableName\": \"books\"")
        require(start >= 0) { "13.json 中缺少表 books" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        return schema.substring(start, if (next >= 0) next else schema.length)
    }

    @Test
    fun `v13 schema 导出文件存在且版本为 13`() {
        assertTrue(schema.contains("\"version\": 13"))
    }

    @Test
    fun `books 新增可空的内容就绪列`() {
        val block = booksBlock()
        val idx = block.indexOf("\"columnName\": \"contentPreparedAt\"")
        assertTrue("books 表应含 contentPreparedAt 列", idx >= 0)
        // 可空：旧行没有这个信息，必须允许 NULL（= 未就绪），不能给列加默认值糊过去
        assertFalse(
            "contentPreparedAt 应可空",
            block.substring(idx, idx + 160).contains("\"notNull\": true"),
        )
    }
}
