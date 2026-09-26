package com.llzx373.foldreader.core.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * M17 元数据 DAO 行为（真 Room 库，Robolectric）：
 * AI 回写只填空值、用户编辑无条件覆盖、按题材分组只动有标签的书。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BookDaoMetadataTest {

    private fun openDb(): FoldReaderDatabase =
        Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            FoldReaderDatabase::class.java,
        ).build()

    private fun book(id: Long, genreTag: String? = null, groupName: String? = null) = BookEntity(
        id = id,
        title = "书$id",
        author = null,
        fileUri = "content://book/$id",
        contentHash = "hash$id",
        format = BookFormat.TXT,
        totalChars = 1000,
        encoding = "UTF-8",
        importedAt = id,
        lastReadAt = null,
        groupName = groupName,
        genreTag = genreTag,
    )

    @Test
    fun `AI 回写只填空值且整体替换 metaSource`() = runBlocking {
        val db = openDb()
        val dao = db.bookDao()
        dao.upsert(book(1))
        dao.upsert(book(2))
        dao.applyAiMetadata(1, author = "AI 作者", description = null, genreTag = "科幻", metaSource = "author:ai,genre:ai")

        val filled = dao.getById(1)!!
        assertEquals("AI 作者", filled.author)
        assertNull(filled.description)
        assertEquals("科幻", filled.genreTag)
        assertEquals("author:ai,genre:ai", filled.metaSource)

        // 已有值不被 COALESCE 覆盖（决策之外的兜底）
        dao.applyAiMetadata(1, author = "覆盖尝试", description = "补简介", genreTag = null, metaSource = "author:ai,desc:ai,genre:ai")
        val guarded = dao.getById(1)!!
        assertEquals("AI 作者", guarded.author)
        assertEquals("补简介", guarded.description)
        assertEquals("科幻", guarded.genreTag)
        // 未动别的书
        assertNull(dao.getById(2)!!.author)
        db.close()
    }

    @Test
    fun `用户编辑无条件覆盖且允许清空`() = runBlocking {
        val db = openDb()
        val dao = db.bookDao()
        dao.upsert(book(1).copy(author = "旧作者", description = "旧简介", genreTag = "玄幻"))
        dao.updateUserMetadata(1, author = "新作者", description = null, genreTag = "都市", metaSource = "desc:user,genre:user")

        val updated = dao.getById(1)!!
        assertEquals("新作者", updated.author)
        assertNull(updated.description) // 允许清空
        assertEquals("都市", updated.genreTag)
        assertEquals("desc:user,genre:user", updated.metaSource)
        db.close()
    }

    @Test
    fun `按题材分组只动有标签的书且返回归入本数`() = runBlocking {
        val db = openDb()
        val dao = db.bookDao()
        dao.upsert(book(1, genreTag = "科幻"))
        dao.upsert(book(2, genreTag = "科幻", groupName = "旧分组"))
        dao.upsert(book(3, genreTag = "都市"))
        dao.upsert(book(4)) // 无标签
        dao.upsert(book(5, genreTag = "")) // 空串视同无标签

        assertEquals(3, dao.groupByGenreTag())
        assertEquals("科幻", dao.getById(1)!!.groupName)
        assertEquals("科幻", dao.getById(2)!!.groupName) // 覆盖原分组
        assertEquals("都市", dao.getById(3)!!.groupName)
        assertNull(dao.getById(4)!!.groupName)
        assertNull(dao.getById(5)!!.groupName)
        // 分组名进入书架分组清单（SQLite BINARY 排序按码位：科 0x79D1 < 都 0x90FD）
        assertEquals(listOf("科幻", "都市"), dao.observeGroupNames().first())
        db.close()
    }
}
