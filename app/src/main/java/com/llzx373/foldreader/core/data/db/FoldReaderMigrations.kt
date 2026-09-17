package com.llzx373.foldreader.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v10 → v11：索引增删。Room 的 AutoMigration 不处理索引变更，只能手写 SQL。
 *
 *  - bookmarks / annotations 各补一个复合索引，让 `WHERE bookId = ? ORDER BY <列>` 走索引排序；
 *  - chapters / offset_index 去掉与主键前缀重复的 `bookId` 单列索引（纯写放大）。
 *
 * 不涉及任何数据搬迁，因此没有数据丢失风险；索引名必须是 Room 的约定命名
 * （`index_<表>_<列1>_<列2>`），否则升级后 schema 校验会失败。
 */
val MIGRATION_10_11: Migration = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId_createdAt` " +
                "ON `bookmarks` (`bookId`, `createdAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_annotations_bookId_startCharOffset` " +
                "ON `annotations` (`bookId`, `startCharOffset`)",
        )
        // 旧的单列索引必须一并删除：Room 打开库后会把实际索引集合与实体定义逐一比对，
        // 多出来的索引会被判为「迁移未正确处理」而直接抛异常。
        db.execSQL("DROP INDEX IF EXISTS `index_bookmarks_bookId`")
        db.execSQL("DROP INDEX IF EXISTS `index_annotations_bookId`")
        db.execSQL("DROP INDEX IF EXISTS `index_chapters_bookId`")
        db.execSQL("DROP INDEX IF EXISTS `index_offset_index_bookId`")
    }
}
