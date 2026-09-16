package com.llzx373.foldreader.core.backup

import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookPrefsDao
import com.llzx373.foldreader.core.data.db.BookPrefsEntity
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionDao
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.format.Chapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val fullPrefs = ReadingPreferences(
        fontSizeSp = 22f,
        lineSpacingMultiplier = 1.8f,
        marginLevel = 2,
        maxLineChars = 28,
        paragraphSpacingEm = 0.9f,
        letterSpacingEm = 0.2f,
        themeId = ReadingTheme.NIGHT,
        customBackgroundArgb = 0xFF102030.toInt(),
        customTextArgb = 0xFFE0E0E0.toInt(),
        darkThemeOption = DarkThemeOption.DARK,
        fontKey = "serif",
        dualPageMode = DualPageMode.FORCE_DUAL,
        wideScreenDualPage = true,
        pageTurnMode = PageTurnMode.SCROLL,
        pageTurnHotspotRatio = 0.5f,
        volumeKeyPagingEnabled = true,
        brightnessGestureEnabled = false,
        keepScreenOn = true,
        showChapterTitle = false,
        showPageProgress = false,
        showPageNumber = false,
        showBattery = false,
        showTime = false,
        readerBrightness = 0.6f,
        autoPageEnabled = true,
        autoPageMode = AutoPageMode.SCROLL,
        autoPageIntervalSec = 20,
        autoPageSpeedPx = 120f,
        simulationDegraded = true,
        panelScreenOff = true,
        bookshelfGridView = false,
        customChapterRules = listOf("^第.+章$", "^卷 \\d+ .+"),
        adCleanRules = listOf("公众号", "^【广告】.*$"),
    )

    @Test
    fun `偏好字段导出导入往返一致`() = runBlocking {
        val sourceSettings = FakeSettingsRepository(fullPrefs)
        val source = BackupCodec(
            FakeBookshelfRepository(),
            sourceSettings,
            FakeBookPrefsDao(),
            FakeSessionDao(),
        )
        val json = source.exportJson().toString()

        val targetSettings = FakeSettingsRepository()
        val target = BackupCodec(
            FakeBookshelfRepository(),
            targetSettings,
            FakeBookPrefsDao(),
            FakeSessionDao(),
        )
        target.importJson(json)

        assertEquals(fullPrefs, targetSettings.snapshot())
    }

    @Test
    fun `旧版本备份缺少新字段时新字段回落默认值`() = runBlocking {
        val legacy = """
            {
              "app": "FoldReader",
              "version": 1,
              "preferences": {
                "fontSizeSp": 20.0,
                "themeId": "NIGHT",
                "keepScreenOn": true,
                "bookshelfGridView": false
              },
              "books": []
            }
        """.trimIndent()
        val targetSettings = FakeSettingsRepository()
        val target = BackupCodec(
            FakeBookshelfRepository(),
            targetSettings,
            FakeBookPrefsDao(),
            FakeSessionDao(),
        )

        target.importJson(legacy)

        val restored = targetSettings.snapshot()
        assertEquals(20f, restored.fontSizeSp, 0.0001f)
        assertEquals(ReadingTheme.NIGHT, restored.themeId)
        assertTrue(restored.keepScreenOn)
        val defaults = ReadingPreferences()
        assertEquals(defaults.maxLineChars, restored.maxLineChars)
        assertEquals(defaults.paragraphSpacingEm, restored.paragraphSpacingEm, 0.0001f)
        assertEquals(defaults.letterSpacingEm, restored.letterSpacingEm, 0.0001f)
        assertEquals(defaults.fontKey, restored.fontKey)
        assertNull(restored.customBackgroundArgb)
        assertNull(restored.customTextArgb)
        assertEquals(defaults.wideScreenDualPage, restored.wideScreenDualPage)
        assertEquals(defaults.showPageNumber, restored.showPageNumber)
        assertEquals(defaults.brightnessGestureEnabled, restored.brightnessGestureEnabled)
        assertEquals(defaults.readerBrightness, restored.readerBrightness, 0.0001f)
        assertEquals(defaults.panelScreenOff, restored.panelScreenOff)
        assertEquals(emptyList<String>(), restored.customChapterRules)
        assertEquals(emptyList<String>(), restored.adCleanRules)
    }

    @Test
    fun `阅读会话与每书偏好随书籍匹配恢复`() = runBlocking {
        val sourceBooks = FakeBookshelfRepository(mutableListOf(book(id = 1, hash = "hashA")))
        sourceBooks.progress += ReadingProgressEntity(
            bookId = 1,
            charOffset = 500,
            chapterIndex = 3,
            totalReadingMillis = 60000,
            firstReadAt = 10,
            updatedAt = 100,
        )
        sourceBooks.bookmarks += BookmarkEntity(
            bookId = 1,
            charOffset = 200,
            chapterIndex = 1,
            snapshotText = "快照",
            label = "书签",
            createdAt = 50,
        )
        sourceBooks.annotations += AnnotationEntity(
            bookId = 1,
            startCharOffset = 10,
            endCharOffset = 20,
            selectedText = "选中文本",
            color = 0xFF00FF00,
            note = "笔记",
            createdAt = 60,
            updatedAt = 70,
        )
        val sourceSessions = FakeSessionDao()
        sourceSessions.rows += ReadingSessionEntity(bookId = 1, dayStartMs = 1000, durationMs = 60000)
        sourceSessions.rows += ReadingSessionEntity(bookId = 1, dayStartMs = 2000, durationMs = 30000)
        val sourceBookPrefs = FakeBookPrefsDao()
        sourceBookPrefs.rows += BookPrefsEntity(
            bookId = 1,
            fontSizeSp = 24f,
            themeId = "NIGHT",
            fontKey = "serif",
            panelScreenOff = true,
        )
        val json = BackupCodec(
            sourceBooks,
            FakeSettingsRepository(),
            sourceBookPrefs,
            sourceSessions,
        ).exportJson().toString()

        val targetBooks = FakeBookshelfRepository(mutableListOf(book(id = 7, hash = "hashA")))
        val targetSessions = FakeSessionDao()
        val targetBookPrefs = FakeBookPrefsDao()
        val result = BackupCodec(
            targetBooks,
            FakeSettingsRepository(),
            targetBookPrefs,
            targetSessions,
        ).importJson(json)

        assertEquals(1, result.restoredBooks)
        assertEquals(1, result.restoredBookmarks)
        assertEquals(1, result.restoredAnnotations)
        assertEquals(2, result.restoredSessions)
        assertEquals(1, result.restoredBookPrefs)
        assertTrue(result.missingBookTitles.isEmpty())

        val progress = targetBooks.progress.single()
        assertEquals(7L, progress.bookId)
        assertEquals(500L, progress.charOffset)
        assertEquals(listOf(1000L, 2000L), targetSessions.rows.map { it.dayStartMs }.sorted())
        assertTrue(targetSessions.rows.all { it.bookId == 7L })
        assertEquals(60000L, targetSessions.rows.first { it.dayStartMs == 1000L }.durationMs)

        val prefs = targetBookPrefs.rows.single()
        assertEquals(7L, prefs.bookId)
        assertEquals(24f, prefs.fontSizeSp, 0.0001f)
        assertEquals("NIGHT", prefs.themeId)
        assertEquals("serif", prefs.fontKey)
        assertTrue(prefs.panelScreenOff)

        assertEquals(200L, targetBooks.bookmarks.single().charOffset)
        assertEquals("选中文本", targetBooks.annotations.single().selectedText)
    }

    @Test
    fun `书籍未匹配时会话与每书偏好不产生孤儿记录`() = runBlocking {
        val sourceBooks = FakeBookshelfRepository(mutableListOf(book(id = 1, hash = "hashX")))
        val sourceSessions = FakeSessionDao()
        sourceSessions.rows += ReadingSessionEntity(bookId = 1, dayStartMs = 1000, durationMs = 60000)
        val sourceBookPrefs = FakeBookPrefsDao()
        sourceBookPrefs.rows += BookPrefsEntity(bookId = 1, fontSizeSp = 24f)
        val json = BackupCodec(
            sourceBooks,
            FakeSettingsRepository(),
            sourceBookPrefs,
            sourceSessions,
        ).exportJson().toString()

        val targetBooks = FakeBookshelfRepository()
        val targetSessions = FakeSessionDao()
        val targetBookPrefs = FakeBookPrefsDao()
        val result = BackupCodec(
            targetBooks,
            FakeSettingsRepository(),
            targetBookPrefs,
            targetSessions,
        ).importJson(json)

        assertEquals(0, result.restoredBooks)
        assertEquals(listOf("书hashX"), result.missingBookTitles)
        assertTrue(targetBooks.progress.isEmpty())
        assertTrue(targetBooks.bookmarks.isEmpty())
        assertTrue(targetBooks.annotations.isEmpty())
        assertTrue(targetSessions.rows.isEmpty())
        assertTrue(targetBookPrefs.rows.isEmpty())
    }

    private fun book(id: Long, hash: String) = BookEntity(
        id = id,
        title = "书$hash",
        author = null,
        fileUri = "content://book/$hash",
        contentHash = hash,
        format = BookFormat.TXT,
        totalChars = 1000,
        encoding = "UTF-8",
        importedAt = 0,
        lastReadAt = null,
    )

    private class FakeSettingsRepository(
        initial: ReadingPreferences = ReadingPreferences(),
    ) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<ReadingPreferences> = state
        fun snapshot(): ReadingPreferences = state.value

        override suspend fun setFontSize(sizeSp: Float) = update { copy(fontSizeSp = sizeSp) }
        override suspend fun setLineSpacing(multiplier: Float) =
            update { copy(lineSpacingMultiplier = multiplier) }
        override suspend fun setMarginLevel(level: Int) = update { copy(marginLevel = level) }
        override suspend fun setMaxLineChars(chars: Int) = update { copy(maxLineChars = chars) }
        override suspend fun setParagraphSpacingEm(spacingEm: Float) =
            update { copy(paragraphSpacingEm = spacingEm) }
        override suspend fun setLetterSpacingEm(spacingEm: Float) =
            update { copy(letterSpacingEm = spacingEm) }
        override suspend fun setTheme(theme: ReadingTheme) = update { copy(themeId = theme) }
        override suspend fun setCustomColors(backgroundArgb: Int?, textArgb: Int?) =
            update { copy(customBackgroundArgb = backgroundArgb, customTextArgb = textArgb) }
        override suspend fun setDarkThemeOption(option: DarkThemeOption) =
            update { copy(darkThemeOption = option) }
        override suspend fun setFontKey(fontKey: String) = update { copy(fontKey = fontKey) }
        override suspend fun setDualPageMode(mode: DualPageMode) =
            update { copy(dualPageMode = mode) }
        override suspend fun setWideScreenDualPage(enabled: Boolean) =
            update { copy(wideScreenDualPage = enabled) }
        override suspend fun setAvoidCameraCutout(enabled: Boolean) =
            update { copy(avoidCameraCutout = enabled) }
        override suspend fun setPageTurnMode(mode: PageTurnMode) =
            update { copy(pageTurnMode = mode) }
        override suspend fun setPageTurnModeExplicit(explicit: Boolean) =
            update { copy(pageTurnModeExplicit = explicit) }
        override suspend fun setPageTurnHotspotRatio(ratio: Float) =
            update { copy(pageTurnHotspotRatio = ratio) }
        override suspend fun setVolumeKeyPagingEnabled(enabled: Boolean) =
            update { copy(volumeKeyPagingEnabled = enabled) }
        override suspend fun setBrightnessGestureEnabled(enabled: Boolean) =
            update { copy(brightnessGestureEnabled = enabled) }
        override suspend fun setSwipeGestureEnabled(enabled: Boolean) =
            update { copy(swipeGestureEnabled = enabled) }
        override suspend fun setKeepScreenOn(enabled: Boolean) =
            update { copy(keepScreenOn = enabled) }
        override suspend fun setShowChapterTitle(enabled: Boolean) =
            update { copy(showChapterTitle = enabled) }
        override suspend fun setShowPageProgress(enabled: Boolean) =
            update { copy(showPageProgress = enabled) }
        override suspend fun setShowPageNumber(enabled: Boolean) =
            update { copy(showPageNumber = enabled) }
        override suspend fun setShowBattery(enabled: Boolean) =
            update { copy(showBattery = enabled) }
        override suspend fun setShowTime(enabled: Boolean) = update { copy(showTime = enabled) }
        override suspend fun setReaderBrightness(brightness: Float) =
            update { copy(readerBrightness = brightness) }
        override suspend fun setAutoPageEnabled(enabled: Boolean) =
            update { copy(autoPageEnabled = enabled) }
        override suspend fun setAutoPageMode(mode: AutoPageMode) =
            update { copy(autoPageMode = mode) }
        override suspend fun setAutoPageIntervalSec(seconds: Int) =
            update { copy(autoPageIntervalSec = seconds) }
        override suspend fun setAutoPageSpeedPx(pxPerSecond: Float) =
            update { copy(autoPageSpeedPx = pxPerSecond) }
        override suspend fun setSimulationDegraded(degraded: Boolean) =
            update { copy(simulationDegraded = degraded) }
        override suspend fun setPanelScreenOff(enabled: Boolean) =
            update { copy(panelScreenOff = enabled) }
        override suspend fun setBookshelfGridView(gridView: Boolean) =
            update { copy(bookshelfGridView = gridView) }
        override suspend fun setBookshelfSort(sort: com.llzx373.foldreader.core.data.settings.BookshelfSort) =
            update { copy(bookshelfSort = sort) }
        override suspend fun setCustomChapterRules(rules: List<String>) =
            update { copy(customChapterRules = rules) }
        override suspend fun setAdCleanRules(rules: List<String>) =
            update { copy(adCleanRules = rules) }

        private fun update(block: ReadingPreferences.() -> ReadingPreferences) {
            state.value = state.value.block()
        }
    }

    private class FakeBookshelfRepository(
        val books: MutableList<BookEntity> = mutableListOf(),
    ) : BookshelfRepository {
        val progress = mutableListOf<ReadingProgressEntity>()
        val bookmarks = mutableListOf<BookmarkEntity>()
        val annotations = mutableListOf<AnnotationEntity>()

        override fun observeBookshelf(): Flow<List<BookEntity>> = flowOf(books.toList())
        override fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override suspend fun getBook(bookId: Long): BookEntity? = books.find { it.id == bookId }
        override fun observeBook(bookId: Long): Flow<BookEntity?> = flowOf(books.find { it.id == bookId })
        override suspend fun updateEncoding(bookId: Long, encoding: String) = Unit
        override suspend fun findByFileUri(fileUri: String): BookEntity? =
            books.find { it.fileUri == fileUri }
        override suspend fun findByContentHash(contentHash: String): BookEntity? =
            books.find { it.contentHash == contentHash }
        override suspend fun upsertBook(book: BookEntity): Long = book.id
        override suspend fun touchLastRead(bookId: Long, timestamp: Long) = Unit
        override suspend fun deleteBooks(bookIds: List<Long>, deleteLocalData: Boolean) = Unit
        override fun observeGroupNames(): Flow<List<String>> = flowOf(emptyList())
        override fun observeBookshelfWithProgressInGroup(
            groupName: String?,
        ): Flow<List<BookWithProgress>> = flowOf(emptyList())
        override suspend fun updateGroup(bookIds: List<Long>, groupName: String?) = Unit
        override suspend fun clearGroup(groupName: String) = Unit

        override fun observeProgress(bookId: Long): Flow<ReadingProgressEntity?> =
            flowOf(progress.find { it.bookId == bookId })
        override suspend fun getProgress(bookId: Long): ReadingProgressEntity? =
            progress.find { it.bookId == bookId }
        override suspend fun saveProgress(progress: ReadingProgressEntity) {
            this.progress.removeAll { it.bookId == progress.bookId }
            this.progress += progress
        }

        override suspend fun getChapters(bookId: Long): List<Chapter> = emptyList()
        override fun observeChapters(bookId: Long): Flow<List<Chapter>> = flowOf(emptyList())
        override suspend fun saveChapters(bookId: Long, chapters: List<Chapter>) = Unit

        override fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>> =
            flowOf(bookmarks.filter { it.bookId == bookId })
        override fun observeAllBookmarks(): Flow<List<BookmarkEntity>> = flowOf(bookmarks.toList())
        override suspend fun addBookmark(bookmark: BookmarkEntity): Long {
            bookmarks += bookmark
            return bookmark.id
        }
        override suspend fun renameBookmark(bookmark: BookmarkEntity) = Unit
        override suspend fun deleteBookmark(id: Long) = Unit

        override fun observeAnnotations(bookId: Long): Flow<List<AnnotationEntity>> =
            flowOf(annotations.filter { it.bookId == bookId })
        override fun observeAllAnnotations(): Flow<List<AnnotationEntity>> =
            flowOf(annotations.toList())
        override suspend fun addAnnotation(annotation: AnnotationEntity): Long {
            annotations += annotation
            return annotation.id
        }
        override suspend fun updateAnnotation(annotation: AnnotationEntity) = Unit
        override suspend fun deleteAnnotation(id: Long) = Unit

        override suspend fun addReadingSession(bookId: Long, dayStartMs: Long, deltaMs: Long) = Unit
        override suspend fun getReadingSessionsBetween(
            startMs: Long,
            endMs: Long,
        ): List<ReadingSessionEntity> = emptyList()
        override suspend fun getReadingDayCount(bookId: Long): Int = 0
    }

    private class FakeBookPrefsDao : BookPrefsDao {
        val rows = mutableListOf<BookPrefsEntity>()
        override fun observe(bookId: Long): Flow<BookPrefsEntity?> =
            flowOf(rows.find { it.bookId == bookId })
        override suspend fun get(bookId: Long): BookPrefsEntity? = rows.find { it.bookId == bookId }
        override suspend fun getAll(): List<BookPrefsEntity> = rows.toList()
        override suspend fun upsert(prefs: BookPrefsEntity) {
            rows.removeAll { it.bookId == prefs.bookId }
            rows += prefs
        }
        override suspend fun applyGlobalPageTurnMode(mode: String) {
            rows.replaceAll {
                it.copy(pageTurnMode = mode, pageTurnModeExplicit = true, simulationDegraded = false)
            }
        }
        override suspend fun delete(bookId: Long) {
            rows.removeAll { it.bookId == bookId }
        }
    }

    private class FakeSessionDao : ReadingSessionDao {
        val rows = mutableListOf<ReadingSessionEntity>()
        override suspend fun get(bookId: Long, dayStartMs: Long): ReadingSessionEntity? =
            rows.find { it.bookId == bookId && it.dayStartMs == dayStartMs }
        override suspend fun upsert(session: ReadingSessionEntity) {
            rows.removeAll { it.bookId == session.bookId && it.dayStartMs == session.dayStartMs }
            rows += session
        }
        override suspend fun getBetween(startMs: Long, endMs: Long): List<ReadingSessionEntity> =
            rows.filter { it.dayStartMs in startMs..endMs }
        override suspend fun getAll(): List<ReadingSessionEntity> = rows.toList()
        override suspend fun countReadingDays(bookId: Long): Int =
            rows.filter { it.bookId == bookId }.size
    }
}
