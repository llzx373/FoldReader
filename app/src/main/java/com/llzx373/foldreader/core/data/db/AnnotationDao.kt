package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {

    @Query("SELECT * FROM annotations WHERE bookId = :bookId ORDER BY startCharOffset ASC")
    fun observeByBook(bookId: Long): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations ORDER BY bookId ASC, startCharOffset ASC")
    fun observeAll(): Flow<List<AnnotationEntity>>

    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Update
    suspend fun update(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM annotations WHERE bookId IN (:bookIds)")
    suspend fun deleteByBookIds(bookIds: List<Long>)

    /**
     * 删除某书笔记以 [notePrefix] 开头的批注（M30：AI 校对重跑前清掉上一轮的校对批注）。
     * 前缀按字面匹配：通配符先转义，否则前缀里的 %/_ 会吞掉不该删的批注。
     */
    suspend fun deleteByBookAndNotePrefix(bookId: Long, notePrefix: String) =
        deleteByNotePrefixEscaped(bookId, escapeLikeWildcards(notePrefix))

    @Query("DELETE FROM annotations WHERE bookId = :bookId AND note LIKE :notePrefix || '%' ESCAPE '\\'")
    suspend fun deleteByNotePrefixEscaped(bookId: Long, notePrefix: String)

    companion object {
        /** LIKE 前缀里的通配符（%/_）与转义符本身转义，与查询里的 ESCAPE '\' 配套。 */
        internal fun escapeLikeWildcards(prefix: String): String = buildString(prefix.length) {
            prefix.forEach { c ->
                if (c == '%' || c == '_' || c == '\\') append('\\')
                append(c)
            }
        }
    }
}
