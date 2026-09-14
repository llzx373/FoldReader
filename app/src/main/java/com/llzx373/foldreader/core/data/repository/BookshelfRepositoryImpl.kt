package com.llzx373.foldreader.core.data.repository

import com.llzx373.foldreader.core.data.db.BookDao
import com.llzx373.foldreader.core.data.db.BookEntity
import com.llzx373.foldreader.core.data.db.BookWithProgress
import com.llzx373.foldreader.core.data.db.ReadingProgressDao
import com.llzx373.foldreader.core.data.db.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

class BookshelfRepositoryImpl(
    private val bookDao: BookDao,
    private val progressDao: ReadingProgressDao,
) : BookshelfRepository {

    override fun observeBookshelf(): Flow<List<BookEntity>> = bookDao.observeBookshelf()

    override fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>> =
        bookDao.observeBookshelfWithProgress()

    override suspend fun getBook(bookId: Long): BookEntity? = bookDao.getById(bookId)

    override suspend fun findByFileUri(fileUri: String): BookEntity? =
        bookDao.getByFileUri(fileUri)

    override suspend fun findByContentHash(contentHash: String): BookEntity? =
        bookDao.getByContentHash(contentHash)

    override suspend fun upsertBook(book: BookEntity): Long = bookDao.upsert(book)

    override suspend fun touchLastRead(bookId: Long, timestamp: Long) =
        bookDao.touchLastRead(bookId, timestamp)

    override suspend fun deleteBooks(bookIds: List<Long>) = bookDao.deleteByIds(bookIds)

    override fun observeProgress(bookId: Long): Flow<ReadingProgressEntity?> =
        progressDao.observe(bookId)

    override suspend fun getProgress(bookId: Long): ReadingProgressEntity? =
        progressDao.get(bookId)

    override suspend fun saveProgress(progress: ReadingProgressEntity) =
        progressDao.upsert(progress)
}
