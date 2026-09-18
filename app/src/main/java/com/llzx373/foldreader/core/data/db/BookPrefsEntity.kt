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

/**
 * 每书阅读偏好。
 *
 * 只有**会随书独立演化**的项才在这里：正文排版（字号/行距/边距/行长/段距/字距/字体/首行缩进）、
 * 主题配色、翻页方式、阅读器内菜单里的项（亮度/自动翻页/桌面面板熄屏）、PDF 阅读模式，
 * 以及漫画的方向与适应模式。
 *
 * 交互开关与显示项（双页模式、热区比例、中间单击/双击、音量键、常亮、页眉页脚显示、
 * 封面单独/跨页识别/滚动页间距）刻意**不在这里**：它们是应用级偏好，由全局
 * [com.llzx373.foldreader.core.data.settings.ReadingPreferences] 直通——否则设置页改完
 * 对已打开过的书毫无作用。
 */
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
    val fontKey: String = "default",
    val pageTurnMode: String = "COVER",
    val readerBrightness: Float = -1f,
    val autoPageEnabled: Boolean = false,
    val autoPageMode: String = "INTERVAL",
    val autoPageIntervalSec: Int = 10,
    val autoPageSpeedPx: Float = 60f,
    val panelScreenOff: Boolean = false,
    @ColumnInfo(defaultValue = "1") val autoIndentEnabled: Boolean = true,
    /** 以下为漫画专用。 */
    val comicDirection: String = "LTR",
    val comicFitMode: String = "FIT_PAGE",
    /** PDF 的阅读模式（PAGED/TEXT）；null = 未由用户定过，按文档是否有正文决定。 */
    val pdfReadingMode: String? = null,
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

    @Query("UPDATE book_prefs SET pageTurnMode = :mode")
    suspend fun applyGlobalPageTurnMode(mode: String)

    @Query("UPDATE book_prefs SET comicFitMode = :mode")
    suspend fun applyGlobalComicFitMode(mode: String)

    @Query("UPDATE book_prefs SET comicDirection = :direction")
    suspend fun applyGlobalComicDirection(direction: String)

    @Query("DELETE FROM book_prefs WHERE bookId = :bookId")
    suspend fun delete(bookId: Long)
}
