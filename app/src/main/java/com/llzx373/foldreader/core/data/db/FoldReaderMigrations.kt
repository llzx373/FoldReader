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

/**
 * v11 → v12：`offset_index` 改为「字节起点 + 字符起点」双列模型
 * （`charOffset` 更名 `byteOffset`，新增 `charStart`）。
 *
 * 为什么要存字符起点：`TxtIndexer` 的输出缓冲只剩 1 个槽位、而下一个字符是需要 2 槽的
 * 增补字符（emoji、CJK 扩展 B）时，该块会以不足 blockChars 的字符数提交，此后所有块起点
 * 相对均匀模型前移。而增补字符在 UTF-8 里是不可分割的 4 字节，这种边界上不存在合法字节
 * 偏移——非均匀是数据模型的必然结果，不能再用 `i * blockChars` 推算。
 *
 * 这里重建空表而不是搬数据：旧行只存了字节偏移，真实字符起点已经丢失，搬过去也是错的
 * （`charStart` 只能填 0，会被加载校验判为损坏）。直接清掉，下次打开重扫一遍即可。
 */
val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `offset_index`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `offset_index` (`bookId` INTEGER NOT NULL, " +
                "`chunkIndex` INTEGER NOT NULL, `byteOffset` INTEGER NOT NULL, " +
                "`charStart` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `chunkIndex`), " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}
