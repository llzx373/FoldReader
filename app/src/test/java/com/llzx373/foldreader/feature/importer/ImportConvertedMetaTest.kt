package com.llzx373.foldreader.feature.importer

import com.llzx373.foldreader.core.data.db.BookFormat
import com.llzx373.foldreader.core.data.db.BookSource
import com.llzx373.foldreader.core.format.BookMeta
import com.llzx373.foldreader.core.format.CoverImage
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * importConverted 的元数据映射与封面落盘（internal 纯函数级）。
 * 端到端 Uri 路径在 JVM 单测中不可达（Uri.parse 返回 null），真机验证。
 */
class ImportConvertedMetaTest {

    private val useCase = ImportBookUseCase(
        bookshelfRepository = FakeRepo(),
        cleanedDir = File("unused"),
        openChannel = { error("不需要") },
        displayNameOf = { null },
        traditionalMap = { emptyMap() },
    )

    /** convertedEntity/writeCover 不触达 repository，最小实现即可。 */
    private class FakeRepo : com.llzx373.foldreader.core.data.repository.BookshelfRepository {
        override fun observeBookshelf() = throw UnsupportedOperationException()
        override fun observeBookshelfWithProgress() = throw UnsupportedOperationException()
        override suspend fun getBook(bookId: Long) = throw UnsupportedOperationException()
        override fun observeBook(bookId: Long) = throw UnsupportedOperationException()
        override suspend fun updateEncoding(bookId: Long, encoding: String) =
            throw UnsupportedOperationException()
        override suspend fun findByFileUri(fileUri: String) = throw UnsupportedOperationException()
        override suspend fun findByContentHash(contentHash: String) =
            throw UnsupportedOperationException()
        override suspend fun upsertBook(book: com.llzx373.foldreader.core.data.db.BookEntity) =
            throw UnsupportedOperationException()
        override suspend fun touchLastRead(bookId: Long, timestamp: Long) =
            throw UnsupportedOperationException()
        override suspend fun markContentPrepared(bookId: Long, timestamp: Long) =
            throw UnsupportedOperationException()
        override suspend fun deleteBooks(bookIds: List<Long>, deleteLocalData: Boolean) =
            throw UnsupportedOperationException()
        override fun observeGroupNames() = throw UnsupportedOperationException()
        override fun observeBookshelfWithProgressInGroup(groupName: String?) =
            throw UnsupportedOperationException()
        override suspend fun updateGroup(bookIds: List<Long>, groupName: String?) =
            throw UnsupportedOperationException()
        override suspend fun clearGroup(groupName: String) = throw UnsupportedOperationException()
        override fun observeProgress(bookId: Long) = throw UnsupportedOperationException()
        override suspend fun getProgress(bookId: Long) = throw UnsupportedOperationException()
        override suspend fun saveProgress(progress: com.llzx373.foldreader.core.data.db.ReadingProgressEntity) =
            throw UnsupportedOperationException()
        override suspend fun getChapters(bookId: Long) = throw UnsupportedOperationException()
        override fun observeChapters(bookId: Long) = throw UnsupportedOperationException()
        override suspend fun saveChapters(bookId: Long, chapters: List<com.llzx373.foldreader.core.format.Chapter>) =
            throw UnsupportedOperationException()
        override fun observeBookmarks(bookId: Long) = throw UnsupportedOperationException()
        override fun observeAllBookmarks() = throw UnsupportedOperationException()
        override suspend fun addBookmark(bookmark: com.llzx373.foldreader.core.data.db.BookmarkEntity) =
            throw UnsupportedOperationException()
        override suspend fun renameBookmark(bookmark: com.llzx373.foldreader.core.data.db.BookmarkEntity) =
            throw UnsupportedOperationException()
        override suspend fun deleteBookmark(id: Long) = throw UnsupportedOperationException()
        override fun observeAnnotations(bookId: Long) = throw UnsupportedOperationException()
        override fun observeAllAnnotations() = throw UnsupportedOperationException()
        override suspend fun addAnnotation(annotation: com.llzx373.foldreader.core.data.db.AnnotationEntity) =
            throw UnsupportedOperationException()
        override suspend fun updateAnnotation(annotation: com.llzx373.foldreader.core.data.db.AnnotationEntity) =
            throw UnsupportedOperationException()
        override suspend fun deleteAnnotation(id: Long) = throw UnsupportedOperationException()
        override suspend fun addReadingSession(bookId: Long, dayStartMs: Long, deltaMs: Long) =
            throw UnsupportedOperationException()
        override suspend fun getReadingSessionsBetween(startMs: Long, endMs: Long) =
            throw UnsupportedOperationException()
        override suspend fun getReadingDayCount(bookId: Long) = throw UnsupportedOperationException()
    }

    private val fullMeta = BookMeta(
        title = "全字段之书",
        author = "张三, 李四（译）",
        encoding = "UTF-8",
        byteSize = 100,
        description = "这是一段简介。",
        publisher = "测试出版社",
        language = "zh-CN",
        pubDate = "2020-01-02",
        subjects = listOf("科幻", "短篇"),
        identifier = "isbn:9787020002207",
        seriesName = "银河纪元",
        seriesIndex = "3",
    )

    @Test
    fun `EPUB 扩展元数据全字段映射入库实体`() {
        val book = useCase.convertedEntity(
            title = fullMeta.title,
            meta = fullMeta,
            uriKey = "content://test/book.epub",
            contentHash = "abc123",
            format = BookFormat.EPUB,
            source = BookSource.IMPORT,
            coverPath = "/covers/abc123.jpg",
        )

        assertEquals("全字段之书", book.title)
        assertEquals("张三, 李四（译）", book.author)
        assertEquals("这是一段简介。", book.description)
        assertEquals("测试出版社", book.publisher)
        assertEquals("zh-CN", book.language)
        assertEquals("2020-01-02", book.pubDate)
        assertEquals("科幻\n短篇", book.subjects)
        assertEquals("isbn:9787020002207", book.identifier)
        assertEquals("银河纪元", book.seriesName)
        assertEquals("3", book.seriesIndex)
        assertEquals("/covers/abc123.jpg", book.coverPath)
    }

    @Test
    fun `meta 为 null 时扩展字段全空`() {
        val book = useCase.convertedEntity(
            title = "未知书名",
            meta = null,
            uriKey = "content://test/book.epub",
            contentHash = "abc123",
            format = BookFormat.EPUB,
            source = BookSource.EXTERNAL,
            coverPath = null,
        )

        assertNull(book.description)
        assertNull(book.publisher)
        assertNull(book.subjects)
        assertNull(book.seriesName)
        assertNull(book.coverPath)
    }

    @Test
    fun `封面落盘命名为 contentHash 加扩展名且清掉旧扩展名`() {
        val dir = Files.createTempDirectory("foldreader-covers").toFile()
        File(dir, "abc123.png").writeBytes(byteArrayOf(1)) // 上一次提取的旧封面

        val path = useCase.writeCover(dir, "abc123", CoverImage(byteArrayOf(9, 8, 7), "jpg"))

        val cover = File(path)
        assertEquals("abc123.jpg", cover.name)
        assertTrue(byteArrayOf(9, 8, 7).contentEquals(cover.readBytes()))
        assertTrue(!File(dir, "abc123.png").exists())
    }
}
