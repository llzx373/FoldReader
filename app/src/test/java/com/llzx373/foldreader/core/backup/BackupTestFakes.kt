package com.llzx373.foldreader.core.backup

import com.llzx373.foldreader.core.ai.AiProtocol
import com.llzx373.foldreader.core.ai.AiTargetLang
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookPrefsDao
import com.llzx373.foldreader.core.data.db.BookPrefsEntity
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionDao
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.BookshelfSort
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.TapAction
import com.llzx373.foldreader.core.format.Chapter
import com.llzx373.foldreader.core.format.clean.CleanLevel
import com.llzx373.foldreader.core.format.clean.CleanToggles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * 备份相关单测共用的内存假货（从 BackupCodecTest 提升，zip 往返测试也要用）。
 */

internal class FakeSettingsRepository(
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
    override suspend fun setPageTurnHotspotRatio(ratio: Float) =
        update { copy(pageTurnHotspotRatio = ratio) }
    override suspend fun setMiddleTapAction(action: TapAction) =
        update { copy(middleTapAction = action) }
    override suspend fun setMiddleDoubleTapAction(action: TapAction) =
        update { copy(middleDoubleTapAction = action) }
    override suspend fun setVolumeKeyPagingEnabled(enabled: Boolean) =
        update { copy(volumeKeyPagingEnabled = enabled) }
    override suspend fun setBrightnessGestureEnabled(enabled: Boolean) =
        update { copy(brightnessGestureEnabled = enabled) }
    override suspend fun setSwipeGestureEnabled(enabled: Boolean) =
        update { copy(swipeGestureEnabled = enabled) }
    override suspend fun setSwipeDistanceDp(distanceDp: Float) =
        update { copy(swipeDistanceDp = distanceDp) }
    override suspend fun setSwipeFlingVelocityDpPerSec(velocityDpPerSec: Float) =
        update { copy(swipeFlingVelocityDpPerSec = velocityDpPerSec) }
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
    override suspend fun setPanelScreenOff(enabled: Boolean) =
        update { copy(panelScreenOff = enabled) }
    override suspend fun setBookshelfGridView(gridView: Boolean) =
        update { copy(bookshelfGridView = gridView) }
    override suspend fun setBookshelfGridColumns(columns: Int) =
        update { copy(bookshelfGridColumns = if (columns <= 0) 0 else columns.coerceIn(2, 5)) }
    override suspend fun setBookshelfSort(sort: BookshelfSort) =
        update { copy(bookshelfSort = sort) }
    override suspend fun setCustomChapterRules(rules: List<String>) =
        update { copy(customChapterRules = rules) }
    override suspend fun setAdCleanRules(rules: List<String>) =
        update { copy(adCleanRules = rules) }

    override suspend fun setCleanLevel(level: CleanLevel) = update {
        copy(cleanLevel = level, cleanToggles = CleanToggles.preset(level))
    }

    override suspend fun setCleanToggle(key: String, enabled: Boolean) {
        val entry = CleanToggles.ENTRIES.first { it.key == key }
        update {
            copy(cleanToggles = entry.set(cleanToggles, enabled), cleanLevel = CleanLevel.CUSTOM)
        }
    }

    override suspend fun setCleanProfile(level: CleanLevel, toggles: CleanToggles) =
        update { copy(cleanLevel = level, cleanToggles = toggles) }

    override suspend fun setComicDirection(direction: ComicDirection) =
        update { copy(comicDirection = direction) }

    override suspend fun setComicDualPageCoverAlone(enabled: Boolean) =
        update { copy(comicDualPageCoverAlone = enabled) }

    override suspend fun setComicSpreadAutoDetect(enabled: Boolean) =
        update { copy(comicSpreadAutoDetect = enabled) }

    override suspend fun setComicFitMode(mode: ComicFitMode) =
        update { copy(comicFitMode = mode) }

    override suspend fun setComicScrollGapDp(gapDp: Int) =
        update { copy(comicScrollGapDp = gapDp) }

    override suspend fun setAiEnabled(enabled: Boolean) =
        update { copy(aiEnabled = enabled) }

    override suspend fun setAiProtocol(protocol: AiProtocol) =
        update { copy(aiProtocol = protocol) }

    override suspend fun setAiBaseUrl(baseUrl: String) =
        update { copy(aiBaseUrl = baseUrl) }

    override suspend fun setAiModelGeneral(model: String) =
        update { copy(aiModelGeneral = model) }

    override suspend fun setAiModelTranslation(model: String) =
        update { copy(aiModelTranslation = model) }

    override suspend fun setAiModelVision(model: String) =
        update { copy(aiModelVision = model) }

    override suspend fun setAiTargetLang(targetLang: AiTargetLang) =
        update { copy(aiTargetLang = targetLang) }

    override suspend fun setAiChapterRuleConfirmed(confirmed: Boolean) =
        update { copy(aiChapterRuleConfirmed = confirmed) }

    override suspend fun setAiTranslationConfirmed(confirmed: Boolean) =
        update { copy(aiTranslationConfirmed = confirmed) }

    override suspend fun setAiExplainConfirmed(confirmed: Boolean) =
        update { copy(aiExplainConfirmed = confirmed) }

    override suspend fun setAiSummaryConfirmed(confirmed: Boolean) =
        update { copy(aiSummaryConfirmed = confirmed) }

    override suspend fun setAiQaConfirmed(confirmed: Boolean) =
        update { copy(aiQaConfirmed = confirmed) }

    override suspend fun setAiProofreadConfirmed(confirmed: Boolean) =
        update { copy(aiProofreadConfirmed = confirmed) }

    override suspend fun setAiComicTranslateConfirmed(confirmed: Boolean) =
        update { copy(aiComicTranslateConfirmed = confirmed) }

    override suspend fun confirmAiComicVisionForBook(bookId: Long) = Unit

    override suspend fun setAiCleanRecipeConfirmed(confirmed: Boolean) =
        update { copy(aiCleanRecipeConfirmed = confirmed) }

    override suspend fun setAiMetadataConfirmed(confirmed: Boolean) =
        update { copy(aiMetadataConfirmed = confirmed) }

    override suspend fun setTranslationViewHintShown(shown: Boolean) =
        update { copy(translationViewHintShown = shown) }

    override suspend fun setTtsSpeechRate(rate: Float) =
        update { copy(ttsSpeechRate = rate) }

    override suspend fun setTtsPitch(pitch: Float) =
        update { copy(ttsPitch = pitch) }

    override suspend fun setAiPricePerMillion(price: Double) =
        update { copy(aiPricePerMillion = price) }

    override suspend fun setOcrRecLang(modelId: String) =
        update { copy(ocrRecLang = modelId) }

    override suspend fun setOcrNnapiEnabled(enabled: Boolean) =
        update { copy(ocrNnapiEnabled = enabled) }

    override suspend fun setWebDavBaseUrl(baseUrl: String) =
        update { copy(webdavBaseUrl = baseUrl) }

    override suspend fun setWebDavUsername(username: String) =
        update { copy(webdavUsername = username) }

    override suspend fun setWebDavConfirmed(confirmed: Boolean) =
        update { copy(webdavConfirmed = confirmed) }

    override suspend fun setAutoBackupEnabled(enabled: Boolean) =
        update { copy(autoBackupEnabled = enabled) }

    override suspend fun setAutoBackupDirUri(treeUri: String) =
        update { copy(autoBackupDirUri = treeUri) }

    override suspend fun setAutoBackupKeepCount(keep: Int) =
        update { copy(autoBackupKeepCount = keep) }

    override suspend fun setDailyReadingGoalMinutes(minutes: Int) = Unit
    override suspend fun setAppLockEnabled(enabled: Boolean) = Unit
    override suspend fun setBookshelfSearchIndexEnabled(enabled: Boolean) = Unit
    override suspend fun setAutoBackupLastRunAt(timestamp: Long) =
        update { copy(autoBackupLastRunAt = timestamp) }

    private fun update(block: ReadingPreferences.() -> ReadingPreferences) {
        state.value = state.value.block()
    }
}

