package com.llzx373.foldreader.core.data.repository

import com.llzx373.foldreader.core.data.db.BookPrefsDao
import com.llzx373.foldreader.core.data.db.BookPrefsEntity
import com.llzx373.foldreader.core.data.db.toBookPrefsEntity
import com.llzx373.foldreader.core.data.db.toReadingPreferences
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.TapAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookPrefsRepositoryTest {

    private val global = ReadingPreferences(
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
        swipeGestureEnabled = false,
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
        panelScreenOff = true,
        bookshelfGridView = false,
        customChapterRules = listOf("^第.+章$"),
        adCleanRules = listOf("公众号"),
    )

    @Test
    fun `新书首次打开无记录时拷贝当前全局默认落库`() = runBlocking {
        val dao = FakeBookPrefsDao()
        val repository = BookPrefsRepository(dao, FakeSettingsRepository(global))

        repository.ensureInitialized(bookId = 1)

        assertEquals(global.toBookPrefsEntity(bookId = 1), dao.get(1))
    }

    @Test
    fun `已有记录时初始化不覆盖每书偏好`() = runBlocking {
        val dao = FakeBookPrefsDao()
        dao.upsert(BookPrefsEntity(bookId = 1, fontSizeSp = 30f, themeId = "AMOLED"))
        val repository = BookPrefsRepository(dao, FakeSettingsRepository(global))

        repository.ensureInitialized(bookId = 1)

        assertEquals(30f, dao.get(1)!!.fontSizeSp, 0.0001f)
        assertEquals("AMOLED", dao.get(1)!!.themeId)
    }

    @Test
    fun `修改 A 书偏好不影响 B 书与全局默认`() = runBlocking {
        val dao = FakeBookPrefsDao()
        val settings = FakeSettingsRepository(global)
        val repository = BookPrefsRepository(dao, settings)
        repository.ensureInitialized(bookId = 1)
        repository.ensureInitialized(bookId = 2)

        repository.update(bookId = 1) { it.copy(fontSizeSp = 30f, pageTurnMode = "NONE") }

        assertEquals(30f, dao.get(1)!!.fontSizeSp, 0.0001f)
        assertEquals("NONE", dao.get(1)!!.pageTurnMode)
        assertEquals(global.fontSizeSp, dao.get(2)!!.fontSizeSp, 0.0001f)
        assertEquals(global.pageTurnMode.name, dao.get(2)!!.pageTurnMode)
        assertEquals(global, settings.snapshot())
    }

    @Test
    fun `观察合并每书字段与全局字段`() = runBlocking {
        val dao = FakeBookPrefsDao()
        dao.upsert(BookPrefsEntity(bookId = 1, fontSizeSp = 30f, keepScreenOn = true))
        val repository = BookPrefsRepository(dao, FakeSettingsRepository(global))

        val merged = repository.observe(bookId = 1).first()

        assertEquals(30f, merged.fontSizeSp, 0.0001f)
        assertTrue(merged.keepScreenOn)
        assertEquals(global.wideScreenDualPage, merged.wideScreenDualPage)
        assertEquals(global.brightnessGestureEnabled, merged.brightnessGestureEnabled)
        assertEquals(global.swipeGestureEnabled, merged.swipeGestureEnabled)
        assertEquals(global.bookshelfGridView, merged.bookshelfGridView)
        assertEquals(global.customChapterRules, merged.customChapterRules)
        assertEquals(global.adCleanRules, merged.adCleanRules)
    }

    @Test
    fun `无记录时观察直接回落全局偏好`() = runBlocking {
        val repository = BookPrefsRepository(FakeBookPrefsDao(), FakeSettingsRepository(global))

        assertEquals(global, repository.observe(bookId = 99).first())
    }

    @Test
    fun `每书字段映射往返一致`() = runBlocking {
        val entity = global.toBookPrefsEntity(bookId = 7)
        val roundTripped = entity.toReadingPreferences(ReadingPreferences())

        assertEquals(global.fontSizeSp, roundTripped.fontSizeSp, 0.0001f)
        assertEquals(global.lineSpacingMultiplier, roundTripped.lineSpacingMultiplier, 0.0001f)
        assertEquals(global.marginLevel, roundTripped.marginLevel)
        assertEquals(global.maxLineChars, roundTripped.maxLineChars)
        assertEquals(global.paragraphSpacingEm, roundTripped.paragraphSpacingEm, 0.0001f)
        assertEquals(global.letterSpacingEm, roundTripped.letterSpacingEm, 0.0001f)
        assertEquals(global.themeId, roundTripped.themeId)
        assertEquals(global.customBackgroundArgb, roundTripped.customBackgroundArgb)
        assertEquals(global.customTextArgb, roundTripped.customTextArgb)
        assertEquals(global.darkThemeOption, roundTripped.darkThemeOption)
        assertEquals(global.fontKey, roundTripped.fontKey)
        assertEquals(global.dualPageMode, roundTripped.dualPageMode)
        assertEquals(global.pageTurnMode, roundTripped.pageTurnMode)
        assertEquals(global.pageTurnHotspotRatio, roundTripped.pageTurnHotspotRatio, 0.0001f)
        assertEquals(global.volumeKeyPagingEnabled, roundTripped.volumeKeyPagingEnabled)
        assertEquals(global.keepScreenOn, roundTripped.keepScreenOn)
        assertEquals(global.showChapterTitle, roundTripped.showChapterTitle)
        assertEquals(global.showPageProgress, roundTripped.showPageProgress)
        assertEquals(global.showPageNumber, roundTripped.showPageNumber)
        assertEquals(global.showBattery, roundTripped.showBattery)
        assertEquals(global.showTime, roundTripped.showTime)
        assertEquals(global.readerBrightness, roundTripped.readerBrightness, 0.0001f)
        assertEquals(global.autoPageEnabled, roundTripped.autoPageEnabled)
        assertEquals(global.autoPageMode, roundTripped.autoPageMode)
        assertEquals(global.autoPageIntervalSec, roundTripped.autoPageIntervalSec)
        assertEquals(global.autoPageSpeedPx, roundTripped.autoPageSpeedPx, 0.0001f)
        assertEquals(global.panelScreenOff, roundTripped.panelScreenOff)
    }

    @Test
    fun `行内未落库时 update 以全局默认为基底`() = runBlocking {
        val dao = FakeBookPrefsDao()
        val repository = BookPrefsRepository(dao, FakeSettingsRepository(global))

        repository.update(bookId = 3) { it.copy(fontSizeSp = 26f) }

        val stored = dao.get(3)!!
        assertEquals(26f, stored.fontSizeSp, 0.0001f)
        assertEquals(global.themeId.name, stored.themeId)
        assertEquals(global.autoPageIntervalSec, stored.autoPageIntervalSec)
    }

    @Test
    fun `全局翻页模式批量应用覆盖所有书的模式`() = runBlocking {
        val dao = FakeBookPrefsDao()
        dao.upsert(BookPrefsEntity(bookId = 1, pageTurnMode = "NONE"))
        dao.upsert(BookPrefsEntity(bookId = 2, pageTurnMode = "SCROLL"))
        val repository = BookPrefsRepository(dao, FakeSettingsRepository(global))

        repository.applyGlobalPageTurnMode(PageTurnMode.SCROLL)

        dao.rows.forEach { row ->
            assertEquals("SCROLL", row.pageTurnMode)
        }
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
                it.copy(pageTurnMode = mode)
            }
        }
        override suspend fun delete(bookId: Long) {
            rows.removeAll { it.bookId == bookId }
        }
    }

    private class FakeSettingsRepository(
        initial: ReadingPreferences = ReadingPreferences(),
    ) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<ReadingPreferences> = state
        fun snapshot(): ReadingPreferences = state.value

        override suspend fun setFontSize(sizeSp: Float) = Unit
        override suspend fun setLineSpacing(multiplier: Float) = Unit
        override suspend fun setMarginLevel(level: Int) = Unit
        override suspend fun setMaxLineChars(chars: Int) = Unit
        override suspend fun setParagraphSpacingEm(spacingEm: Float) = Unit
        override suspend fun setLetterSpacingEm(spacingEm: Float) = Unit
        override suspend fun setTheme(theme: ReadingTheme) = Unit
        override suspend fun setCustomColors(backgroundArgb: Int?, textArgb: Int?) = Unit
        override suspend fun setDarkThemeOption(option: DarkThemeOption) = Unit
        override suspend fun setFontKey(fontKey: String) = Unit
        override suspend fun setDualPageMode(mode: DualPageMode) = Unit
        override suspend fun setWideScreenDualPage(enabled: Boolean) = Unit
        override suspend fun setAvoidCameraCutout(enabled: Boolean) = Unit
        override suspend fun setPageTurnMode(mode: PageTurnMode) = Unit
        override suspend fun setPageTurnHotspotRatio(ratio: Float) = Unit
        override suspend fun setMiddleTapAction(action: TapAction) = Unit
        override suspend fun setMiddleDoubleTapAction(action: TapAction) = Unit
        override suspend fun setVolumeKeyPagingEnabled(enabled: Boolean) = Unit
        override suspend fun setBrightnessGestureEnabled(enabled: Boolean) = Unit
        override suspend fun setSwipeGestureEnabled(enabled: Boolean) = Unit
        override suspend fun setKeepScreenOn(enabled: Boolean) = Unit
        override suspend fun setShowChapterTitle(enabled: Boolean) = Unit
        override suspend fun setShowPageProgress(enabled: Boolean) = Unit
        override suspend fun setShowPageNumber(enabled: Boolean) = Unit
        override suspend fun setShowBattery(enabled: Boolean) = Unit
        override suspend fun setShowTime(enabled: Boolean) = Unit
        override suspend fun setReaderBrightness(brightness: Float) = Unit
        override suspend fun setAutoPageEnabled(enabled: Boolean) = Unit
        override suspend fun setAutoPageMode(mode: AutoPageMode) = Unit
        override suspend fun setAutoPageIntervalSec(seconds: Int) = Unit
        override suspend fun setAutoPageSpeedPx(pxPerSecond: Float) = Unit
        override suspend fun setPanelScreenOff(enabled: Boolean) = Unit
        override suspend fun setBookshelfGridView(gridView: Boolean) = Unit
        override suspend fun setBookshelfSort(sort: com.llzx373.foldreader.core.data.settings.BookshelfSort) = Unit
        override suspend fun setCustomChapterRules(rules: List<String>) = Unit
        override suspend fun setAdCleanRules(rules: List<String>) = Unit
        override suspend fun setComicDirection(direction: ComicDirection) = Unit
        override suspend fun setComicDualPageCoverAlone(enabled: Boolean) = Unit
        override suspend fun setComicSpreadAutoDetect(enabled: Boolean) = Unit
        override suspend fun setComicFitMode(mode: ComicFitMode) = Unit
        override suspend fun setComicScrollGapDp(gapDp: Int) = Unit
    }
}