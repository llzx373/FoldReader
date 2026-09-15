package com.llzx373.foldreader.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "book_prefs",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class BookPrefsEntity(
    @PrimaryKey val bookId: Long,
    val fontSizeSp: Float = 18f,
    val lineSpacingMultiplier: Float = 1.5f,
    val marginLevel: Int = 1,
    @ColumnInfo(defaultValue = "40") val maxLineChars: Int = 40,
    @ColumnInfo(defaultValue = "0.4") val paragraphSpacingEm: Float = 0.4f,
    @ColumnInfo(defaultValue = "0.0") val letterSpacingEm: Float = 0f,
    val themeId: String = "GREEN",
    val customBackgroundArgb: Int? = null,
    val customTextArgb: Int? = null,
    val darkThemeOption: String = "SYSTEM",
    val fontKey: String = "default",
    val dualPageMode: String = "AUTO",
    val pageTurnMode: String = "COVER",
    @ColumnInfo(defaultValue = "0") val pageTurnModeExplicit: Boolean = false,
    val pageTurnHotspotRatio: Float = 0.3f,
    val volumeKeyPagingEnabled: Boolean = false,
    val keepScreenOn: Boolean = false,
    val showChapterTitle: Boolean = true,
    val showPageProgress: Boolean = true,
    @ColumnInfo(defaultValue = "1") val showPageNumber: Boolean = true,
    val showBattery: Boolean = true,
    val showTime: Boolean = true,
    val readerBrightness: Float = -1f,
    val autoPageEnabled: Boolean = false,
    val autoPageMode: String = "INTERVAL",
    val autoPageIntervalSec: Int = 10,
    val autoPageSpeedPx: Float = 60f,
    val simulationDegraded: Boolean = false,
    val panelScreenOff: Boolean = false,
    @ColumnInfo(defaultValue = "1") val autoIndentEnabled: Boolean = true,
)

@Dao
interface BookPrefsDao {

    @Query("SELECT * FROM book_prefs WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<BookPrefsEntity?>

    @Query("SELECT * FROM book_prefs WHERE bookId = :bookId")
    suspend fun get(bookId: Long): BookPrefsEntity?

    @Query("SELECT * FROM book_prefs")
    suspend fun getAll(): List<BookPrefsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(prefs: BookPrefsEntity)

    @Query("UPDATE book_prefs SET pageTurnMode = :mode, pageTurnModeExplicit = 1, simulationDegraded = 0")
    suspend fun applyGlobalPageTurnMode(mode: String)

    @Query("DELETE FROM book_prefs WHERE bookId = :bookId")
    suspend fun delete(bookId: Long)
}
