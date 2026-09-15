package com.llzx373.foldreader.core.data.db

import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaV5Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/5.json").readText()
    }

    private fun entityBlock(tableName: String): String {
        val start = schema.indexOf("\"tableName\": \"$tableName\"")
        require(start >= 0) { "5.json 中缺少表 $tableName" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        return schema.substring(start, if (next >= 0) next else schema.length)
    }

    @Test
    fun `v5 schema 导出文件存在且版本为 5`() {
        assertTrue(schema.contains("\"version\": 5"))
    }

    @Test
    fun `books 表新增 groupName 与 cleanedFilePath 可空列`() {
        val block = entityBlock("books")
        assertTrue(block.contains("\"columnName\": \"groupName\""))
        assertTrue(block.contains("\"columnName\": \"cleanedFilePath\""))
        val book = BookEntity(0, "", null, "", "", BookFormat.TXT, 0, "UTF-8", 0, null)
        assertEquals(null, book.groupName)
        assertEquals(null, book.cleanedFilePath)
    }

    @Test
    fun `reading_progress 新增 charsReadTotal 默认 0 且外键 NO ACTION`() {
        val block = entityBlock("reading_progress")
        assertTrue(block.contains("\"columnName\": \"charsReadTotal\""))
        assertTrue(block.contains("\"onDelete\": \"NO ACTION\""))
        val progress = ReadingProgressEntity(1, 0, 0, 0, updatedAt = 0)
        assertEquals(0L, progress.charsReadTotal)
    }

    @Test
    fun `书签与标注外键改为 NO ACTION`() {
        assertTrue(entityBlock("bookmarks").contains("\"onDelete\": \"NO ACTION\""))
        assertTrue(entityBlock("annotations").contains("\"onDelete\": \"NO ACTION\""))
    }

    @Test
    fun `章节与会话外键保持 CASCADE`() {
        assertTrue(entityBlock("chapters").contains("\"onDelete\": \"CASCADE\""))
        assertTrue(entityBlock("reading_sessions").contains("\"onDelete\": \"CASCADE\""))
    }

    @Test
    fun `offset_index 复合主键且元信息表存在`() {
        val index = entityBlock("offset_index")
        assertTrue(index.contains("PRIMARY KEY(`bookId`, `chunkIndex`)"))
        assertTrue(index.contains("\"onDelete\": \"CASCADE\""))
        val meta = entityBlock("offset_index_meta")
        for (column in listOf("fileLength", "contentHash", "charsetName", "totalChars", "completed")) {
            assertTrue(meta.contains("\"columnName\": \"$column\""))
        }
    }

    @Test
    fun `book_prefs 默认值与全局阅读偏好一致`() {
        val block = entityBlock("book_prefs")
        assertTrue(block.contains("\"onDelete\": \"CASCADE\""))
        val prefs = BookPrefsEntity(bookId = 1)
        val defaults = ReadingPreferences()

        assertEquals(defaults.fontSizeSp, prefs.fontSizeSp, 0.0001f)
        assertEquals(defaults.lineSpacingMultiplier, prefs.lineSpacingMultiplier, 0.0001f)
        assertEquals(defaults.marginLevel, prefs.marginLevel)
        assertEquals(defaults.themeId.name, prefs.themeId)
        assertEquals(defaults.customBackgroundArgb, prefs.customBackgroundArgb)
        assertEquals(defaults.customTextArgb, prefs.customTextArgb)
        assertEquals(defaults.darkThemeOption.name, prefs.darkThemeOption)
        assertEquals(defaults.fontKey, prefs.fontKey)
        assertEquals(defaults.dualPageMode.name, prefs.dualPageMode)
        assertEquals(defaults.pageTurnMode.name, prefs.pageTurnMode)
        assertEquals(defaults.pageTurnHotspotRatio, prefs.pageTurnHotspotRatio, 0.0001f)
        assertEquals(defaults.volumeKeyPagingEnabled, prefs.volumeKeyPagingEnabled)
        assertEquals(defaults.keepScreenOn, prefs.keepScreenOn)
        assertEquals(defaults.showChapterTitle, prefs.showChapterTitle)
        assertEquals(defaults.showPageProgress, prefs.showPageProgress)
        assertEquals(defaults.showBattery, prefs.showBattery)
        assertEquals(defaults.showTime, prefs.showTime)
        assertEquals(defaults.readerBrightness, prefs.readerBrightness, 0.0001f)
        assertEquals(defaults.autoPageEnabled, prefs.autoPageEnabled)
        assertEquals(defaults.autoPageMode.name, prefs.autoPageMode)
        assertEquals(defaults.autoPageIntervalSec, prefs.autoPageIntervalSec)
        assertEquals(defaults.autoPageSpeedPx, prefs.autoPageSpeedPx, 0.0001f)
        assertEquals(defaults.simulationDegraded, prefs.simulationDegraded)
        assertEquals(defaults.panelScreenOff, prefs.panelScreenOff)
    }
}
