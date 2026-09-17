package com.llzx373.foldreader.core.data.repository

import com.llzx373.foldreader.core.data.db.BookPrefsDao
import com.llzx373.foldreader.core.data.db.BookPrefsEntity
import com.llzx373.foldreader.core.data.db.toBookPrefsEntity
import com.llzx373.foldreader.core.data.db.toReadingPreferences
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * 每书阅读偏好：首次打开书籍时以当时的全局默认落库，之后读写均走 book_prefs。
 * 非书籍维度字段不在 BookPrefsEntity 中，观察时从全局偏好透传合并。
 */
class BookPrefsRepository(
    private val bookPrefsDao: BookPrefsDao,
    private val settingsRepository: SettingsRepository,
) {

    fun observe(bookId: Long): Flow<ReadingPreferences> = combine(
        bookPrefsDao.observe(bookId),
        settingsRepository.preferences,
    ) { book, global ->
        book?.toReadingPreferences(global) ?: global
    }

    suspend fun ensureInitialized(bookId: Long) {
        if (bookPrefsDao.get(bookId) == null) {
            bookPrefsDao.upsert(settingsRepository.preferences.first().toBookPrefsEntity(bookId))
        }
    }

    suspend fun update(bookId: Long, transform: (BookPrefsEntity) -> BookPrefsEntity) {
        val current = bookPrefsDao.get(bookId)
            ?: settingsRepository.preferences.first().toBookPrefsEntity(bookId)
        bookPrefsDao.upsert(transform(current))
    }

    /** 全局翻页模式变更同步到所有已落库的书：覆盖每书模式。 */
    suspend fun applyGlobalPageTurnMode(mode: PageTurnMode) {
        bookPrefsDao.applyGlobalPageTurnMode(mode.name)
    }
}
