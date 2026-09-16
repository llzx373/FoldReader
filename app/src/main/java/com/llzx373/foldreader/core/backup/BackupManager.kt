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
) {

    private val codec = BackupCodec(
        bookshelfRepository = bookshelfRepository,
        settingsRepository = settingsRepository,
        bookPrefsDao = bookPrefsDao,
        readingSessionDao = readingSessionDao,
    )

    data class ImportResult(
        val restoredBooks: Int,
        val missingBookTitles: List<String>,
        val restoredBookmarks: Int,
        val restoredAnnotations: Int,
        val restoredSessions: Int = 0,
        val restoredBookPrefs: Int = 0,
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
        const val BACKUP_VERSION = 4
    }
}
