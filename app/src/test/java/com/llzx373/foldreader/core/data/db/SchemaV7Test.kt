package com.llzx373.foldreader.core.data.db

import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaV7Test {

    private val schema: String by lazy {
        File("schemas/com.llzx373.foldreader.core.data.db.FoldReaderDatabase/7.json").readText()
    }

    private fun entityBlock(tableName: String): String {
        val start = schema.indexOf("\"tableName\": \"$tableName\"")
        require(start >= 0) { "7.json 中缺少表 $tableName" }
        val next = schema.indexOf("\"tableName\": \"", start + 1)
        return schema.substring(start, if (next >= 0) next else schema.length)
    }

    @Test
    fun `v7 schema 导出文件存在且版本为 7`() {
        assertTrue(schema.contains("\"version\": 7"))
    }

    @Test
    fun `book_prefs 新增排版与开关列且带默认值`() {
        val block = entityBlock("book_prefs")
        val expected = mapOf(
            "maxLineChars" to "40",
            "paragraphSpacingEm" to "0.4",
            "letterSpacingEm" to "0.0",
            "pageTurnModeExplicit" to "0",
            "showPageNumber" to "1",
        )
        for ((column, default) in expected) {
            assertTrue(block.contains("\"columnName\": \"$column\""))
            assertTrue(block.contains("\"defaultValue\": \"$default\""))
        }
    }

    @Test
    fun `book_prefs 默认值与全局阅读偏好逐项一致`() {
        val prefs = BookPrefsEntity(bookId = 1)
        val defaults = ReadingPreferences()

        assertEquals(defaults.fontSizeSp, prefs.fontSizeSp, 0.0001f)
        assertEquals(defaults.lineSpacingMultiplier, prefs.lineSpacingMultiplier, 0.0001f)
        assertEquals(defaults.marginLevel, prefs.marginLevel)
        assertEquals(defaults.maxLineChars, prefs.maxLineChars)
        assertEquals(defaults.paragraphSpacingEm, prefs.paragraphSpacingEm, 0.0001f)
        assertEquals(defaults.letterSpacingEm, prefs.letterSpacingEm, 0.0001f)
        assertEquals(defaults.themeId.name, prefs.themeId)
        assertEquals(defaults.customBackgroundArgb, prefs.customBackgroundArgb)
        assertEquals(defaults.customTextArgb, prefs.customTextArgb)
        assertEquals(defaults.darkThemeOption.name, prefs.darkThemeOption)
        assertEquals(defaults.fontKey, prefs.fontKey)
        assertEquals(defaults.dualPageMode.name, prefs.dualPageMode)
        assertEquals(defaults.pageTurnMode.name, prefs.pageTurnMode)
        assertEquals(defaults.pageTurnModeExplicit, prefs.pageTurnModeExplicit)
        assertEquals(defaults.pageTurnHotspotRatio, prefs.pageTurnHotspotRatio, 0.0001f)
        assertEquals(defaults.volumeKeyPagingEnabled, prefs.volumeKeyPagingEnabled)
        assertEquals(defaults.keepScreenOn, prefs.keepScreenOn)
        assertEquals(defaults.showChapterTitle, prefs.showChapterTitle)
        assertEquals(defaults.showPageProgress, prefs.showPageProgress)
        assertEquals(defaults.showPageNumber, prefs.showPageNumber)
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

    @Test
    fun `其余表结构沿用 v6`() {
        assertTrue(entityBlock("books").contains("\"columnName\": \"groupName\""))
        assertTrue(entityBlock("reading_progress").contains("\"columnName\": \"charsReadTotal\""))
        assertTrue(entityBlock("annotations").contains("\"columnName\": \"style\""))
        assertTrue(entityBlock("book_prefs").contains("\"onDelete\": \"CASCADE\""))
    }
}
