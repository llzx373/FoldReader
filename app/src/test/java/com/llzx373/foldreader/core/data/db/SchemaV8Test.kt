package com.llzx373.foldreader.core.data.db

import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaV8Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/8.json").readText()
    }

    @Test
    fun `v8 schema 导出文件存在且版本为 8`() {
        assertTrue(schema.contains("\"version\": 8"))
    }

    @Test
    fun `book_prefs 新增 autoIndentEnabled 列且默认开启`() {
        val start = schema.indexOf("\"tableName\": \"book_prefs\"")
        require(start >= 0) { "8.json 中缺少表 book_prefs" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        val block = schema.substring(start, if (next >= 0) next else schema.length)
        assertTrue(block.contains("\"columnName\": \"autoIndentEnabled\""))
        assertTrue(block.contains("\"defaultValue\": \"1\""))
    }

    @Test
    fun `book_prefs 新列默认值与全局阅读偏好一致`() {
        assertEquals(ReadingPreferences().autoIndentEnabled, BookPrefsEntity(bookId = 1).autoIndentEnabled)
    }
}
