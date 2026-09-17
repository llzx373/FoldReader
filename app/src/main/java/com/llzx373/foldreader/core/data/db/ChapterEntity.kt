package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "chapterIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // 不再单独声明 Index("bookId")：主键 (bookId, chapterIndex) 的前缀已经覆盖它，
    // 重复索引只是纯写放大。
)
data class ChapterEntity(
    val bookId: Long,
    val chapterIndex: Int,
    val title: String,
    val charStart: Long,
    val charEnd: Long,
)

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getForBook(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    fun observeForBook(bookId: Long): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    /**
     * 覆盖式写入：先删后插必须在同一事务里，否则中途失败会留下半份目录
     * （重建目录 / 索引回填都会走到这里）。
     */
    @Transaction
    suspend fun replaceForBook(bookId: Long, chapters: List<ChapterEntity>) {
        deleteForBook(bookId)
        upsertAll(chapters)
    }
}
