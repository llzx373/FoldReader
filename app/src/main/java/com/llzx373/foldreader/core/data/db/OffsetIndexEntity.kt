package com.llzx373.foldreader.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Entity(
    tableName = "offset_index",
    primaryKeys = ["bookId", "chunkIndex"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // 不再单独声明 Index("bookId")：主键 (bookId, chunkIndex) 的前缀已经覆盖它。
)
data class OffsetIndexEntity(
    val bookId: Long,
    val chunkIndex: Int,
    /** 块起点的字节偏移（变长编码下不可推导，必须持久化）。v12 起由 `charOffset` 更名而来。 */
    val byteOffset: Long,
    /**
     * 块起点的字符偏移。**不能**假设它等于 `chunkIndex * blockChars`：
     * 索引器的输出缓冲只剩 1 个槽位、而下一个字符是需要 2 槽的增补字符（emoji、CJK 扩展 B）时，
     * 该块会以不足 blockChars 的字符数提交，此后所有块起点相对均匀模型前移。
     * 而增补字符在 UTF-8 里是不可分割的 4 字节，这种边界上不存在合法字节偏移——
     * 非均匀是数据模型的必然结果，只能如实存下来。
     */
    val charStart: Long,
)

@Entity(
    tableName = "offset_index_meta",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class OffsetIndexMetaEntity(
    @androidx.room.PrimaryKey val bookId: Long,
    val fileLength: Long,
    val contentHash: String,
    val charsetName: String,
    val totalChars: Long,
    val completed: Boolean,
)

@Dao
interface OffsetIndexDao {

    @Query("SELECT * FROM offset_index WHERE bookId = :bookId ORDER BY chunkIndex ASC")
    suspend fun getForBook(bookId: Long): List<OffsetIndexEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<OffsetIndexEntity>)

    @Query("DELETE FROM offset_index WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    @Query("SELECT * FROM offset_index_meta WHERE bookId = :bookId")
    suspend fun getMeta(bookId: Long): OffsetIndexMetaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: OffsetIndexMetaEntity)

    @Query("DELETE FROM offset_index_meta WHERE bookId = :bookId")
    suspend fun deleteMeta(bookId: Long)

    /** 覆盖式写入：delete + insert 必须同事务，否则崩在中途会留下半份索引。 */
    @Transaction
    suspend fun replaceForBook(bookId: Long, entries: List<OffsetIndexEntity>) {
        deleteForBook(bookId)
        upsertAll(entries)
    }

    /** 作废该书索引与元信息（重建索引 / 换编码时调用）。 */
    @Transaction
    suspend fun clearForBook(bookId: Long) {
        deleteForBook(bookId)
        deleteMeta(bookId)
    }
}
