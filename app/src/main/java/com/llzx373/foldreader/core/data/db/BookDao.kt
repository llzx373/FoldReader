package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class BookWithProgress(
    @Embedded val book: BookEntity,
    val charOffset: Long?,
    /** 漫画当前页（0 基）；文本为 null。 */
    val comicPage: Int?,
)

@Dao
interface BookDao {

    // 书架排序改由 ViewModel 按用户选择客户端排序，DAO 只保证稳定顺序（id），
    // 避免 lastReadAt 更新导致从阅读页返回时列表重排。
    @Query("SELECT * FROM books ORDER BY id")
    fun observeBookshelf(): Flow<List<BookEntity>>

    @Query(
        """
        SELECT books.*, reading_progress.charOffset AS charOffset,
               reading_progress.comicPage AS comicPage
        FROM books LEFT JOIN reading_progress ON reading_progress.bookId = books.id
        ORDER BY books.id
        """,
    )
    fun observeBookshelfWithProgress(): Flow<List<BookWithProgress>>

    @Query("SELECT DISTINCT groupName FROM books WHERE groupName IS NOT NULL ORDER BY groupName")
    fun observeGroupNames(): Flow<List<String>>

    @Query(
        """
        SELECT books.*, reading_progress.charOffset AS charOffset,
               reading_progress.comicPage AS comicPage
        FROM books LEFT JOIN reading_progress ON reading_progress.bookId = books.id
        WHERE (:groupName IS NULL AND books.groupName IS NULL) OR books.groupName = :groupName
        ORDER BY books.id
        """,
    )
    fun observeBookshelfWithProgressInGroup(groupName: String?): Flow<List<BookWithProgress>>

    @Query("UPDATE books SET groupName = :groupName WHERE id IN (:bookIds)")
    suspend fun updateGroup(bookIds: List<Long>, groupName: String?)

    @Query("UPDATE books SET groupName = NULL WHERE groupName = :groupName")
    suspend fun clearGroup(groupName: String)

    @Query("SELECT * FROM books WHERE id = :bookId")
    suspend fun getById(bookId: Long): BookEntity?

    /** 批量取：删书时按 id 逐个查询是 N+1，一次 IN 查询即可。 */
    @Query("SELECT * FROM books WHERE id IN (:bookIds)")
    suspend fun getByIds(bookIds: List<Long>): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :bookId")
    fun observeById(bookId: Long): Flow<BookEntity?>

    @Query("UPDATE books SET encoding = :encoding WHERE id = :bookId")
    suspend fun updateEncoding(bookId: Long, encoding: String)

    /**
     * 按源文件 URI 反查。
     *
     * 同一个 `fileUri` 可以对应多行（原版 + 清洗版），这里加 `ORDER BY id` 只是让「不知道
     * bookId 的调用方」拿到确定的一行；知道 bookId 的调用方应当直接用主键查询。
     */
    @Query("SELECT * FROM books WHERE fileUri = :fileUri ORDER BY id LIMIT 1")
    suspend fun getByFileUri(fileUri: String): BookEntity?

