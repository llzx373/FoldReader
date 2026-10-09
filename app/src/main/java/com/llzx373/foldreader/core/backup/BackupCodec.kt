package com.llzx373.foldreader.core.backup

import com.llzx373.foldreader.core.backup.BackupManager.ImportResult
import com.llzx373.foldreader.core.comic.ComicContainer
import com.llzx373.foldreader.core.data.db.AnnotationEntity
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookPrefsDao
import com.llzx373.foldreader.core.data.db.BookPrefsEntity
import com.llzx373.foldreader.core.data.db.BookmarkEntity
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import com.llzx373.foldreader.core.data.db.ReadingSessionDao
import com.llzx373.foldreader.core.data.db.ReadingSessionEntity
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.AutoPageMode
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.TapAction
import com.llzx373.foldreader.core.data.settings.enumOrDefault
import com.llzx373.foldreader.core.format.clean.CleanToggles
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一本书在 zip 备份内的归档位置（v11 起导出）：[file] 是 `files/` 下书文件条目名，
 * [cover] 是 `covers/` 下封面条目名；都遵循 BackupArchive 的命名约定。
 */
data class BookArchiveRefs(val file: String? = null, val cover: String? = null)

/** zip 恢复钩子解析出的本地落盘位置：新建书籍行的 fileUri/coverPath 指向这里。 */
data class ResolvedBookFile(val fileUri: String, val coverPath: String?)

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
    /** M20 术语表（glossary 段）；null = 不导出（测试或旧装配点）。 */
    private val glossaryTermDao: com.llzx373.foldreader.core.data.db.GlossaryTermDao? = null,
    /** M28 生词本（vocabulary 段）；null = 不导出。 */
    private val wordEntryDao: com.llzx373.foldreader.core.data.db.WordEntryDao? = null,
    /** M29 章节摘要（summaries 段，只导出 done）；null = 不导出。 */
    private val chapterSummaryDao: com.llzx373.foldreader.core.data.db.ChapterSummaryDao? = null,
    /** M29 全书大纲（outlines 段）；null = 不导出。 */
    private val bookOutlineDao: com.llzx373.foldreader.core.data.db.BookOutlineDao? = null,
) {

    /**
     * 导出书架数据 + 阅读偏好。[archiveRefs] 由 zip 打包方提供：给出每本书在包内的
     * 归档条目名，写进该书的 `archiveFile`/`archiveCover` 字段；默认恒 null（纯 JSON）。
     */
    suspend fun exportJson(archiveRefs: (BookEntity) -> BookArchiveRefs? = { null }): JSONObject {
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
            .put("preferences", preferencesJson(prefs))

        var anyFilePacked = false
        val booksJson = JSONArray()
        books.forEach { book ->
            val bookJson = JSONObject()
                .put("title", book.title)
                .put("author", book.author ?: JSONObject.NULL)
                .put("fileUri", book.fileUri)
                .put("contentHash", book.contentHash)
                .put("format", book.format.name)
                .put("source", book.source.name)
                .put("totalChars", book.totalChars)
                .put("encoding", book.encoding)
                .put("importedAt", book.importedAt)
                .put("lastReadAt", book.lastReadAt ?: JSONObject.NULL)
                .put("groupName", book.groupName ?: JSONObject.NULL)
                .put("description", book.description ?: JSONObject.NULL)
                .put("publisher", book.publisher ?: JSONObject.NULL)
                .put("language", book.language ?: JSONObject.NULL)
                .put("pubDate", book.pubDate ?: JSONObject.NULL)
                .put("subjects", book.subjects ?: JSONObject.NULL)
                .put("identifier", book.identifier ?: JSONObject.NULL)
                .put("seriesName", book.seriesName ?: JSONObject.NULL)
                .put("seriesIndex", book.seriesIndex ?: JSONObject.NULL)
                // M17（v7 起）：题材标签与逐字段来源标记（「AI 生成」/用户锁定的语义随备份走）
                .put("genreTag", book.genreTag ?: JSONObject.NULL)
                .put("metaSource", book.metaSource)
                // M34（v10 起）：隐私锁「指定书籍隐藏」状态随备份走，恢复后仍是隐藏的
                .put("hidden", book.hidden)
                // v11 起：漫画容器信息随备份走——zip 恢复新建书籍时需要原样还原才能打开
                .put("comicContainer", book.comicContainer?.name ?: JSONObject.NULL)
                .put("comicPageCount", book.comicPageCount ?: JSONObject.NULL)

            // v11 起（zip 备份）：书文件/封面在包内的条目名；读不到文件的书不带 archiveFile
            val refs = archiveRefs(book)
            if (refs?.file != null) {
                bookJson.put("archiveFile", refs.file)
                anyFilePacked = true
            }
            if (refs?.cover != null) bookJson.put("archiveCover", refs.cover)

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
                                .put("createdAt", b.createdAt)
                                .put("pageIndex", b.pageIndex ?: JSONObject.NULL)
                                .put("anchorX", b.anchorX?.toDouble() ?: JSONObject.NULL)
                                .put("anchorY", b.anchorY?.toDouble() ?: JSONObject.NULL)
                                .put("anchorW", b.anchorW?.toDouble() ?: JSONObject.NULL)
                                .put("anchorH", b.anchorH?.toDouble() ?: JSONObject.NULL),
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
                                .put("updatedAt", a.updatedAt)
                                .put("pageIndex", a.pageIndex ?: JSONObject.NULL)
                                .put("regionX", a.regionX?.toDouble() ?: JSONObject.NULL)
                                .put("regionY", a.regionY?.toDouble() ?: JSONObject.NULL)
                                .put("regionW", a.regionW?.toDouble() ?: JSONObject.NULL)
                                .put("regionH", a.regionH?.toDouble() ?: JSONObject.NULL),
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
        root.put(
            "note",
            if (anyFilePacked) {
                "含书籍文件本体（zip 内 files/ 目录）与封面；偏移索引、分页缓存、清洗/压平产物等派生数据不包含，打开书籍时自动重建"
            } else {
                "不含书籍文件本体；偏移索引与分页缓存不包含，打开书籍时自动重建"
            },
        )
        // M20 术语表（v6 起）：全部层级整表导出。单书行的 ownerKey 是本机 bookId，
        // 换机恢复时按 contentHash 重映射（书没导入则跳过该行），所以附带 contentHash。
        glossaryTermDao?.let { dao ->
            val booksById = books.associateBy { it.id }
            val glossaryJson = JSONArray()
            dao.getAll().forEach { term ->
                val book = if (term.scope == com.llzx373.foldreader.core.data.db.GlossaryTermEntity.SCOPE_BOOK) {
                    booksById[term.ownerKey.toLongOrNull()]
                } else {
                    null
                }
                // 单书行找不到所属书（库内已无该书）不导出——恢复过去也是孤儿
                if (term.scope == com.llzx373.foldreader.core.data.db.GlossaryTermEntity.SCOPE_BOOK &&
                    book == null
                ) {
                    return@forEach
                }
                glossaryJson.put(
                    JSONObject()
                        .put("scope", term.scope)
                        .put("ownerKey", term.ownerKey)
                        .put("source", term.source)
                        .put("target", term.target)
                        .put("origin", term.origin)
                        .put("confirmed", term.confirmed)
                        .put("contentHash", book?.contentHash ?: JSONObject.NULL),
                )
            }
            root.put("glossary", glossaryJson)
        }
        // M28 生词本（v8 起）：词条 + 释义 + 例句 + 位置；bookId 按 contentHash 附带，
        // 换机恢复时重映射（书未导入则跳过该词条，与术语表同口径）
        wordEntryDao?.let { dao ->
            val booksById = books.associateBy { it.id }
            val vocabularyJson = JSONArray()
            dao.getAll().forEach { entry ->
                val book = booksById[entry.bookId] ?: return@forEach
                vocabularyJson.put(
                    JSONObject()
                        .put("word", entry.word)
                        .put("definition", entry.definition)
                        .put("contextSentence", entry.contextSentence)
                        .put("charOffset", entry.charOffset)
                        .put("source", entry.source)
                        .put("createdAt", entry.createdAt)
                        .put("contentHash", book.contentHash),
                )
            }
            root.put("vocabulary", vocabularyJson)
        }
        // M29 章节摘要（v9 起）：只导出 done 摘要（过程态不备份）；bookId 按 contentHash 附带，
        // 换机恢复时重映射（书未导入则跳过该行，与术语表同口径）
        chapterSummaryDao?.let { dao ->
            val booksById = books.associateBy { it.id }
            val summariesJson = JSONArray()
            dao.getAllDone().forEach { row ->
                val book = booksById[row.bookId] ?: return@forEach
                summariesJson.put(
                    JSONObject()
                        .put("lang", row.lang)
                        .put("unitIndex", row.unitIndex)
                        .put("unitKind", row.unitKind)
                        .put("unitTitle", row.unitTitle)
                        .put("summary", row.summary)
                        .put("model", row.model)
                        .put("updatedAt", row.updatedAt)
                        .put("contentHash", book.contentHash),
                )
            }
            root.put("summaries", summariesJson)
        }
        // M29 全书大纲（v9 起）：同上按 contentHash 附带重映射
        bookOutlineDao?.let { dao ->
            val booksById = books.associateBy { it.id }
            val outlinesJson = JSONArray()
            dao.getAll().forEach { row ->
                val book = booksById[row.bookId] ?: return@forEach
                outlinesJson.put(
                    JSONObject()
                        .put("lang", row.lang)
                        .put("outline", row.outline)
                        .put("summaryCount", row.summaryCount)
                        .put("model", row.model)
                        .put("updatedAt", row.updatedAt)
                        .put("contentHash", book.contentHash),
                )
            }
            root.put("outlines", outlinesJson)
        }
        return root
    }

    /**
     * 导入。[resolveBookFile] 是 zip 备份的「文件解析钩子」：给定书的 JSON，返回该书文件
     * 解压落盘后的本地位置；返回 null 表示包里没有这本书的文件。纯 JSON 旧备份走默认值
     * （恒 null），行为与 v10 及更早完全一致——未匹配的书进 missing 清单。
     */
    suspend fun importJson(
        text: String,
        resolveBookFile: (bookJson: JSONObject) -> ResolvedBookFile? = { null },
    ): ImportResult {
        val root = JSONObject(text)
        val version = root.optInt("version", 0)
        // 向前兼容：只拒绝认不出的版本（缺 version / 非法值），比本机新的备份照样尽力导入——
        // 认不出的字段按各自默认值落库（见下方各 optXxx 的兜底），不因为版本号更大就整份拒绝。
        check(version >= 1) { "备份版本不支持" }

        root.optJSONObject("preferences")?.let { applyPreferences(it) }

        var restoredBooks = 0
        var createdBooks = 0
        var restoredBookmarks = 0
        var restoredAnnotations = 0
        var restoredSessions = 0
        var restoredBookPrefs = 0
        val missing = mutableListOf<String>()

        val booksJson = root.optJSONArray("books") ?: JSONArray()
        for (i in 0 until booksJson.length()) {
            val bookJson = booksJson.getJSONObject(i)
            val title = bookJson.optString("title", "未知书名")
            val found = bookshelfRepository.findByContentHash(bookJson.optString("contentHash"))
            val local: BookEntity
            if (found == null) {
                // v11 zip 备份：本机没有这本书，但包里带了它的文件——按 JSON 元数据新建书籍行，
                // fileUri 指向刚解压的本地副本，之后与匹配上的书走同一条合并逻辑
                val resolved = resolveBookFile(bookJson)
                if (resolved == null) {
                    missing += title
                    continue
                }
                val entity = bookEntityFromJson(bookJson, resolved)
                val newId = bookshelfRepository.upsertBook(entity)
                local = entity.copy(id = newId)
                createdBooks++
            } else {
                local = found
            }
            restoredBooks++

            // v3 起备份分组：字段存在（含 null）时恢复/清除分组；v2 及更早无此字段，保持现状不动
            if (bookJson.has("groupName")) {
                val groupName = if (bookJson.isNull("groupName")) {
                    null
                } else {
                    bookJson.optString("groupName").trim().takeIf { it.isNotEmpty() }
                }
                bookshelfRepository.updateGroup(listOf(local.id), groupName)
            }

            // v10 起备份隐藏状态（M34 隐私锁）：字段缺失（旧备份）时保持本地值。
            // 不走单独 update——下方元数据 upsert 是整行覆盖，会把这个字段顶回去，所以并进去。
            val restoredHidden =
                if (bookJson.has("hidden")) bookJson.optBoolean("hidden") else local.hidden

            // v4 起备份 EPUB 扩展元数据，v7 起加 genreTag / metaSource（M17）；
            // 字段缺省（旧版本备份）时保持本地值
            val metaKeys = listOf(
                "description", "publisher", "language", "pubDate",
                "subjects", "identifier", "seriesName", "seriesIndex", "genreTag",
            )
            if (metaKeys.any { bookJson.has(it) } || bookJson.has("metaSource") || bookJson.has("hidden")) {
                fun opt(key: String, current: String?): String? =
                    if (!bookJson.has(key)) {
                        current
                    } else if (bookJson.isNull(key)) {
                        null
                    } else {
                        bookJson.optString(key).takeIf { it.isNotEmpty() }
                    }
                bookshelfRepository.upsertBook(
                    local.copy(
                        hidden = restoredHidden,
                        description = opt("description", local.description),
                        publisher = opt("publisher", local.publisher),
                        language = opt("language", local.language),
                        pubDate = opt("pubDate", local.pubDate),
                        subjects = opt("subjects", local.subjects),
                        identifier = opt("identifier", local.identifier),
                        seriesName = opt("seriesName", local.seriesName),
                        seriesIndex = opt("seriesIndex", local.seriesIndex),
                        genreTag = opt("genreTag", local.genreTag),
                        metaSource = when {
                            !bookJson.has("metaSource") -> local.metaSource
                            bookJson.isNull("metaSource") -> ""
                            else -> bookJson.optString("metaSource")
                        },
                    ),
                )
            }

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
            val existingBookmarkKeys = existingBookmarks.mapTo(HashSet()) { bookmarkKey(it) }
            bookJson.optJSONArray("bookmarks")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val b = arr.getJSONObject(j)
                    val entity = BookmarkEntity(
                        bookId = local.id,
                        charOffset = b.optLong("charOffset"),
                        chapterIndex = b.optInt("chapterIndex"),
                        snapshotText = b.optString("snapshotText"),
                        label = b.optString("label"),
                        createdAt = b.optLong("createdAt"),
                        pageIndex = b.optLongOrNull("pageIndex"),
                        anchorX = b.optFloatOrNull("anchorX"),
                        anchorY = b.optFloatOrNull("anchorY"),
                        anchorW = b.optFloatOrNull("anchorW"),
                        anchorH = b.optFloatOrNull("anchorH"),
                    )
                    if (!existingBookmarkKeys.add(bookmarkKey(entity))) continue
                    bookshelfRepository.addBookmark(entity)
                    restoredBookmarks++
                }
            }

            val existingAnnotations = bookshelfRepository.observeAnnotations(local.id).first()
            val existingAnnotationKeys = existingAnnotations.mapTo(HashSet()) { annotationKey(it) }
            bookJson.optJSONArray("annotations")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val a = arr.getJSONObject(j)
                    val entity = AnnotationEntity(
                        bookId = local.id,
                        startCharOffset = a.optLong("startCharOffset"),
                        endCharOffset = a.optLong("endCharOffset"),
                        selectedText = a.optString("selectedText"),
                        color = a.optLong("color"),
                        note = if (a.isNull("note")) null else a.optString("note"),
                        style = a.optString("style", AnnotationEntity.STYLE_HIGHLIGHT),
                        createdAt = a.optLong("createdAt"),
                        updatedAt = a.optLong("updatedAt"),
                        pageIndex = a.optLongOrNull("pageIndex"),
                        regionX = a.optFloatOrNull("regionX"),
                        regionY = a.optFloatOrNull("regionY"),
                        regionW = a.optFloatOrNull("regionW"),
                        regionH = a.optFloatOrNull("regionH"),
                    )
                    if (!existingAnnotationKeys.add(annotationKey(entity))) continue
                    bookshelfRepository.addAnnotation(entity)
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

        // M20 术语表（v6 起）：单书行按 contentHash 重映射 ownerKey（书未导入则跳过），
        // 其余层级原样落库；按 (scope, ownerKey, source) 去重 upsert
        var restoredGlossary = 0
        root.optJSONArray("glossary")?.let { arr ->
            val dao = glossaryTermDao
            if (dao != null) {
                val seen = HashSet<String>()
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    val scope = t.optString("scope")
                    var ownerKey = t.optString("ownerKey")
                    if (scope == com.llzx373.foldreader.core.data.db.GlossaryTermEntity.SCOPE_BOOK) {
                        val hash = if (t.isNull("contentHash")) null else t.optString("contentHash")
                        val book = hash?.let { bookshelfRepository.findByContentHash(it) } ?: continue
                        ownerKey = book.id.toString()
                    }
                    val source = t.optString("source")
                    if (source.isBlank()) continue
                    if (!seen.add("$scope$ownerKey$source")) continue
                    dao.upsert(
                        com.llzx373.foldreader.core.data.db.GlossaryTermEntity(
                            scope = scope,
                            ownerKey = ownerKey,
                            source = source,
                            target = t.optString("target"),
                            origin = t.optString(
                                "origin",
                                com.llzx373.foldreader.core.data.db.GlossaryTermEntity.ORIGIN_USER,
                            ),
                            confirmed = t.optBoolean("confirmed"),
                        ),
                    )
                    restoredGlossary++
                }
            }
        }

        // M28 生词本（v8 起）：按 contentHash 重映射 bookId（书未导入则跳过该词条），
        // 按 (bookId, word, charOffset) 去重避免重复恢复
        var restoredVocabulary = 0
        root.optJSONArray("vocabulary")?.let { arr ->
            val dao = wordEntryDao
            if (dao != null) {
                val existing = dao.getAll()
                    .mapTo(HashSet()) { "${it.bookId}${it.word}${it.charOffset}" }
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    val hash = if (v.isNull("contentHash")) null else v.optString("contentHash")
                    val book = hash?.let { bookshelfRepository.findByContentHash(it) } ?: continue
                    val word = v.optString("word")
                    if (word.isBlank()) continue
                    val entity = com.llzx373.foldreader.core.data.db.WordEntryEntity(
                        bookId = book.id,
                        word = word,
                        definition = v.optString("definition"),
                        contextSentence = v.optString("contextSentence"),
                        charOffset = v.optLong("charOffset"),
                        source = v.optString("source"),
                        createdAt = v.optLong("createdAt"),
                    )
                    if (!existing.add("${book.id}$word${entity.charOffset}")) continue
                    dao.insert(entity)
                    restoredVocabulary++
                }
            }
        }

        // M29 章节摘要（v9 起）：按 contentHash 重映射 bookId（书未导入则跳过），
        // 同 (bookId, lang, unitIndex) 已存在时不覆盖（本机已生成的优先）
        var restoredSummaries = 0
        root.optJSONArray("summaries")?.let { arr ->
            val dao = chapterSummaryDao
            if (dao != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val hash = if (s.isNull("contentHash")) null else s.optString("contentHash")
                    val book = hash?.let { bookshelfRepository.findByContentHash(it) } ?: continue
                    val summary = s.optString("summary")
                    if (summary.isBlank()) continue
                    val lang = s.optString("lang")
                    val unitIndex = s.optInt("unitIndex")
                    val existing = dao.getForBook(book.id, lang).any { it.unitIndex == unitIndex }
                    if (existing) continue
                    dao.upsert(
                        com.llzx373.foldreader.core.data.db.ChapterSummaryEntity(
                            bookId = book.id,
                            lang = lang,
                            unitIndex = unitIndex,
                            unitKind = s.optString("unitKind", "chapter"),
                            unitTitle = s.optString("unitTitle"),
                            status = com.llzx373.foldreader.core.data.db.ChapterSummaryEntity.STATUS_DONE,
                            summary = summary,
                            model = s.optString("model"),
                            updatedAt = s.optLong("updatedAt"),
                        ),
                    )
                    restoredSummaries++
                }
            }
        }

        // M29 全书大纲（v9 起）：同上重映射；已有同语言大纲不覆盖
        var restoredOutlines = 0
        root.optJSONArray("outlines")?.let { arr ->
            val dao = bookOutlineDao
            if (dao != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val hash = if (o.isNull("contentHash")) null else o.optString("contentHash")
                    val book = hash?.let { bookshelfRepository.findByContentHash(it) } ?: continue
                    val outline = o.optString("outline")
                    if (outline.isBlank()) continue
                    val lang = o.optString("lang")
                    if (dao.get(book.id, lang) != null) continue
                    dao.upsert(
                        com.llzx373.foldreader.core.data.db.BookOutlineEntity(
                            bookId = book.id,
                            lang = lang,
                            outline = outline,
                            summaryCount = o.optInt("summaryCount"),
                            model = o.optString("model"),
                            updatedAt = o.optLong("updatedAt"),
                        ),
                    )
                    restoredOutlines++
                }
            }
        }

        return ImportResult(
            restoredBooks = restoredBooks,
            missingBookTitles = missing,
            restoredBookmarks = restoredBookmarks,
            restoredAnnotations = restoredAnnotations,
            restoredSessions = restoredSessions,
            restoredBookPrefs = restoredBookPrefs,
            restoredGlossary = restoredGlossary,
            restoredVocabulary = restoredVocabulary,
            restoredSummaries = restoredSummaries,
            restoredOutlines = restoredOutlines,
            createdBooks = createdBooks,
        )
    }

    /**
     * 用 zip 备份里的元数据新建书籍行（v11）：正文/封面指向钩子解压出的本地文件。
     * 派生物字段（cleanedFilePath / comicLocalPath / contentPreparedAt）一律留空，
     * 打开书籍时自动重建，与「不打包派生物」的导出口径对应。
     */
    private fun bookEntityFromJson(bookJson: JSONObject, resolved: ResolvedBookFile): BookEntity {
        fun optStr(key: String): String? =
            if (!bookJson.has(key) || bookJson.isNull(key)) {
                null
            } else {
                bookJson.optString(key).takeIf { it.isNotEmpty() }
            }
        return BookEntity(
            title = bookJson.optString("title", "未知书名"),
            author = optStr("author"),
            fileUri = resolved.fileUri,
            contentHash = bookJson.optString("contentHash"),
            format = enumOrDefault(optStr("format"), BookFormat.TXT),
            totalChars = bookJson.optLong("totalChars"),
            encoding = optStr("encoding") ?: Charsets.UTF_8.name(),
            importedAt = bookJson.optLong("importedAt").takeIf { it > 0 } ?: System.currentTimeMillis(),
            lastReadAt = if (!bookJson.has("lastReadAt") || bookJson.isNull("lastReadAt")) {
                null
            } else {
                bookJson.optLong("lastReadAt")
            },
            groupName = optStr("groupName"),
            coverPath = resolved.coverPath,
            comicContainer = optStr("comicContainer")?.let { name ->
                runCatching { ComicContainer.valueOf(name) }.getOrNull()
            },
            comicPageCount = if (!bookJson.has("comicPageCount") || bookJson.isNull("comicPageCount")) {
                null
            } else {
                bookJson.optInt("comicPageCount")
            },
            description = optStr("description"),
            publisher = optStr("publisher"),
            language = optStr("language"),
            pubDate = optStr("pubDate"),
            subjects = optStr("subjects"),
            identifier = optStr("identifier"),
            seriesName = optStr("seriesName"),
            seriesIndex = optStr("seriesIndex"),
            genreTag = optStr("genreTag"),
            metaSource = bookJson.optString("metaSource"),
            hidden = bookJson.optBoolean("hidden"),
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
        .put("avoidCameraCutout", p.avoidCameraCutout)
        .put("pageTurnMode", p.pageTurnMode.name)
        .put("pageTurnHotspotRatio", p.pageTurnHotspotRatio.toDouble())
        .put("middleTapAction", p.middleTapAction.name)
        .put("middleDoubleTapAction", p.middleDoubleTapAction.name)
        .put("volumeKeyPagingEnabled", p.volumeKeyPagingEnabled)
        .put("brightnessGestureEnabled", p.brightnessGestureEnabled)
        .put("swipeGestureEnabled", p.swipeGestureEnabled)
        .put("swipeDistanceDp", p.swipeDistanceDp.toDouble())
        .put("swipeFlingVelocityDpPerSec", p.swipeFlingVelocityDpPerSec.toDouble())
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
        .put("panelScreenOff", p.panelScreenOff)
        .put("bookshelfGridView", p.bookshelfGridView)
        .put("bookshelfGridColumns", p.bookshelfGridColumns)
        .put("customChapterRules", JSONArray().apply { p.customChapterRules.forEach { put(it) } })
        .put("adCleanRules", JSONArray().apply { p.adCleanRules.forEach { put(it) } })
        .put("cleanLevel", p.cleanLevel.name)
        .put("cleanToggles", CleanToggles.encode(p.cleanToggles))
        .put("comicDirection", p.comicDirection.name)
        .put("comicDualPageCoverAlone", p.comicDualPageCoverAlone)
        .put("comicSpreadAutoDetect", p.comicSpreadAutoDetect)
        .put("comicFitMode", p.comicFitMode.name)
        .put("comicScrollGapDp", p.comicScrollGapDp)
        // M21：OCR 识别语言（无隐私含量的界面偏好；AI 服务配置与凭据依旧不进备份）
        .put("ocrRecLang", p.ocrRecLang)
        // M26：TTS 语速 / 音调全局默认
        .put("ttsSpeechRate", p.ttsSpeechRate.toDouble())
        .put("ttsPitch", p.ttsPitch.toDouble())

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
        if (json.has("avoidCameraCutout")) {
            settingsRepository.setAvoidCameraCutout(json.optBoolean("avoidCameraCutout"))
        }
        if (json.has("pageTurnMode")) {
            settingsRepository.setPageTurnMode(
                enumOrDefault(json.optString("pageTurnMode"), current.pageTurnMode),
            )
        }
        if (json.has("pageTurnHotspotRatio")) {
            settingsRepository.setPageTurnHotspotRatio(json.optDouble("pageTurnHotspotRatio").toFloat())
        }
        if (json.has("middleTapAction")) {
            settingsRepository.setMiddleTapAction(
                enumOrDefault(json.optString("middleTapAction"), TapAction.TOGGLE_MENU),
            )
        }
        if (json.has("middleDoubleTapAction")) {
            settingsRepository.setMiddleDoubleTapAction(
                enumOrDefault(json.optString("middleDoubleTapAction"), TapAction.TOGGLE_ZOOM),
            )
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
        if (json.has("swipeDistanceDp")) {
            settingsRepository.setSwipeDistanceDp(json.optDouble("swipeDistanceDp").toFloat())
        }
        if (json.has("swipeFlingVelocityDpPerSec")) {
            settingsRepository.setSwipeFlingVelocityDpPerSec(
                json.optDouble("swipeFlingVelocityDpPerSec").toFloat(),
            )
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
        if (json.has("panelScreenOff")) {
            settingsRepository.setPanelScreenOff(json.optBoolean("panelScreenOff"))
        }
        if (json.has("bookshelfGridView")) {
            settingsRepository.setBookshelfGridView(json.optBoolean("bookshelfGridView"))
        }
        if (json.has("bookshelfGridColumns")) {
            settingsRepository.setBookshelfGridColumns(json.optInt("bookshelfGridColumns"))
        }
        if (json.has("customChapterRules")) {
            settingsRepository.setCustomChapterRules(json.stringList("customChapterRules"))
        }
        if (json.has("adCleanRules")) {
            settingsRepository.setAdCleanRules(json.stringList("adCleanRules"))
        }
        // 旧备份没有这两项（或细项串长度对不上）时保持现有设置，不做覆盖
        if (json.has("cleanToggles")) {
            CleanToggles.decode(json.optString("cleanToggles"))?.let { toggles ->
                settingsRepository.setCleanProfile(
                    level = enumOrDefault(json.optString("cleanLevel"), ReadingPreferences().cleanLevel),
                    toggles = toggles,
                )
            }
        }
        if (json.has("comicDirection")) {
            settingsRepository.setComicDirection(
                enumOrDefault(json.optString("comicDirection"), ComicDirection.LTR),
            )
        }
        if (json.has("comicDualPageCoverAlone")) {
            settingsRepository.setComicDualPageCoverAlone(json.optBoolean("comicDualPageCoverAlone"))
        }
        if (json.has("comicSpreadAutoDetect")) {
            settingsRepository.setComicSpreadAutoDetect(json.optBoolean("comicSpreadAutoDetect"))
        }
        if (json.has("comicFitMode")) {
            settingsRepository.setComicFitMode(
                enumOrDefault(json.optString("comicFitMode"), ComicFitMode.FIT_PAGE),
            )
        }
        if (json.has("comicScrollGapDp")) {
            settingsRepository.setComicScrollGapDp(json.optInt("comicScrollGapDp"))
        }
        if (json.has("ocrRecLang")) {
            settingsRepository.setOcrRecLang(json.optString("ocrRecLang"))
        }
        if (json.has("ttsSpeechRate")) {
            settingsRepository.setTtsSpeechRate(json.optDouble("ttsSpeechRate").toFloat())
        }
        if (json.has("ttsPitch")) {
            settingsRepository.setTtsPitch(json.optDouble("ttsPitch").toFloat())
        }
    }

    private fun JSONObject.stringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    /**
     * 书签去重键。
     *
     * 页式（漫画 / PDF）书签的 [BookmarkEntity.charOffset] 恒为 0，只按字符偏移去重会让
     * 「同一页上的多条书签」在导入时互相吞掉，所以页式锚点必须改用页序号 + 页内坐标。
     */
    private fun bookmarkKey(b: BookmarkEntity): String =
        if (b.pageIndex != null) "p:${b.pageIndex}:${b.anchorX}:${b.anchorY}" else "t:${b.charOffset}"

    /** 标注去重键，理由同 [bookmarkKey]：页式高亮按区域去重，文本标注按字符区间。 */
    private fun annotationKey(a: AnnotationEntity): String =
        if (a.pageIndex != null) {
            "p:${a.pageIndex}:${a.regionX}:${a.regionY}:${a.regionW}:${a.regionH}"
        } else {
            "t:${a.startCharOffset}:${a.endCharOffset}:${a.selectedText}"
        }

    /** 可空数值列（页内坐标）：键缺失或显式 null 都还原成 null，老备份天然兼容。 */
    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optFloatOrNull(key: String): Float? =
        if (!has(key) || isNull(key)) null else optDouble(key).toFloat()

    /**
     * 每书偏好只导出会随书独立演化的字段；交互开关与显示项是全局的，在全局偏好段里导出一次。
     */
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
        .put("fontKey", p.fontKey)
        .put("pageTurnMode", p.pageTurnMode)
        .put("readerBrightness", p.readerBrightness.toDouble())
        .put("autoPageEnabled", p.autoPageEnabled)
        .put("autoPageMode", p.autoPageMode)
        .put("autoPageIntervalSec", p.autoPageIntervalSec)
        .put("autoPageSpeedPx", p.autoPageSpeedPx.toDouble())
        .put("panelScreenOff", p.panelScreenOff)
        .put("autoIndentEnabled", p.autoIndentEnabled)
        .put("normalizeWhitespaceEnabled", p.normalizeWhitespaceEnabled)
        .put("comicDirection", p.comicDirection)
        .put("comicFitMode", p.comicFitMode)
        .put("pdfReadingMode", p.pdfReadingMode ?: JSONObject.NULL)
        .put("ttsLang", p.ttsLang ?: JSONObject.NULL)
        .put("comicCropEnabled", p.comicCropEnabled)
        .put("comicCropBox", p.comicCropBox)

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
            fontKey = json.optString("fontKey", defaults.fontKey),
            pageTurnMode = json.optString("pageTurnMode", defaults.pageTurnMode),
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
            panelScreenOff = json.optBoolean("panelScreenOff", defaults.panelScreenOff),
            autoIndentEnabled = json.optBoolean("autoIndentEnabled", defaults.autoIndentEnabled),
            normalizeWhitespaceEnabled = json.optBoolean(
                "normalizeWhitespaceEnabled",
                defaults.normalizeWhitespaceEnabled,
            ),
            comicDirection = json.optString("comicDirection", defaults.comicDirection),
            comicFitMode = json.optString("comicFitMode", defaults.comicFitMode),
            pdfReadingMode = if (!json.has("pdfReadingMode") || json.isNull("pdfReadingMode")) {
                defaults.pdfReadingMode
            } else {
                json.optString("pdfReadingMode")
            },
            ttsLang = if (!json.has("ttsLang") || json.isNull("ttsLang")) {
                defaults.ttsLang
            } else {
                json.optString("ttsLang")
            },
            comicCropEnabled = json.optBoolean("comicCropEnabled", defaults.comicCropEnabled),
            comicCropBox = json.optString("comicCropBox", defaults.comicCropBox),
        )
    }
}
