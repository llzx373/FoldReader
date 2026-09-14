package com.llzx373.foldreader.core.backup

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.DarkThemeOption
import com.llzx373.foldreader.core.data.settings.DualPageMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.ReadingTheme
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.enumOrDefault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书架数据 + 阅读偏好的本地备份（JSON，不含书籍文件本体）。
 * 导入时按 contentHash 匹配书籍：匹配上的恢复进度/书签/标注；
 * 未匹配的列入"文件缺失"清单，由用户自行重新导入原书文件。
 */
class BackupManager(
    private val context: Context,
    private val bookshelfRepository: BookshelfRepository,
    private val settingsRepository: SettingsRepository,
) {

    data class ImportResult(
        val restoredBooks: Int,
        val missingBookTitles: List<String>,
        val restoredBookmarks: Int,
        val restoredAnnotations: Int,
    )

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val books = bookshelfRepository.observeBookshelf().first()
        val bookmarks = bookshelfRepository.observeAllBookmarks().first().groupBy { it.bookId }
        val annotations = bookshelfRepository.observeAllAnnotations().first().groupBy { it.bookId }
        val prefs = settingsRepository.preferences.first()

        val root = JSONObject()
            .put("app", "FoldReader")
            .put("version", BACKUP_VERSION)
            .put("exportedAt", System.currentTimeMillis())
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
                                .put("createdAt", a.createdAt)
                                .put("updatedAt", a.updatedAt),
                        )
                    }
                },
            )
            booksJson.put(bookJson)
        }
        root.put("books", booksJson)

        val stream = context.contentResolver.openOutputStream(uri)
            ?: error("无法写入备份文件")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(root.toString()) }
    }

    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("无法读取备份文件")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(text)
        check(root.optInt("version", 0) == BACKUP_VERSION) { "备份版本不支持" }

        root.optJSONObject("preferences")?.let { applyPreferences(it) }

        var restoredBooks = 0
        var restoredBookmarks = 0
        var restoredAnnotations = 0
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
                            createdAt = a.optLong("createdAt"),
                            updatedAt = a.optLong("updatedAt"),
                        ),
                    )
                    restoredAnnotations++
                }
            }
        }

        ImportResult(
            restoredBooks = restoredBooks,
            missingBookTitles = missing,
            restoredBookmarks = restoredBookmarks,
            restoredAnnotations = restoredAnnotations,
        )
    }

    private fun preferencesJson(p: ReadingPreferences) = JSONObject()
        .put("fontSizeSp", p.fontSizeSp.toDouble())
        .put("lineSpacingMultiplier", p.lineSpacingMultiplier.toDouble())
        .put("marginLevel", p.marginLevel)
        .put("themeId", p.themeId.name)
        .put("darkThemeOption", p.darkThemeOption.name)
        .put("dualPageMode", p.dualPageMode.name)
        .put("pageTurnMode", p.pageTurnMode.name)
        .put("pageTurnHotspotRatio", p.pageTurnHotspotRatio.toDouble())
        .put("volumeKeyPagingEnabled", p.volumeKeyPagingEnabled)
        .put("keepScreenOn", p.keepScreenOn)
        .put("showChapterTitle", p.showChapterTitle)
        .put("showPageProgress", p.showPageProgress)
        .put("showBattery", p.showBattery)
        .put("showTime", p.showTime)
        .put("autoPageEnabled", p.autoPageEnabled)
        .put("autoPageMode", p.autoPageMode.name)
        .put("autoPageIntervalSec", p.autoPageIntervalSec)
        .put("autoPageSpeedPx", p.autoPageSpeedPx.toDouble())
        .put("bookshelfGridView", p.bookshelfGridView)

    private suspend fun applyPreferences(json: JSONObject) {
        val current = settingsRepository.preferences.first()
        if (json.has("fontSizeSp")) settingsRepository.setFontSize(json.optDouble("fontSizeSp").toFloat())
        if (json.has("lineSpacingMultiplier")) {
            settingsRepository.setLineSpacing(json.optDouble("lineSpacingMultiplier").toFloat())
        }
        if (json.has("marginLevel")) settingsRepository.setMarginLevel(json.optInt("marginLevel"))
        if (json.has("themeId")) {
            settingsRepository.setTheme(enumOrDefault(json.optString("themeId"), current.themeId))
        }
        if (json.has("darkThemeOption")) {
            settingsRepository.setDarkThemeOption(
                enumOrDefault(json.optString("darkThemeOption"), current.darkThemeOption),
            )
        }
        if (json.has("dualPageMode")) {
            settingsRepository.setDualPageMode(
                enumOrDefault(json.optString("dualPageMode"), current.dualPageMode),
            )
        }
        if (json.has("pageTurnMode")) {
            settingsRepository.setPageTurnMode(
                enumOrDefault(json.optString("pageTurnMode"), current.pageTurnMode),
            )
        }
        if (json.has("pageTurnHotspotRatio")) {
            settingsRepository.setPageTurnHotspotRatio(json.optDouble("pageTurnHotspotRatio").toFloat())
        }
        if (json.has("volumeKeyPagingEnabled")) {
            settingsRepository.setVolumeKeyPagingEnabled(json.optBoolean("volumeKeyPagingEnabled"))
        }
        if (json.has("keepScreenOn")) settingsRepository.setKeepScreenOn(json.optBoolean("keepScreenOn"))
        if (json.has("showChapterTitle")) {
            settingsRepository.setShowChapterTitle(json.optBoolean("showChapterTitle"))
        }
        if (json.has("showPageProgress")) {
            settingsRepository.setShowPageProgress(json.optBoolean("showPageProgress"))
        }
        if (json.has("showBattery")) settingsRepository.setShowBattery(json.optBoolean("showBattery"))
        if (json.has("showTime")) settingsRepository.setShowTime(json.optBoolean("showTime"))
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
        if (json.has("bookshelfGridView")) {
            settingsRepository.setBookshelfGridView(json.optBoolean("bookshelfGridView"))
        }
    }

    companion object {
        const val BACKUP_VERSION = 1
    }
}
