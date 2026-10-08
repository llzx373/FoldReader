package com.llzx373.foldreader.core.backup

import android.content.Context
import android.net.Uri
import com.llzx373.foldreader.core.data.db.BookPrefsDao
import com.llzx373.foldreader.core.data.db.ReadingSessionDao
import com.llzx373.foldreader.core.data.repository.BookshelfRepository
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书架数据 + 阅读偏好的本地备份（JSON，不含书籍文件本体）。
 * 导入时按 contentHash 匹配书籍：匹配上的恢复进度/书签/标注/每书偏好/阅读统计；
 * 未匹配的列入"文件缺失"清单，由用户自行重新导入原书文件。
 */
class BackupManager(
    private val context: Context,
    bookshelfRepository: BookshelfRepository,
    settingsRepository: SettingsRepository,
    bookPrefsDao: BookPrefsDao,
    readingSessionDao: ReadingSessionDao,
    glossaryTermDao: com.llzx373.foldreader.core.data.db.GlossaryTermDao? = null,
    wordEntryDao: com.llzx373.foldreader.core.data.db.WordEntryDao? = null,
    chapterSummaryDao: com.llzx373.foldreader.core.data.db.ChapterSummaryDao? = null,
    bookOutlineDao: com.llzx373.foldreader.core.data.db.BookOutlineDao? = null,
) {

    private val codec = BackupCodec(
        bookshelfRepository = bookshelfRepository,
        settingsRepository = settingsRepository,
        bookPrefsDao = bookPrefsDao,
        readingSessionDao = readingSessionDao,
        glossaryTermDao = glossaryTermDao,
        wordEntryDao = wordEntryDao,
        chapterSummaryDao = chapterSummaryDao,
        bookOutlineDao = bookOutlineDao,
    )

    data class ImportResult(
        val restoredBooks: Int,
        val missingBookTitles: List<String>,
        val restoredBookmarks: Int,
        val restoredAnnotations: Int,
        val restoredSessions: Int = 0,
        val restoredBookPrefs: Int = 0,
        val restoredGlossary: Int = 0,
        val restoredVocabulary: Int = 0,
        val restoredSummaries: Int = 0,
        val restoredOutlines: Int = 0,
    )

    /** 恢复前预览（M25）：只解析元信息不落库；非备份文件/版本不认时抛异常。 */
    data class BackupPreview(
        val version: Int,
        val bookCount: Int,
        val exportedAt: Long,
    )

    /** 导出为 JSON 文本：WebDAV 上传与本地自动备份共用，与 [exportTo] 同一编解码路径。 */
    suspend fun exportJsonText(): String = withContext(Dispatchers.IO) {
        codec.exportJson().toString()
    }

    /** 从 JSON 文本导入：WebDAV 下载恢复走这里，与 [importFrom] 同一编解码路径。 */
    suspend fun importFromText(text: String): ImportResult = withContext(Dispatchers.IO) {
        codec.importJson(text)
    }

    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        val root = codec.exportJson()
        val stream = context.contentResolver.openOutputStream(uri)
            ?: error("无法写入备份文件")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(root.toString()) }
    }

    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("无法读取备份文件")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        codec.importJson(text)
    }

    companion object {
        /**
         * v10：书籍新增 `hidden`（M34 隐私锁：指定书籍隐藏状态随备份走，恢复后仍隐藏）。
         * v9：新增章节摘要 `summaries` 与全书大纲 `outlines` 段（M29，与术语表同口径：
         * 按 contentHash 重映射来源书，只备份 done 摘要）。
         * v8：新增生词本 `vocabulary` 段（M28：词条/释义/上下文例句/位置，按 contentHash 重映射来源书）。
         * v7：书籍新增 `genreTag` / `metaSource`（M17 题材标签与「AI 生成」/用户锁定标记）。
         * v6：新增术语表 `glossary` 段（全局/系列/单书全表，单书行附 contentHash 供换机重映射）。
         * v5：书签/标注增加页式锚点（页序号 + 归一化页内坐标），中间点击区动作入备份。
         * 导入侧对老版本仍然兼容——新字段缺失即按 null / 默认值处理。
         */
        const val BACKUP_VERSION = 10

        /** 解析备份元信息供恢复前预览（M25）；不碰数据库，纯函数可测。 */
        fun preview(text: String): BackupPreview {
            val root = org.json.JSONObject(text)
            val version = root.optInt("version", 0)
            require(version >= 1) { "备份版本不支持" }
            return BackupPreview(
                version = version,
                bookCount = root.optJSONArray("books")?.length() ?: 0,
                exportedAt = root.optLong("exportedAt"),
            )
        }
    }
}
