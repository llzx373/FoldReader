package com.llzx373.foldreader.core.translate

import androidx.room.Room
import com.llzx373.foldreader.core.data.db.FoldReaderDatabase
import com.llzx373.foldreader.core.data.db.GlossaryTermEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 术语仓库的系列层级（M22-2.5，真 Room 库）：
 * 系列术语跨卷共享、书 > 系列 > 全局的覆盖口径、未确认术语不注入。
 * 合并纯函数本身的规则见 [MergeGlossaryTest]。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GlossaryRepositoryTest {

    private fun openDb(): FoldReaderDatabase =
        Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FoldReaderDatabase::class.java,
        ).build()

    private fun term(
        scope: String,
        ownerKey: String,
        source: String,
        target: String,
        confirmed: Boolean = true,
    ) = GlossaryTermEntity(
        scope = scope,
        ownerKey = ownerKey,
        source = source,
        target = target,
        origin = if (confirmed) GlossaryTermEntity.ORIGIN_USER else GlossaryTermEntity.ORIGIN_AUTO,
        confirmed = confirmed,
    )

    @Test
    fun `系列术语对同系列不同书都注入`() = runBlocking {
        val db = openDb()
        val dao = db.glossaryTermDao()
        val repo = GlossaryRepository(dao)
        dao.upsert(term(GlossaryTermEntity.SCOPE_SERIES, "one piece", "ルフィ", "路飞"))

        // 同一 seriesKey 下两卷（不同 bookId）都能拿到系列术语
        assertEquals(
            listOf("ルフィ" to "路飞"),
            repo.mergedConfirmed(bookId = "1", seriesKey = "one piece"),
        )
        assertEquals(
            listOf("ルフィ" to "路飞"),
            repo.mergedConfirmed(bookId = "2", seriesKey = "one piece"),
        )
    }

    @Test
    fun `书级覆盖系列级`() = runBlocking {
        val db = openDb()
        val dao = db.glossaryTermDao()
        val repo = GlossaryRepository(dao)
        dao.upsert(term(GlossaryTermEntity.SCOPE_SERIES, "s", "甲", "系列译法"))
        dao.upsert(term(GlossaryTermEntity.SCOPE_BOOK, "7", "甲", "本书译法"))

        assertEquals(
            listOf("甲" to "本书译法"),
            repo.mergedConfirmed(bookId = "7", seriesKey = "s"),
        )
        // 同系列另一本书没有书级词条，仍用系列译法
        assertEquals(
            listOf("甲" to "系列译法"),
            repo.mergedConfirmed(bookId = "8", seriesKey = "s"),
        )
    }

    @Test
    fun `系列级覆盖全局级`() = runBlocking {
        val db = openDb()
        val dao = db.glossaryTermDao()
        val repo = GlossaryRepository(dao)
        dao.upsert(term(GlossaryTermEntity.SCOPE_GLOBAL, "", "魔法", "magic"))
        dao.upsert(term(GlossaryTermEntity.SCOPE_SERIES, "s", "魔法", "咒术"))

        assertEquals(
            listOf("魔法" to "咒术"),
            repo.mergedConfirmed(bookId = "1", seriesKey = "s"),
        )
    }

    @Test
    fun `未确认术语不注入确认后才生效`() = runBlocking {
        val db = openDb()
        val dao = db.glossaryTermDao()
        val repo = GlossaryRepository(dao)
        // 候选落表路径：origin=auto、confirmed=false
        repo.upsertCandidates(
            GlossaryTermEntity.SCOPE_SERIES,
            "s",
            listOf("ゾロ" to "索隆"),
        )

        assertEquals(emptyList<Pair<String, String>>(), repo.mergedConfirmed("1", "s"))

        val candidate = dao.getAll().single { it.scope == GlossaryTermEntity.SCOPE_SERIES }
        dao.setConfirmed(candidate.id, confirmed = true)
        assertEquals(listOf("ゾロ" to "索隆"), repo.mergedConfirmed("1", "s"))
    }

    @Test
    fun `不传 seriesKey 时不读系列层`() = runBlocking {
        val db = openDb()
        val dao = db.glossaryTermDao()
        val repo = GlossaryRepository(dao)
        dao.upsert(term(GlossaryTermEntity.SCOPE_SERIES, "s", "甲", "系列译法"))
        dao.upsert(term(GlossaryTermEntity.SCOPE_GLOBAL, "", "乙", "全局译法"))

        // 文本书链路（TranslateEngine）不传 seriesKey：只剩全局层
        assertEquals(listOf("乙" to "全局译法"), repo.mergedConfirmed("1"))
    }

    @Test
    fun `不同系列的术语互不可见`() = runBlocking {
        val db = openDb()
        val dao = db.glossaryTermDao()
        val repo = GlossaryRepository(dao)
        dao.upsert(term(GlossaryTermEntity.SCOPE_SERIES, "naruto", "甲", "N"))
        dao.upsert(term(GlossaryTermEntity.SCOPE_SERIES, "bleach", "乙", "B"))

        assertEquals(listOf("甲" to "N"), repo.mergedConfirmed("1", "naruto"))
        assertEquals(listOf("乙" to "B"), repo.mergedConfirmed("2", "bleach"))
    }
}