    @Query("SELECT * FROM books WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getByContentHash(contentHash: String): BookEntity?

    /**
     * 按清洗副本路径反查。
     *
     * 清洗产物是按**内容**命名并复用的，同一份文件可能被多行引用（同一个源文件的原版与清洗版
     * 用同一套规则重洗时会落到同一个文件上），所以删书前必须用它确认「还有没有人用」。
     */
    @Query("SELECT * FROM books WHERE cleanedFilePath = :cleanedFilePath LIMIT 1")
    suspend fun getByCleanedFilePath(cleanedFilePath: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun touchLastRead(bookId: Long, timestamp: Long)

    @Query("UPDATE books SET contentPreparedAt = :timestamp WHERE id = :bookId")
    suspend fun markContentPrepared(bookId: Long, timestamp: Long)

    /**
     * 回填漫画页数（rar/tar/7z 首次打开才知道真实页数）。
     *
     * 用 UPDATE 而不是 [upsert]：`INSERT OR REPLACE` 会先删旧行再插入，
     * 而 reading_progress 的外键是 NO_ACTION，已有进度时 REPLACE 会直接抛约束错误。
     */
    @Query("UPDATE books SET comicPageCount = :pageCount WHERE id = :bookId")
    suspend fun updateComicPageCount(bookId: Long, pageCount: Int)

    /** 回填封面路径（rar/tar/7z 的封面要等后台解压完才拿得到）。 */
    @Query("UPDATE books SET coverPath = :coverPath WHERE id = :bookId")
    suspend fun updateCoverPath(bookId: Long, coverPath: String?)

    /** 记录/清除「复制到本地」的页目录。 */
    @Query("UPDATE books SET comicLocalPath = :localPath WHERE id = :bookId")
    suspend fun updateComicLocalPath(bookId: Long, localPath: String?)

    /**
     * PDF 预热回填元数据。只填空值（COALESCE），不覆盖已有内容：
     * 传 null 表示"这条没读到"，不是"把它清掉"。
     */
    @Query(
        "UPDATE books SET title = COALESCE(:title, title), author = COALESCE(:author, author), " +
            "description = COALESCE(:description, description), subjects = COALESCE(:subjects, subjects) " +
            "WHERE id = :bookId",
    )
    suspend fun backfillPdfMetadata(
        bookId: Long,
        title: String?,
        author: String?,
        description: String?,
        subjects: String?,
    )

    /**
     * 记录压平产物（PDF 文本模式用；EPUB/FB2 在导入时就写好了）。
     * [cleanedFilePath] 为 null = 没有可读正文（扫描件），同时把 totalChars 归零。
     */
    @Query("UPDATE books SET cleanedFilePath = :cleanedFilePath, totalChars = :totalChars WHERE id = :bookId")
    suspend fun updateConvertedFile(bookId: Long, cleanedFilePath: String?, totalChars: Long)

    @Query("DELETE FROM books WHERE id IN (:bookIds)")
    suspend fun deleteByIds(bookIds: List<Long>)

    /**
     * AI 元数据补全回写（M17）：**只填空值**——目标列已有内容（非 NULL 非空串）一律不动，
     * 传 null 的列也不动；「哪些字段允许 AI 写」由调用方按 metaSource 快照先行决策
     * （见 `core/metadata/BookMetaSources.planAiMetadataWrite`），这里的 CASE WHEN 是
     * 决策之外的兜底，保证 AI 路径无论如何都覆盖不了已有值（含用户刚改完的竞态）。
     * [metaSource] 整体替换为决策后的新标记。
     */
    @Query(
        "UPDATE books SET " +
            "author = CASE WHEN :author IS NOT NULL AND (author IS NULL OR author = '') THEN :author ELSE author END, " +
            "description = CASE WHEN :description IS NOT NULL AND (description IS NULL OR description = '') THEN :description ELSE description END, " +
            "genreTag = CASE WHEN :genreTag IS NOT NULL AND (genreTag IS NULL OR genreTag = '') THEN :genreTag ELSE genreTag END, " +
            "metaSource = :metaSource WHERE id = :bookId",
    )
    suspend fun applyAiMetadata(
        bookId: Long,
        author: String?,
        description: String?,
        genreTag: String?,
        metaSource: String,
    )

    /**
     * 用户编辑元数据（M17）：三列无条件覆盖（允许清空），
     * [metaSource] 由调用方按「值发生变化的字段打 user 标」算出（BookMetaSources.planUserEdit）。
     */
    @Query(
        "UPDATE books SET author = :author, description = :description, " +
            "genreTag = :genreTag, metaSource = :metaSource WHERE id = :bookId",
    )
    suspend fun updateUserMetadata(
        bookId: Long,
        author: String?,
        description: String?,
        genreTag: String?,
        metaSource: String,
    )

    /**
     * 规则版按题材自动分组（M17）：有题材标签的书 `groupName` 落题材名（覆盖原分组），
     * 无标签的书保持原样。返回归入分组的本数。
     */
    @Query("UPDATE books SET groupName = genreTag WHERE genreTag IS NOT NULL AND genreTag != ''")
    suspend fun groupByGenreTag(): Int
}
