package com.llzx373.foldreader.core.backup

import com.llzx373.foldreader.core.backup.BackupManager.ImportResult
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookPrefsDao
import com.llzx373.foldreader.core.data.db.BookPrefsEntity
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionDao
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.enumOrDefault
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 备份 JSON 的编解码：导出书架数据 + 阅读偏好，导入时按 contentHash 匹配书籍，
 * 匹配上的恢复进度/书签/标注/每书偏好/阅读统计，未匹配的列入"文件缺失"清单。
 * 偏移索引与分页缓存不参与备份，打开书籍时自动重建。
 */
class BackupCodec(
    private val bookshelfRepository: BookshelfRepository,
    private val settingsRepository: SettingsRepository,
    private val bookPrefsDao: BookPrefsDao,
    private val readingSessionDao: ReadingSessionDao,
) {

    suspend fun exportJson(): JSONObject {
        val books = bookshelfRepository.observeBookshelf().first()
        val bookmarks = bookshelfRepository.observeAllBookmarks().first().groupBy { it.bookId }
        val annotations = bookshelfRepository.observeAllAnnotations().first().groupBy { it.bookId }
        val bookPrefs = bookPrefsDao.getAll().associateBy { it.bookId }
        val sessions = readingSessionDao.getAll().groupBy { it.bookId }
        val prefs = settingsRepository.preferences.first()

        val root = JSONObject()
            .put("app", "FoldReader")
            .put("version", BackupManager.BACKUP_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("note", "不含书籍文件本体；偏移索引与分页缓存不包含，打开书籍时自动重建")
            .put("preferences", preferencesJson(prefs))

        val booksJson = JSONArray()
        books.forEach { book ->
            val bookJson = JSONObject()
                .put("title", book.title)
                .put("author", book.author ?: JSONObject.NULL)
                .put("fileUri", book.fileUri)
                .put("contentHash", book.contentHash)
                .put("format", book.format.name)
                .put("totalChars", book.totalChars)
                .put("encoding", book.encoding)
                .put("importedAt", book.importedAt)
                .put("lastReadAt", book.lastReadAt ?: JSONObject.NULL)

            val progress = bookshelfRepository.getProgress(book.id)
            bookJson.put(
                "progress",
                progress?.let {
                    JSONObject()
                        .put("charOffset", it.charOffset)
                        .put("chapterIndex", it.chapterIndex)
                        .put("totalReadingMillis", it.totalReadingMillis)
                        .put("firstReadAt", it.firstReadAt)
                        .put("updatedAt", it.updatedAt)
                } ?: JSONObject.NULL,
            )

            bookJson.put(
                "bookmarks",
                JSONArray().apply {
                    bookmarks[book.id].orEmpty().forEach { b ->
                        put(
                            JSONObject()
                                .put("charOffset", b.charOffset)
                                .put("chapterIndex", b.chapterIndex)
                                .put("snapshotText", b.snapshotText)
                                .put("label", b.label)
                                .put("createdAt", b.createdAt),
                        )
                    }
                },
            )
            bookJson.put(
                "annotations",
                JSONArray().apply {
                    annotations[book.id].orEmpty().forEach { a ->
                        put(
                            JSONObject()
                                .put("startCharOffset", a.startCharOffset)
                                .put("endCharOffset", a.endCharOffset)
                                .put("selectedText", a.selectedText)
                                .put("color", a.color)
                                .put("note", a.note ?: JSONObject.NULL)
                                .put("style", a.style)
                                .put("createdAt", a.createdAt)
                                .put("updatedAt", a.updatedAt),
                        )
                    }
                },
            )
            bookJson.put(
                "sessions",
                JSONArray().apply {
                    sessions[book.id].orEmpty().forEach { s ->
                        put(
                            JSONObject()
                                .put("dayStartMs", s.dayStartMs)
                                .put("durationMs", s.durationMs),
                        )
                    }
                },
            )
            bookPrefs[book.id]?.let { bookJson.put("bookPrefs", bookPrefsJson(it)) }
            booksJson.put(bookJson)
        }
        root.put("books", booksJson)
        return root
    }

    suspend fun importJson(text: String): ImportResult {
        val root = JSONObject(text)
        val version = root.optInt("version", 0)
        check(version in 1..BackupManager.BACKUP_VERSION) { "备份版本不支持" }

        root.optJSONObject("preferences")?.let { applyPreferences(it) }

        var restoredBooks = 0
        var restoredBookmarks = 0
        var restoredAnnotations = 0
        var restoredSessions = 0
        var restoredBookPrefs = 0
        val missing = mutableListOf<String>()

        val booksJson = root.optJSONArray("books") ?: JSONArray()
        for (i in 0 until booksJson.length()) {
            val bookJson = booksJson.getJSONObject(i)
            val title = bookJson.optString("title", "未知书名")
            val local = bookshelfRepository.findByContentHash(bookJson.optString("contentHash"))
            if (local == null) {
                missing += title
                continue
            }
            restoredBooks++

            bookJson.optJSONObject("progress")?.let { p ->
                val existing = bookshelfRepository.getProgress(local.id)
                if (existing == null || p.optLong("updatedAt") >= existing.updatedAt) {
                    bookshelfRepository.saveProgress(
                        ReadingProgressEntity(
                            bookId = local.id,
                            charOffset = p.optLong("charOffset"),
                            chapterIndex = p.optInt("chapterIndex"),
                            totalReadingMillis = p.optLong("totalReadingMillis"),
                            firstReadAt = p.optLong("firstReadAt"),
                            updatedAt = p.optLong("updatedAt"),
                        ),
                    )
                }
            }

            val existingBookmarks = bookshelfRepository.observeBookmarks(local.id).first()
            val existingOffsets = existingBookmarks.mapTo(HashSet()) { it.charOffset }
            bookJson.optJSONArray("bookmarks")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val b = arr.getJSONObject(j)
                    val offset = b.optLong("charOffset")
                    if (!existingOffsets.add(offset)) continue
                    bookshelfRepository.addBookmark(
                        BookmarkEntity(
                            bookId = local.id,
                            charOffset = offset,
                            chapterIndex = b.optInt("chapterIndex"),
                            snapshotText = b.optString("snapshotText"),
                            label = b.optString("label"),
                            createdAt = b.optLong("createdAt"),
                        ),
                    )
                    restoredBookmarks++
                }
            }

            val existingAnnotations = bookshelfRepository.observeAnnotations(local.id).first()
            val existingKeys = existingAnnotations.mapTo(HashSet()) {
                "${it.startCharOffset}:${it.endCharOffset}:${it.selectedText}"
            }
            bookJson.optJSONArray("annotations")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val a = arr.getJSONObject(j)
                    val start = a.optLong("startCharOffset")
                    val end = a.optLong("endCharOffset")
                    val selectedText = a.optString("selectedText")
                    if (!existingKeys.add("$start:$end:$selectedText")) continue
                    bookshelfRepository.addAnnotation(
                        AnnotationEntity(
                            bookId = local.id,
                            startCharOffset = start,
                            endCharOffset = end,
                            selectedText = selectedText,
                            color = a.optLong("color"),
                            note = if (a.isNull("note")) null else a.optString("note"),
                            style = a.optString("style", AnnotationEntity.STYLE_HIGHLIGHT),
                            createdAt = a.optLong("createdAt"),
                            updatedAt = a.optLong("updatedAt"),
                        ),
                    )
                    restoredAnnotations++
                }
            }

            bookJson.optJSONArray("sessions")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val s = arr.getJSONObject(j)
                    val dayStartMs = s.optLong("dayStartMs")
                    val durationMs = s.optLong("durationMs")
                    val existing = readingSessionDao.get(local.id, dayStartMs)
                    val merged = maxOf(existing?.durationMs ?: 0L, durationMs)
                    if (existing == null || merged != existing.durationMs) {
                        readingSessionDao.upsert(
                            ReadingSessionEntity(
                                bookId = local.id,
                                dayStartMs = dayStartMs,
                                durationMs = merged,
                            ),
                        )
                        restoredSessions++
                    }
                }
            }

            bookJson.optJSONObject("bookPrefs")?.let {
                bookPrefsDao.upsert(bookPrefsFromJson(local.id, it))
                restoredBookPrefs++
            }
        }

        return ImportResult(
            restoredBooks = restoredBooks,
            missingBookTitles = missing,
            restoredBookmarks = restoredBookmarks,
            restoredAnnotations = restoredAnnotations,
            restoredSessions = restoredSessions,
            restoredBookPrefs = restoredBookPrefs,
        )
    }

    private fun preferencesJson(p: ReadingPreferences) = JSONObject()
        .put("fontSizeSp", p.fontSizeSp.toDouble())
        .put("lineSpacingMultiplier", p.lineSpacingMultiplier.toDouble())
        .put("marginLevel", p.marginLevel)
        .put("maxLineChars", p.maxLineChars)
        .put("paragraphSpacingEm", p.paragraphSpacingEm.toDouble())
        .put("letterSpacingEm", p.letterSpacingEm.toDouble())
        .put("themeId", p.themeId.name)
        .put("customBackgroundArgb", p.customBackgroundArgb ?: JSONObject.NULL)
        .put("customTextArgb", p.customTextArgb ?: JSONObject.NULL)
        .put("darkThemeOption", p.darkThemeOption.name)
        .put("fontKey", p.fontKey)
        .put("dualPageMode", p.dualPageMode.name)
        .put("wideScreenDualPage", p.wideScreenDualPage)
        .put("dualRightPageDrop", p.dualRightPageDrop)
        .put("pageTurnMode", p.pageTurnMode.name)
        .put("pageTurnModeExplicit", p.pageTurnModeExplicit)
        .put("pageTurnHotspotRatio", p.pageTurnHotspotRatio.toDouble())
        .put("volumeKeyPagingEnabled", p.volumeKeyPagingEnabled)
        .put("brightnessGestureEnabled", p.brightnessGestureEnabled)
        .put("swipeGestureEnabled", p.swipeGestureEnabled)
        .put("keepScreenOn", p.keepScreenOn)
        .put("showChapterTitle", p.showChapterTitle)
        .put("showPageProgress", p.showPageProgress)
        .put("showPageNumber", p.showPageNumber)
        .put("showBattery", p.showBattery)
        .put("showTime", p.showTime)
        .put("readerBrightness", p.readerBrightness.toDouble())
        .put("autoPageEnabled", p.autoPageEnabled)
        .put("autoPageMode", p.autoPageMode.name)
        .put("autoPageIntervalSec", p.autoPageIntervalSec)
        .put("autoPageSpeedPx", p.autoPageSpeedPx.toDouble())
        .put("simulationDegraded", p.simulationDegraded)
        .put("panelScreenOff", p.panelScreenOff)
        .put("bookshelfGridView", p.bookshelfGridView)
        .put("customChapterRules", JSONArray().apply { p.customChapterRules.forEach { put(it) } })
        .put("adCleanRules", JSONArray().apply { p.adCleanRules.forEach { put(it) } })

    private suspend fun applyPreferences(json: JSONObject) {
        val current = settingsRepository.preferences.first()
        if (json.has("fontSizeSp")) settingsRepository.setFontSize(json.optDouble("fontSizeSp").toFloat())
        if (json.has("lineSpacingMultiplier")) {
            settingsRepository.setLineSpacing(json.optDouble("lineSpacingMultiplier").toFloat())
        }
        if (json.has("marginLevel")) settingsRepository.setMarginLevel(json.optInt("marginLevel"))
        if (json.has("maxLineChars")) settingsRepository.setMaxLineChars(json.optInt("maxLineChars"))
        if (json.has("paragraphSpacingEm")) {
            settingsRepository.setParagraphSpacingEm(json.optDouble("paragraphSpacingEm").toFloat())
        }
        if (json.has("letterSpacingEm")) {
            settingsRepository.setLetterSpacingEm(json.optDouble("letterSpacingEm").toFloat())
        }
        if (json.has("themeId")) {
            settingsRepository.setTheme(enumOrDefault(json.optString("themeId"), current.themeId))
        }
        if (json.has("customBackgroundArgb") || json.has("customTextArgb")) {
            val background = when {
                !json.has("customBackgroundArgb") -> current.customBackgroundArgb
                json.isNull("customBackgroundArgb") -> null
                else -> json.optInt("customBackgroundArgb")
            }
            val text = when {
                !json.has("customTextArgb") -> current.customTextArgb
                json.isNull("customTextArgb") -> null
                else -> json.optInt("customTextArgb")
            }
            settingsRepository.setCustomColors(background, text)
        }
        if (json.has("darkThemeOption")) {
            settingsRepository.setDarkThemeOption(
                enumOrDefault(json.optString("darkThemeOption"), current.darkThemeOption),
            )
        }
        if (json.has("fontKey")) settingsRepository.setFontKey(json.optString("fontKey"))
        if (json.has("dualPageMode")) {
            settingsRepository.setDualPageMode(
                enumOrDefault(json.optString("dualPageMode"), current.dualPageMode),
            )
        }
        if (json.has("wideScreenDualPage")) {
            settingsRepository.setWideScreenDualPage(json.optBoolean("wideScreenDualPage"))
        }
        if (json.has("dualRightPageDrop")) {
            settingsRepository.setDualRightPageDrop(json.optBoolean("dualRightPageDrop"))
        }
        if (json.has("pageTurnMode")) {
            settingsRepository.setPageTurnMode(
                enumOrDefault(json.optString("pageTurnMode"), current.pageTurnMode),
            )
        }
        if (json.has("pageTurnModeExplicit")) {
            settingsRepository.setPageTurnModeExplicit(json.optBoolean("pageTurnModeExplicit"))
        }
        if (json.has("pageTurnHotspotRatio")) {
            settingsRepository.setPageTurnHotspotRatio(json.optDouble("pageTurnHotspotRatio").toFloat())
        }
        if (json.has("volumeKeyPagingEnabled")) {
            settingsRepository.setVolumeKeyPagingEnabled(json.optBoolean("volumeKeyPagingEnabled"))
        }
        if (json.has("brightnessGestureEnabled")) {
            settingsRepository.setBrightnessGestureEnabled(json.optBoolean("brightnessGestureEnabled"))
        }
        if (json.has("swipeGestureEnabled")) {
            settingsRepository.setSwipeGestureEnabled(json.optBoolean("swipeGestureEnabled"))
        }
        if (json.has("keepScreenOn")) settingsRepository.setKeepScreenOn(json.optBoolean("keepScreenOn"))
        if (json.has("showChapterTitle")) {
            settingsRepository.setShowChapterTitle(json.optBoolean("showChapterTitle"))
        }
        if (json.has("showPageProgress")) {
            settingsRepository.setShowPageProgress(json.optBoolean("showPageProgress"))
        }
        if (json.has("showPageNumber")) {
            settingsRepository.setShowPageNumber(json.optBoolean("showPageNumber"))
        }
        if (json.has("showBattery")) settingsRepository.setShowBattery(json.optBoolean("showBattery"))
        if (json.has("showTime")) settingsRepository.setShowTime(json.optBoolean("showTime"))
        if (json.has("readerBrightness")) {
            settingsRepository.setReaderBrightness(json.optDouble("readerBrightness").toFloat())
        }
        if (json.has("autoPageEnabled")) {
            settingsRepository.setAutoPageEnabled(json.optBoolean("autoPageEnabled"))
        }
        if (json.has("autoPageMode")) {
            settingsRepository.setAutoPageMode(
                enumOrDefault(json.optString("autoPageMode"), AutoPageMode.INTERVAL),
            )
        }
        if (json.has("autoPageIntervalSec")) {
            settingsRepository.setAutoPageIntervalSec(json.optInt("autoPageIntervalSec"))
        }
        if (json.has("autoPageSpeedPx")) {
            settingsRepository.setAutoPageSpeedPx(json.optDouble("autoPageSpeedPx").toFloat())
        }
        if (json.has("simulationDegraded")) {
            settingsRepository.setSimulationDegraded(json.optBoolean("simulationDegraded"))
        }
        if (json.has("panelScreenOff")) {
            settingsRepository.setPanelScreenOff(json.optBoolean("panelScreenOff"))
        }
        if (json.has("bookshelfGridView")) {
            settingsRepository.setBookshelfGridView(json.optBoolean("bookshelfGridView"))
        }
        if (json.has("customChapterRules")) {
            settingsRepository.setCustomChapterRules(json.stringList("customChapterRules"))
        }
        if (json.has("adCleanRules")) {
            settingsRepository.setAdCleanRules(json.stringList("adCleanRules"))
        }
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun bookPrefsJson(p: BookPrefsEntity) = JSONObject()
        .put("fontSizeSp", p.fontSizeSp.toDouble())
        .put("lineSpacingMultiplier", p.lineSpacingMultiplier.toDouble())
        .put("marginLevel", p.marginLevel)
        .put("maxLineChars", p.maxLineChars)
        .put("paragraphSpacingEm", p.paragraphSpacingEm.toDouble())
        .put("letterSpacingEm", p.letterSpacingEm.toDouble())
        .put("themeId", p.themeId)
        .put("customBackgroundArgb", p.customBackgroundArgb ?: JSONObject.NULL)
        .put("customTextArgb", p.customTextArgb ?: JSONObject.NULL)
        .put("darkThemeOption", p.darkThemeOption)
        .put("fontKey", p.fontKey)
        .put("dualPageMode", p.dualPageMode)
        .put("pageTurnMode", p.pageTurnMode)
        .put("pageTurnModeExplicit", p.pageTurnModeExplicit)
        .put("pageTurnHotspotRatio", p.pageTurnHotspotRatio.toDouble())
        .put("volumeKeyPagingEnabled", p.volumeKeyPagingEnabled)
        .put("keepScreenOn", p.keepScreenOn)
        .put("showChapterTitle", p.showChapterTitle)
        .put("showPageProgress", p.showPageProgress)
        .put("showPageNumber", p.showPageNumber)
        .put("showBattery", p.showBattery)
        .put("showTime", p.showTime)
        .put("readerBrightness", p.readerBrightness.toDouble())
        .put("autoPageEnabled", p.autoPageEnabled)
        .put("autoPageMode", p.autoPageMode)
        .put("autoPageIntervalSec", p.autoPageIntervalSec)
        .put("autoPageSpeedPx", p.autoPageSpeedPx.toDouble())
        .put("simulationDegraded", p.simulationDegraded)
        .put("panelScreenOff", p.panelScreenOff)
        .put("autoIndentEnabled", p.autoIndentEnabled)

    private fun bookPrefsFromJson(bookId: Long, json: JSONObject): BookPrefsEntity {
        val defaults = BookPrefsEntity(bookId = bookId)
        return BookPrefsEntity(
            bookId = bookId,
            fontSizeSp = json.optDouble("fontSizeSp", defaults.fontSizeSp.toDouble()).toFloat(),
            lineSpacingMultiplier = json.optDouble(
                "lineSpacingMultiplier",
                defaults.lineSpacingMultiplier.toDouble(),
            ).toFloat(),
            marginLevel = json.optInt("marginLevel", defaults.marginLevel),
            maxLineChars = json.optInt("maxLineChars", defaults.maxLineChars),
            paragraphSpacingEm = json.optDouble(
                "paragraphSpacingEm",
                defaults.paragraphSpacingEm.toDouble(),
            ).toFloat(),
            letterSpacingEm = json.optDouble(
                "letterSpacingEm",
                defaults.letterSpacingEm.toDouble(),
            ).toFloat(),
            themeId = json.optString("themeId", defaults.themeId),
            customBackgroundArgb = if (json.isNull("customBackgroundArgb")) {
                defaults.customBackgroundArgb
            } else {
                json.optInt("customBackgroundArgb")
            },
            customTextArgb = if (json.isNull("customTextArgb")) {
                defaults.customTextArgb
            } else {
                json.optInt("customTextArgb")
            },
            darkThemeOption = json.optString("darkThemeOption", defaults.darkThemeOption),
            fontKey = json.optString("fontKey", defaults.fontKey),
            dualPageMode = json.optString("dualPageMode", defaults.dualPageMode),
            pageTurnMode = json.optString("pageTurnMode", defaults.pageTurnMode),
            pageTurnModeExplicit = json.optBoolean(
                "pageTurnModeExplicit",
                defaults.pageTurnModeExplicit,
            ),
            pageTurnHotspotRatio = json.optDouble(
                "pageTurnHotspotRatio",
                defaults.pageTurnHotspotRatio.toDouble(),
            ).toFloat(),
            volumeKeyPagingEnabled = json.optBoolean(
                "volumeKeyPagingEnabled",
                defaults.volumeKeyPagingEnabled,
            ),
            keepScreenOn = json.optBoolean("keepScreenOn", defaults.keepScreenOn),
            showChapterTitle = json.optBoolean("showChapterTitle", defaults.showChapterTitle),
            showPageProgress = json.optBoolean("showPageProgress", defaults.showPageProgress),
            showPageNumber = json.optBoolean("showPageNumber", defaults.showPageNumber),
            showBattery = json.optBoolean("showBattery", defaults.showBattery),
            showTime = json.optBoolean("showTime", defaults.showTime),
            readerBrightness = json.optDouble(
                "readerBrightness",
                defaults.readerBrightness.toDouble(),
            ).toFloat(),
            autoPageEnabled = json.optBoolean("autoPageEnabled", defaults.autoPageEnabled),
            autoPageMode = json.optString("autoPageMode", defaults.autoPageMode),
            autoPageIntervalSec = json.optInt("autoPageIntervalSec", defaults.autoPageIntervalSec),
            autoPageSpeedPx = json.optDouble(
                "autoPageSpeedPx",
                defaults.autoPageSpeedPx.toDouble(),
            ).toFloat(),
            simulationDegraded = json.optBoolean("simulationDegraded", defaults.simulationDegraded),
            panelScreenOff = json.optBoolean("panelScreenOff", defaults.panelScreenOff),
            autoIndentEnabled = json.optBoolean("autoIndentEnabled", defaults.autoIndentEnabled),
        )
    }
}
