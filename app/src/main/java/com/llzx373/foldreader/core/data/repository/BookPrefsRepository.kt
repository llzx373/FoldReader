package com.llzx373.foldreader.core.data.repository

import com.llzx373.foldreader.core.data.db.BookPrefsDao
import com.llzx373.foldreader.core.data.db.BookPrefsEntity
import com.llzx373.foldreader.core.data.db.toBookPrefsEntity
import com.llzx373.foldreader.core.data.db.toReadingPreferences
import com.llzx373.foldreader.core.data.settings.ComicDirection
import com.llzx373.foldreader.core.data.settings.ComicFitMode
import com.llzx373.foldreader.core.data.settings.PageTurnMode
import com.llzx373.foldreader.core.data.settings.ReadingPreferences
import com.llzx373.foldreader.core.data.settings.SettingsRepository
import com.llzx373.foldreader.core.data.settings.decodeCustomChapterRules
import com.llzx373.foldreader.core.data.settings.encodeCustomChapterRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

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

    /** 按书自定义章节规则（原始正则串列表）；未自定义（或尚未落库）时为空列表。 */
    fun observeChapterRules(bookId: Long): Flow<List<String>> =
        bookPrefsDao.observe(bookId).map { decodeCustomChapterRules(it?.chapterRules) }

    suspend fun chapterRules(bookId: Long): List<String> =
        decodeCustomChapterRules(bookPrefsDao.get(bookId)?.chapterRules)

    /** 空列表存空串，等价于"未自定义"。 */
    suspend fun setChapterRules(bookId: Long, rules: List<String>) {
        update(bookId) { it.copy(chapterRules = encodeCustomChapterRules(rules)) }
    }

    /** 全局翻页模式变更同步到所有已落库的书：覆盖每书模式。 */
    suspend fun applyGlobalPageTurnMode(mode: PageTurnMode) {
        bookPrefsDao.applyGlobalPageTurnMode(mode.name)
    }

    /** 全局漫画适应模式变更同步到所有已落库的书：覆盖每书模式。 */
    suspend fun applyGlobalComicFitMode(mode: ComicFitMode) {
        bookPrefsDao.applyGlobalComicFitMode(mode.name)
    }

    /** 全局漫画阅读方向变更同步到所有已落库的书：覆盖每书方向。 */
    suspend fun applyGlobalComicDirection(direction: ComicDirection) {
        bookPrefsDao.applyGlobalComicDirection(direction.name)
    }
}
