package com.llzx373.foldreader.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1 → v2 迁移必须保住老数据，并让新列的结构与 Room 期望**逐项一致**——
 * Room 打开库时正是拿 `PRAGMA table_info` 这套属性做校验，对不上会直接抛错（用户升级即崩）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DatabaseMigrationTest {

    private data class Column(
        val name: String,
        val type: String,
        val notNull: Boolean,
        val default: String?,
    )

    /** v1.0.0 的 `book_prefs` 建表语句，摘自 `app/schemas/…/1.json`（已发布快照不可改写）。 */
    private val bookPrefsV1 =
        "CREATE TABLE IF NOT EXISTS `book_prefs` (`bookId` INTEGER NOT NULL, " +
            "`fontSizeSp` REAL NOT NULL, `lineSpacingMultiplier` REAL NOT NULL, " +
            "`marginLevel` INTEGER NOT NULL, `maxLineChars` INTEGER NOT NULL DEFAULT 40, " +
            "`paragraphSpacingEm` REAL NOT NULL DEFAULT 0.4, `letterSpacingEm` REAL NOT NULL DEFAULT 0.0, " +
            "`themeId` TEXT NOT NULL, `customBackgroundArgb` INTEGER, `customTextArgb` INTEGER, " +
            "`fontKey` TEXT NOT NULL, `pageTurnMode` TEXT NOT NULL, `readerBrightness` REAL NOT NULL, " +
            "`autoPageEnabled` INTEGER NOT NULL, `autoPageMode` TEXT NOT NULL, " +
            "`autoPageIntervalSec` INTEGER NOT NULL, `autoPageSpeedPx` REAL NOT NULL, " +
            "`panelScreenOff` INTEGER NOT NULL, `autoIndentEnabled` INTEGER NOT NULL DEFAULT 1, " +
            "`comicDirection` TEXT NOT NULL, `comicFitMode` TEXT NOT NULL, `pdfReadingMode` TEXT, " +
            "PRIMARY KEY(`bookId`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"

    private val insertV1 =
        "INSERT INTO `book_prefs` (`bookId`, `fontSizeSp`, `lineSpacingMultiplier`, `marginLevel`, " +
            "`maxLineChars`, `paragraphSpacingEm`, `letterSpacingEm`, `themeId`, `fontKey`, " +
            "`pageTurnMode`, `readerBrightness`, `autoPageEnabled`, `autoPageMode`, " +
            "`autoPageIntervalSec`, `autoPageSpeedPx`, `panelScreenOff`, `autoIndentEnabled`, " +
            "`comicDirection`, `comicFitMode`) VALUES " +
            "(7, 21.5, 1.8, 2, 28, 0.9, 0.2, 'NIGHT', 'serif', 'COVER', 0.6, 1, 'SCROLL', " +
            "20, 120.0, 1, 1, 'RTL', 'FIT_WIDTH')"

    /** 建一个 v1 形态的库（含一行老数据）：只在 onCreate 里铺 v1 结构，之后手动跑迁移。 */
    private fun openV1(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                db.execSQL("INSERT INTO `books` (`id`) VALUES (7)")
                db.execSQL(bookPrefsV1)
                db.execSQL(insertV1)
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(null)
                .callback(callback)
                .build(),
        )
        return helper.writableDatabase
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): List<Column> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            val out = ArrayList<Column>()
            while (c.moveToNext()) {
                out += Column(
                    name = c.getString(1),
                    type = c.getString(2),
                    notNull = c.getInt(3) != 0,
                    default = c.getString(4),
                )
            }
            out
        }

    @Test
    fun `v1 升到 v2 保住老数据且新列结构与 Room 期望一致`() {
        val v1 = openV1()
        val migrated = openV1()

        MIGRATION_1_2.migrate(migrated)

        // 老行原样还在
        migrated.query(
            "SELECT `fontSizeSp`, `autoIndentEnabled`, `comicDirection` FROM `book_prefs` WHERE `bookId` = 7",
        ).use { c ->
            assertTrue("老数据行不见了", c.moveToFirst())
            assertEquals(21.5f, c.getFloat(0), 0.001f)
            assertEquals(1, c.getInt(1))
            assertEquals("RTL", c.getString(2))
        }
        // 新列按 DEFAULT 0 落地
        migrated.query(
            "SELECT `normalizeWhitespaceEnabled` FROM `book_prefs` WHERE `bookId` = 7",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        // 列结构 = v1 的全部列 + 新列（名字/类型/非空/默认值逐项一致，顺序无关）
        assertEquals(
            columns(v1, "book_prefs") + Column("normalizeWhitespaceEnabled", "INTEGER", true, "0"),
            columns(migrated, "book_prefs"),
        )
    }
}
