package com.llzx373.foldreader.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v12 → v13 是加列：跑真实 SQLite 确认列加上了、可空、旧行留 NULL（= 未就绪）。
 */
@RunWith(RobolectricTestRunner::class)
class MigrationV13Test {

    private fun openV12(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null) // 内存库
            .callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // 只建迁移需要的最小形态：ALTER TABLE 不关心其它列
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `books` (`id` INTEGER NOT NULL PRIMARY KEY, " +
                            "`title` TEXT NOT NULL, `format` TEXT NOT NULL)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    /** 列名 → 是否非空。 */
    private fun notNullFlags(db: SupportSQLiteDatabase, table: String): Map<String, Boolean> {
        val result = mutableMapOf<String, Boolean>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            while (cursor.moveToNext()) {
                result[cursor.getString(nameIdx)] = cursor.getInt(notNullIdx) != 0
            }
        }
        return result
    }

    @Test
    fun `迁移后新增可空列且旧行留 NULL`() {
        val db = openV12()
        db.execSQL("INSERT INTO books (id, title, format) VALUES (1, '旧书', 'EPUB')")
        assertFalse(notNullFlags(db, "books").containsKey("contentPreparedAt"))

        MIGRATION_12_13.migrate(db)

        val flags = notNullFlags(db, "books")
        assertTrue(flags.containsKey("contentPreparedAt"))
        assertFalse("必须可空", flags.getValue("contentPreparedAt"))

        db.query("SELECT contentPreparedAt FROM books WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue("旧行应为未就绪（NULL）", cursor.isNull(0))
        }
    }
}
