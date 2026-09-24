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
) {

    private val codec = BackupCodec(
        bookshelfRepository = bookshelfRepository,
        settingsRepository = settingsRepository,
        bookPrefsDao = bookPrefsDao,
        readingSessionDao = readingSessionDao,
        glossaryTermDao = glossaryTermDao,
    )

    data class ImportResult(
        val restoredBooks: Int,
        val missingBookTitles: List<String>,
        val restoredBookmarks: Int,
        val restoredAnnotations: Int,
        val restoredSessions: Int = 0,
        val restoredBookPrefs: Int = 0,
        val restoredGlossary: Int = 0,
    )

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
         * v6：新增术语表 `glossary` 段（全局/系列/单书全表，单书行附 contentHash 供换机重映射）。
         * v5：书签/标注增加页式锚点（页序号 + 归一化页内坐标），中间点击区动作入备份。
         * 导入侧对老版本仍然兼容——新字段缺失即按 null / 默认值处理。
         */
        const val BACKUP_VERSION = 6
    }
}
