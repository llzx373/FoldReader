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
 * v13 → v14 是加列：跑真实 SQLite 确认列加上了、非空、默认 0，
 * 且**已有章节数据不丢**（丢了 TXT 就得全量重扫，代价很大）。
 */
@RunWith(RobolectricTestRunner::class)
class MigrationV14Test {

    private fun openV13(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null) // 内存库
            .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `chapters` (`bookId` INTEGER NOT NULL, " +
                            "`chapterIndex` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                            "`charStart` INTEGER NOT NULL, `charEnd` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`bookId`, `chapterIndex`))",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    /** 列名 → (是否非空, 默认值)。 */
    private fun columns(db: SupportSQLiteDatabase, table: String): Map<String, Pair<Boolean, String?>> {
        val result = mutableMapOf<String, Pair<Boolean, String?>>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            val defaultIdx = cursor.getColumnIndexOrThrow("dflt_value")
            while (cursor.moveToNext()) {
                result[cursor.getString(nameIdx)] =
                    (cursor.getInt(notNullIdx) != 0) to cursor.getString(defaultIdx)
            }
        }
        return result
    }

    @Test
    fun `迁移后新增 depth 列且已有章节数据保留`() {
        val db = openV13()
        db.execSQL("INSERT INTO chapters (bookId, chapterIndex, title, charStart, charEnd) VALUES (1, 0, '第一章', 0, 100)")
        db.execSQL("INSERT INTO chapters (bookId, chapterIndex, title, charStart, charEnd) VALUES (1, 1, '第二章', 100, 200)")
        assertFalse(columns(db, "chapters").containsKey("depth"))

        MIGRATION_13_14.migrate(db)

        val depth = columns(db, "chapters").getValue("depth")
        assertTrue("depth 应非空", depth.first)
        assertEquals("默认值必须是 0（与实体声明一致）", "0", depth.second)

        // 数据必须原样保留：丢了 TXT 书就得全量重扫章节
        db.query("SELECT bookId, chapterIndex, title, depth FROM chapters ORDER BY chapterIndex").use { cursor ->
            assertEquals(2, cursor.count)
            cursor.moveToFirst()
            assertEquals("第一章", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
            cursor.moveToNext()
            assertEquals("第二章", cursor.getString(2))
            assertEquals(0, cursor.getInt(3))
        }
    }
}
