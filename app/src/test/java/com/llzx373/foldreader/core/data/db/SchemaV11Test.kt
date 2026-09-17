package com.llzx373.foldreader.core.data.db

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v11 = 索引增删。Room 在运行期会拿实体定义与打开后的库做 schema 校验，
 * 导出 schema 是这条链路的唯一静态证据，必须与 [MIGRATION_10_11] 的 SQL 对上。
 */
class SchemaV11Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/11.json").readText()
    }

    private fun tableBlock(table: String): String {
        val start = schema.indexOf("\"tableName\": \"$table\"")
        require(start >= 0) { "11.json 中缺少表 $table" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        return schema.substring(start, if (next >= 0) next else schema.length)
    }

    @Test
    fun `v11 schema 导出文件存在且版本为 11`() {
        assertTrue(schema.contains("\"version\": 11"))
    }

    @Test
    fun `书签与标注补上复合索引`() {
        val bookmarks = tableBlock("bookmarks")
        assertTrue("bookmarks 需要 (bookId, createdAt) 复合索引", bookmarks.contains("index_bookmarks_bookId_createdAt"))
        assertFalse("bookmarks 的单列 bookId 索引已被复合索引取代", bookmarks.contains("index_bookmarks_bookId\""))

        val annotations = tableBlock("annotations")
        assertTrue(
            "annotations 需要 (bookId, startCharOffset) 复合索引",
            annotations.contains("index_annotations_bookId_startCharOffset"),
        )
        assertFalse("annotations 的单列 bookId 索引已被复合索引取代", annotations.contains("index_annotations_bookId\""))
    }

    @Test
    fun `与主键前缀重复的单列索引被移除`() {
        // chapters 主键 (bookId, chapterIndex)、offset_index 主键 (bookId, chunkIndex)
        assertFalse(tableBlock("chapters").contains("index_chapters_bookId\""))
        assertFalse(tableBlock("offset_index").contains("index_offset_index_bookId\""))
    }
}