internal class FakeBookshelfRepository(
    val books: MutableList<BookEntity> = mutableListOf(),
) : BookshelfRepository {
    val progress = mutableListOf<ReadingProgressEntity>()
    val bookmarks = mutableListOf<BookmarkEntity>()
    val annotations = mutableListOf<AnnotationEntity>()
    val groupCalls = mutableListOf<Pair<List<Long>, String?>>()
    private var nextId = 10_000L

    override fun observeBookshelf(): Flow<List<BookEntity>> = flowOf(books.toList())
    override fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>> = flowOf(emptyList())
    override suspend fun getBook(bookId: Long): BookEntity? = books.find { it.id == bookId }
    override fun observeBook(bookId: Long): Flow<BookEntity?> = flowOf(books.find { it.id == bookId })
    override suspend fun updateEncoding(bookId: Long, encoding: String) = Unit
    override suspend fun findByFileUri(fileUri: String): BookEntity? =
        books.find { it.fileUri == fileUri }
    override suspend fun findByContentHash(contentHash: String): BookEntity? =
        books.find { it.contentHash == contentHash }

    /** id=0 时分配新 id（对齐 Room 自增主键），返回最终 id。 */
    override suspend fun upsertBook(book: BookEntity): Long {
        val id = if (book.id != 0L) book.id else nextId++
        books.removeAll { it.id == id }
        books += book.copy(id = id)
        return id
    }

    /** 备份恢复的定点更新（A4）：记录调用（分组断言用）并真实落值。 */
    data class RestoreCall(val bookId: Long, val groupName: String?, val hidden: Boolean)

    val restoreCalls = mutableListOf<RestoreCall>()

    override suspend fun restoreBookMetadata(
        bookId: Long,
        groupName: String?,
        hidden: Boolean,
        description: String?,
        publisher: String?,
        language: String?,
        pubDate: String?,
        subjects: String?,
        identifier: String?,
        seriesName: String?,
        seriesIndex: String?,
        genreTag: String?,
        metaSource: String,
    ) {
        restoreCalls += RestoreCall(bookId, groupName, hidden)
        books.replaceAll {
            if (it.id != bookId) {
                it
            } else {
                it.copy(
                    groupName = groupName,
                    hidden = hidden,
                    description = description,
                    publisher = publisher,
                    language = language,
                    pubDate = pubDate,
                    subjects = subjects,
                    identifier = identifier,
                    seriesName = seriesName,
                    seriesIndex = seriesIndex,
                    genreTag = genreTag,
                    metaSource = metaSource,
                )
            }
        }
    }
    override suspend fun touchLastRead(bookId: Long, timestamp: Long) = Unit
    override suspend fun markContentPrepared(bookId: Long, timestamp: Long) = Unit
    override suspend fun backfillPdfMetadata(
        bookId: Long,
        title: String?,
        author: String?,
        description: String?,
        subjects: String?,
    ) = Unit
    override suspend fun backfillComicInfo(
        bookId: Long,
        author: String?,
        seriesName: String?,
        seriesIndex: String?,
    ) = Unit
    override suspend fun updateComicPageCount(bookId: Long, pageCount: Int) = Unit
    override suspend fun updateCoverPath(bookId: Long, coverPath: String?) = Unit
    override suspend fun updateComicLocalPath(bookId: Long, localPath: String?) = Unit
    override suspend fun updateConvertedFile(bookId: Long, cleanedFilePath: String?, totalChars: Long) = Unit
    override suspend fun deleteBooks(bookIds: List<Long>, deleteLocalData: Boolean) = Unit
    override fun observeGroupNames(): Flow<List<String>> = flowOf(emptyList())
    override fun observeBookshelfWithProgressInGroup(
        groupName: String?,
    ): Flow<List<BookWithProgress>> = flowOf(emptyList())
    override suspend fun updateHidden(bookIds: List<Long>, hidden: Boolean) {
        books.replaceAll { if (it.id in bookIds) it.copy(hidden = hidden) else it }
    }

    override suspend fun updateGroup(bookIds: List<Long>, groupName: String?) {
        groupCalls += bookIds to groupName
    }
    override suspend fun clearGroup(groupName: String) = Unit
    override suspend fun applyAiMetadata(
        bookId: Long,
        author: String?,
        description: String?,
        genreTag: String?,
        metaSource: String,
    ) = Unit
    override suspend fun updateUserMetadata(
        bookId: Long,
        author: String?,
        description: String?,
        genreTag: String?,
        metaSource: String,
    ) = Unit
    override suspend fun groupBooksByGenreTag(): Int = 0

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
    override fun observePersonAppearances(bookId: Long): Flow<List<com.llzx373.foldreader.core.data.db.PersonAppearanceEntity>> = flowOf(emptyList())
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

    override suspend fun getReadingDayStarts(): List<Long> = emptyList()
}

internal class FakeBookPrefsDao : BookPrefsDao {
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
    override suspend fun applyGlobalComicFitMode(mode: String) {
        rows.replaceAll {
            it.copy(comicFitMode = mode)
        }
    }
    override suspend fun applyGlobalComicDirection(direction: String) {
        rows.replaceAll {
            it.copy(comicDirection = direction)
        }
    }
    override suspend fun delete(bookId: Long) {
        rows.removeAll { it.bookId == bookId }
    }
}

internal class FakeSessionDao : ReadingSessionDao {
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

    override suspend fun dayStartsWithReading(): List<Long> =
        rows.filter { it.durationMs > 0 }.map { it.dayStartMs }.distinct()
    override suspend fun countReadingDays(bookId: Long): Int =
        rows.filter { it.bookId == bookId }.size
}
