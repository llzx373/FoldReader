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
    fun `v1 升到 v2 保住老数据且新列结构与 Room 期望一致`() {        val v1 = openV1()
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

    /** 建一个 v2 形态的库：v1 结构 + MIGRATION_1_2 的新列，之后手动跑 v2→v3 迁移。 */
    private fun openV2(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                db.execSQL("INSERT INTO `books` (`id`) VALUES (7)")
                db.execSQL(bookPrefsV1)
                db.execSQL(insertV1)
                MIGRATION_1_2.migrate(db)
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

    @Test
    fun `v2 升到 v3 建人物出场表且 book_prefs 加章节规则列`() {
        val v2 = openV2()
        val migrated = openV2()

        MIGRATION_2_3.migrate(migrated)

        // book_prefs 老行原样还在，新列按 DEFAULT '' 落地
        migrated.query(
            "SELECT `fontSizeSp`, `chapterRules` FROM `book_prefs` WHERE `bookId` = 7",
        ).use { c ->
            assertTrue("老数据行不见了", c.moveToFirst())
            assertEquals(21.5f, c.getFloat(0), 0.001f)
            assertEquals("", c.getString(1))
        }
        // 列结构 = v2 的全部列 + 新列（与 Room 期望逐项一致）
        assertEquals(
            columns(v2, "book_prefs") + Column("chapterRules", "TEXT", true, "''"),
            columns(migrated, "book_prefs"),
        )
        // 新表结构与 3.json 快照一致
        assertEquals(
            listOf(
                Column("bookId", "INTEGER", true, null),
                Column("name", "TEXT", true, null),
                Column("firstChapterIndex", "INTEGER", true, null),
                Column("firstCharOffset", "INTEGER", true, null),
                Column("mentionCount", "INTEGER", true, null),
            ),
            columns(migrated, "person_appearances"),
        )
        // 复合主键 (bookId, name) 与外键级联的形状校验
        migrated.query("PRAGMA index_list(`person_appearances`)").use { c ->
            assertTrue("缺主键索引", c.moveToFirst())
        }
        migrated.query("PRAGMA foreign_key_list(`person_appearances`)").use { c ->
            assertTrue("缺外键", c.moveToFirst())
            assertEquals("books", c.getString(2))
            assertEquals("CASCADE", c.getString(6))
        }
    }

    /** v3 的 `reading_progress` 建表语句，摘自 `app/schemas/…/3.json`（已发布快照不可改写）。 */
    private val readingProgressV3 =
        "CREATE TABLE IF NOT EXISTS `reading_progress` (`bookId` INTEGER NOT NULL, " +
            "`charOffset` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
            "`totalReadingMillis` INTEGER NOT NULL, `firstReadAt` INTEGER NOT NULL DEFAULT 0, " +
            "`charsReadTotal` INTEGER NOT NULL DEFAULT 0, `comicPage` INTEGER, " +
            "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`), " +
            "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )"

    /** 建一个 v3 形态的库（含一行阅读进度老数据），之后手动跑 v3→v4 迁移。 */
    private fun openV3(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                db.execSQL("INSERT INTO `books` (`id`) VALUES (7)")
                db.execSQL(bookPrefsV1)
                db.execSQL(insertV1)
                MIGRATION_1_2.migrate(db)
                MIGRATION_2_3.migrate(db)
                db.execSQL(readingProgressV3)
                db.execSQL(
                    "INSERT INTO `reading_progress` (`bookId`, `charOffset`, `chapterIndex`, " +
                        "`totalReadingMillis`, `firstReadAt`, `charsReadTotal`, `comicPage`, " +
                        "`updatedAt`) VALUES (7, 9000, 3, 60000, 1, 9000, 50, 2)",
                )
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

    @Test
    fun `v3 升到 v4 建翻译台账表且 reading_progress 加译文锚点列`() {
        val v3 = openV3()
        val migrated = openV3()

        MIGRATION_3_4.migrate(migrated)

        // reading_progress 老行原样还在，新列默认 NULL
        migrated.query(
            "SELECT `charOffset`, `comicPage`, `translationAnchor` FROM `reading_progress` WHERE `bookId` = 7",
        ).use { c ->
            assertTrue("老数据行不见了", c.moveToFirst())
            assertEquals(9000L, c.getLong(0))
            assertEquals(50, c.getInt(1))
            assertTrue("新列应默认 NULL", c.isNull(2))
        }
        // 列结构 = v3 的全部列 + 新列（可空、无默认值，与 Room 期望逐项一致）
        assertEquals(
            columns(v3, "reading_progress") + Column("translationAnchor", "INTEGER", false, null),
            columns(migrated, "reading_progress"),
        )
        // 新表结构与 4.json 快照一致
        assertEquals(
            listOf(
                Column("bookId", "INTEGER", true, null),
                Column("lang", "TEXT", true, null),
                Column("unitKind", "TEXT", true, null),
                Column("unitIndex", "INTEGER", true, null),
                Column("status", "TEXT", true, null),
                Column("model", "TEXT", true, null),
                Column("paragraphCount", "INTEGER", true, null),
                Column("updatedAt", "INTEGER", true, null),
            ),
            columns(migrated, "translations"),
        )
        // 复合主键 (bookId, lang, unitIndex) 与外键级联的形状校验
        migrated.query("PRAGMA index_list(`translations`)").use { c ->
            assertTrue("缺主键索引", c.moveToFirst())
        }
        migrated.query("PRAGMA foreign_key_list(`translations`)").use { c ->
            assertTrue("缺外键", c.moveToFirst())
            assertEquals("books", c.getString(2))
            assertEquals("CASCADE", c.getString(6))
        }
        // 插入一行台账抽查可写
        migrated.execSQL(
            "INSERT INTO `translations` (`bookId`, `lang`, `unitKind`, `unitIndex`, `status`, " +
                "`model`, `paragraphCount`, `updatedAt`) VALUES (7, 'ZH_HANS', 'chapter', 0, 'done', 'm', 12, 3)",
        )
        migrated.query(
            "SELECT `status`, `paragraphCount` FROM `translations` WHERE `bookId` = 7 AND `lang` = 'ZH_HANS'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("done", c.getString(0))
            assertEquals(12, c.getInt(1))
        }
    }

    /** 建一个 v4 形态的库（含一行翻译台账老数据），之后手动跑 v4→v5 迁移。 */
    private fun openV4(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                db.execSQL("INSERT INTO `books` (`id`) VALUES (7)")
                db.execSQL(readingProgressV3)
                MIGRATION_3_4.migrate(db)
                db.execSQL(
                    "INSERT INTO `translations` (`bookId`, `lang`, `unitKind`, `unitIndex`, `status`, " +
                        "`model`, `paragraphCount`, `updatedAt`) VALUES (7, 'ZH_HANS', 'chapter', 0, 'done', 'm', 12, 3)",
                )
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

    @Test
    fun `v4 升到 v5 建术语表且老数据不动`() {
        val migrated = openV4()

        MIGRATION_4_5.migrate(migrated)

        // 老表老行原样还在
        migrated.query(
            "SELECT `status` FROM `translations` WHERE `bookId` = 7 AND `lang` = 'ZH_HANS'",
        ).use { c ->
            assertTrue("老数据行不见了", c.moveToFirst())
            assertEquals("done", c.getString(0))
        }
        // 新表列结构与 Room 期望逐项一致
        assertEquals(
            listOf(
                Column("id", "INTEGER", true, null),
                Column("scope", "TEXT", true, null),
                Column("ownerKey", "TEXT", true, null),
                Column("source", "TEXT", true, null),
                Column("target", "TEXT", true, null),
                Column("origin", "TEXT", true, null),
                Column("confirmed", "INTEGER", true, null),
            ),
            columns(migrated, "glossary_terms"),
        )
        // (scope, ownerKey, source) 唯一索引：同键二插必败
        migrated.execSQL(
            "INSERT INTO `glossary_terms` (`scope`, `ownerKey`, `source`, `target`, `origin`, `confirmed`) " +
                "VALUES ('book', '7', '张三', 'Zhang San', 'auto', 0)",
        )
        var duplicateRejected = false
        try {
            migrated.execSQL(
                "INSERT INTO `glossary_terms` (`scope`, `ownerKey`, `source`, `target`, `origin`, `confirmed`) " +
                    "VALUES ('book', '7', '张三', 'Sam', 'user', 1)",
            )
        } catch (_: android.database.SQLException) {
            duplicateRejected = true
        }
        assertTrue("唯一索引未生效", duplicateRejected)
        // 不同 scope / ownerKey 的同名 source 共存
        migrated.execSQL(
            "INSERT INTO `glossary_terms` (`scope`, `ownerKey`, `source`, `target`, `origin`, `confirmed`) " +
                "VALUES ('global', '', '张三', 'Sam', 'user', 1)",
        )
        migrated.query("SELECT COUNT(*) FROM `glossary_terms`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
    }

    /** 建一个 v5 形态的库（`books` 含一行老数据），之后手动跑 v5→v6 迁移。 */
    private fun openV5(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(5) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL)",
                )
                db.execSQL("INSERT INTO `books` (`id`, `title`) VALUES (7, '老书')")
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

    @Test
    fun `v5 升到 v6 books 加题材与来源标记列且老行取默认值`() {
        val v5 = openV5()
        val migrated = openV5()

        MIGRATION_5_6.migrate(migrated)

        // 老行原样还在；genreTag 默认 NULL，metaSource 按 DEFAULT '' 落地
        migrated.query(
            "SELECT `title`, `genreTag`, `metaSource` FROM `books` WHERE `id` = 7",
        ).use { c ->
            assertTrue("老数据行不见了", c.moveToFirst())
            assertEquals("老书", c.getString(0))
            assertTrue("genreTag 应默认 NULL", c.isNull(1))
            assertEquals("", c.getString(2))
        }
        // 列结构 = v5 的全部列 + 两个新列（名字/类型/非空/默认值逐项一致，顺序无关）
        assertEquals(
            columns(v5, "books") +
                Column("genreTag", "TEXT", false, null) +
                Column("metaSource", "TEXT", true, "''"),
            columns(migrated, "books"),
        )
        // 新列可写：AI 补全 / 用户编辑各写一次抽查
        migrated.execSQL(
            "UPDATE `books` SET `genreTag` = '科幻', `metaSource` = 'genre:ai' WHERE `id` = 7",
        )
        migrated.query("SELECT `metaSource` FROM `books` WHERE `id` = 7").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("genre:ai", c.getString(0))
        }
    }

    /** 建一个 v6 形态的库（`books` 含一行老数据），之后手动跑 v6→v7 迁移。 */
    private fun openV6(): SupportSQLiteDatabase {
        val callback = object : SupportSQLiteOpenHelper.Callback(6) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `genreTag` TEXT, `metaSource` TEXT NOT NULL DEFAULT '')",
                )
                db.execSQL("INSERT INTO `books` (`id`, `title`) VALUES (7, '老漫画')")
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

    @Test
    fun `v6 升到 v7 建漫画页翻译台账且老数据不动`() {
        val migrated = openV6()

        MIGRATION_6_7.migrate(migrated)

        // 老表老行原样还在
        migrated.query("SELECT `title` FROM `books` WHERE `id` = 7").use { c ->
            assertTrue("老数据行不见了", c.moveToFirst())
            assertEquals("老漫画", c.getString(0))
        }
        // 新表列结构与 Room 期望逐项一致（照 7.json 快照）
        assertEquals(
            listOf(
                Column("bookId", "INTEGER", true, null),
                Column("lang", "TEXT", true, null),
                Column("pageIndex", "INTEGER", true, null),
                Column("status", "TEXT", true, null),
                Column("model", "TEXT", true, null),
                Column("bubbleCount", "INTEGER", true, null),
                Column("updatedAt", "INTEGER", true, null),
            ),
            columns(migrated, "comic_page_translations"),
        )
        // 主键 (bookId, lang, pageIndex)：同键二插必败、不同语言同页共存
        migrated.execSQL(
            "INSERT INTO `comic_page_translations` (`bookId`, `lang`, `pageIndex`, `status`, `model`, " +
                "`bubbleCount`, `updatedAt`) VALUES (7, 'ZH_HANS', 3, 'done', 'm', 5, 1)",
        )
        migrated.execSQL(
            "INSERT INTO `comic_page_translations` (`bookId`, `lang`, `pageIndex`, `status`, `model`, " +
                "`bubbleCount`, `updatedAt`) VALUES (7, 'EN', 3, 'pending', '', 0, 1)",
        )
        var duplicateRejected = false
        try {
            migrated.execSQL(
                "INSERT INTO `comic_page_translations` (`bookId`, `lang`, `pageIndex`, `status`, `model`, " +
                    "`bubbleCount`, `updatedAt`) VALUES (7, 'ZH_HANS', 3, 'failed', '', 0, 2)",
            )
        } catch (_: android.database.SQLException) {
            duplicateRejected = true
        }
        assertTrue("主键约束未生效", duplicateRejected)
        migrated.query("SELECT COUNT(*) FROM `comic_page_translations`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2, c.getInt(0))
        }
    }
}
