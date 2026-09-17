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
 * v11 → v12 是手写 SQL 的表重建：跑真实 SQLite 验证 SQL 本身与结果结构。
 * 旧行只有字节偏移、真实字符起点已丢失，故一律丢弃——本用例钉住这一点。
 */
@RunWith(RobolectricTestRunner::class)
class MigrationV12Test {

    private fun openV11(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
            .name(null) // 内存库
            .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `offset_index` (`bookId` INTEGER NOT NULL, " +
                            "`chunkIndex` INTEGER NOT NULL, `charOffset` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`bookId`, `chunkIndex`), " +
                            "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    /** 列名 → (是否非空, 是否主键)。 */
    private fun columns(db: SupportSQLiteDatabase, table: String): Map<String, Pair<Boolean, Boolean>> {
        val result = mutableMapOf<String, Pair<Boolean, Boolean>>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            val pkIdx = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                result[cursor.getString(nameIdx)] = (cursor.getInt(notNullIdx) != 0) to (cursor.getInt(pkIdx) != 0)
            }
        }
        return result
    }

    private fun rowCount(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    @Test
    fun `迁移后表换成双列模型并丢弃旧行`() {
        val db = openV11()
        db.execSQL("INSERT INTO offset_index (bookId, chunkIndex, charOffset) VALUES (1, 0, 0), (1, 1, 4096)")
        assertEquals(2, rowCount(db, "offset_index"))

        MIGRATION_11_12.migrate(db)

        val columns = columns(db, "offset_index")
        assertEquals(setOf("bookId", "chunkIndex", "byteOffset", "charStart"), columns.keys)
        assertTrue("byteOffset 应非空", columns.getValue("byteOffset").first)
        assertTrue("charStart 应非空", columns.getValue("charStart").first)
        assertTrue("主键仍是 (bookId, chunkIndex)", columns.getValue("bookId").second)
        assertTrue("主键仍是 (bookId, chunkIndex)", columns.getValue("chunkIndex").second)
        assertFalse("旧列已不存在", columns.containsKey("charOffset"))

        // 旧行只有字节偏移，真实字符起点已丢失 —— 一律丢弃，下次打开重扫
        assertEquals(0, rowCount(db, "offset_index"))
    }

    @Test
    fun `迁移后外键仍指向 books`() {
        val db = openV11()

        MIGRATION_11_12.migrate(db)

        val refs = mutableListOf<String>()
        db.query("PRAGMA foreign_key_list(`offset_index`)").use { cursor ->
            val tableIdx = cursor.getColumnIndexOrThrow("table")
            val onDeleteIdx = cursor.getColumnIndexOrThrow("on_delete")
            while (cursor.moveToNext()) {
                refs += "${cursor.getString(tableIdx)}/${cursor.getString(onDeleteIdx)}"
            }
        }
        assertEquals(listOf("books/CASCADE"), refs)
    }

    @Test
    fun `迁移可重复执行`() {
        val db = openV11()

        MIGRATION_11_12.migrate(db)
        MIGRATION_11_12.migrate(db) // DROP + CREATE IF NOT EXISTS，重跑不应报错

        assertEquals(setOf("bookId", "chunkIndex", "byteOffset", "charStart"), columns(db, "offset_index").keys)
    }
}
