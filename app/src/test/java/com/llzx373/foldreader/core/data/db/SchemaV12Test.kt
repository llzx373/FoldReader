package com.llzx373.foldreader.core.data.db

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v12 = `offset_index` 换成「字节起点 + 字符起点」双列模型。
 * Room 运行期会拿实体定义与打开后的库做 schema 校验，导出 schema 是这条链路的静态证据。
 */
class SchemaV12Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/12.json").readText()
    }

    private fun tableBlock(table: String): String {
        val start = schema.indexOf("\"tableName\": \"$table\"")
        require(start >= 0) { "12.json 中缺少表 $table" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        return schema.substring(start, if (next >= 0) next else schema.length)
    }

    @Test
    fun `v12 schema 导出文件存在且版本为 12`() {
        assertTrue(schema.contains("\"version\": 12"))
    }

    @Test
    fun `offset_index 同时持久化字节起点与字符起点`() {
        val block = tableBlock("offset_index")

        assertTrue("需要 byteOffset 列", block.contains("\"columnName\": \"byteOffset\""))
        assertTrue("需要 charStart 列", block.contains("\"columnName\": \"charStart\""))
        assertFalse("charOffset 已更名", block.contains("\"columnName\": \"charOffset\""))

        // 两列都必须非空：缺失即代表索引不可用，不该留下半份数据
        for (column in listOf("byteOffset", "charStart")) {
            val idx = block.indexOf("\"columnName\": \"$column\"")
            assertTrue("列 $column 应非空", block.substring(idx, idx + 120).contains("\"notNull\": true"))
        }
    }

    @Test
    fun `offset_index 主键与外键保持不变`() {
        val block = tableBlock("offset_index")

        assertTrue(block.contains("PRIMARY KEY(`bookId`, `chunkIndex`)"))
        assertTrue(block.contains("REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
    }
}
