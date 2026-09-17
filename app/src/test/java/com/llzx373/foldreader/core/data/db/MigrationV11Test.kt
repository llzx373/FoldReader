package com.llzx373.foldreader.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 10 → 11 是手写 SQL 的索引迁移：跑真实 SQLite 验证 SQL 本身，
 * 而不是只看导出 schema（后者只能证明实体定义对了）。
 */
@RunWith(RobolectricTestRunner::class)
class MigrationV11Test {

    private fun openV10(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null) // 内存库
            .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `bookmarks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`bookId` INTEGER NOT NULL, `charOffset` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
                            "`snapshotText` TEXT NOT NULL, `label` TEXT NOT NULL DEFAULT '', `createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `annotations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`bookId` INTEGER NOT NULL, `startCharOffset` INTEGER NOT NULL, `endCharOffset` INTEGER NOT NULL, " +
                            "`selectedText` TEXT NOT NULL, `color` INTEGER NOT NULL, `note` TEXT, " +
                            "`style` TEXT NOT NULL DEFAULT 'highlight', `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_annotations_bookId` ON `annotations` (`bookId`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `chapters` (`bookId` INTEGER NOT NULL, `chapterIndex` INTEGER NOT NULL, " +
                            "`title` TEXT NOT NULL, `charStart` INTEGER NOT NULL, `charEnd` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`bookId`, `chapterIndex`))",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapters_bookId` ON `chapters` (`bookId`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `offset_index` (`bookId` INTEGER NOT NULL, `chunkIndex` INTEGER NOT NULL, " +
                            "`charOffset` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `chunkIndex`))",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_offset_index_bookId` ON `offset_index` (`bookId`)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?", arrayOf(table))
            .use { cursor ->
                while (cursor.moveToNext()) names += cursor.getString(0)
            }
        return names
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun `迁移后索引按要求增删且数据不丢`() {
        val db = openV10()
        db.execSQL(
            "INSERT INTO bookmarks (bookId, charOffset, chapterIndex, snapshotText, label, createdAt) " +
                "VALUES (1, 100, 0, '快照', '书签', 1000)",
        )
        db.execSQL(
            "INSERT INTO annotations (bookId, startCharOffset, endCharOffset, selectedText, color, note, style, createdAt, updatedAt) " +
                "VALUES (1, 10, 20, '划线', 255, NULL, 'highlight', 1000, 1000)",
        )
        db.execSQL("INSERT INTO chapters (bookId, chapterIndex, title, charStart, charEnd) VALUES (1, 0, '第一章', 0, 500)")
        db.execSQL("INSERT INTO offset_index (bookId, chunkIndex, charOffset) VALUES (1, 0, 0)")

        val beforeBookmarks = indexNames(db, "bookmarks")
        assertTrue(beforeBookmarks.contains("index_bookmarks_bookId"))

        MIGRATION_10_11.migrate(db)

        // 数据原样保留
        assertEquals(1, count(db, "bookmarks"))
        assertEquals(1, count(db, "annotations"))
        assertEquals(1, count(db, "chapters"))
        assertEquals(1, count(db, "offset_index"))
        db.query("SELECT label FROM bookmarks").use { cursor ->
            cursor.moveToFirst()
            assertEquals("书签", cursor.getString(0))
        }

        // 新索引建立、旧索引移除
        val bookmarks = indexNames(db, "bookmarks")
        assertTrue(bookmarks.contains("index_bookmarks_bookId_createdAt"))
        assertFalse(bookmarks.contains("index_bookmarks_bookId"))

        val annotations = indexNames(db, "annotations")
        assertTrue(annotations.contains("index_annotations_bookId_startCharOffset"))
        assertFalse(annotations.contains("index_annotations_bookId"))

        assertFalse(indexNames(db, "chapters").contains("index_chapters_bookId"))
        assertFalse(indexNames(db, "offset_index").contains("index_offset_index_bookId"))
    }

    @Test
    fun `迁移可重复执行`() {
        val db = openV10()

        MIGRATION_10_11.migrate(db)
        MIGRATION_10_11.migrate(db) // 全部语句都是 IF [NOT] EXISTS，重跑不应报错

        assertTrue(indexNames(db, "bookmarks").contains("index_bookmarks_bookId_createdAt"))
        assertTrue(indexNames(db, "annotations").contains("index_annotations_bookId_startCharOffset"))
    }
}
