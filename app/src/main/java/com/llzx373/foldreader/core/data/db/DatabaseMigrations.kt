package com.llzx373.foldreader.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1.1：每书的 `book_prefs` 增加「空白归一化」开关（默认关，老行取 DEFAULT 0）。 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `book_prefs` ADD COLUMN `normalizeWhitespaceEnabled` INTEGER NOT NULL DEFAULT 0",
        )
    }
}

/**
 * 数据库迁移登记表，供 `Room.databaseBuilder(...).addMigrations(*DATABASE_MIGRATIONS)` 使用。
 *
 * **规矩：schema 一变就必须升 [FoldReaderDatabase.version] 并在这里补一条迁移。**
 * v1.0.0 起已有公开发布，用户手上的库必须能原地升级、不能丢数据——再也不要"重置基线、
 * 只保证新安装"那套（`app/schemas/` 下每个已发布版本的 schema 快照都要保留，不能改写）。
 *
 * 迁移只做结构变更；要动数据另起一条 Migration，并在上面补注释说明。
 */
val DATABASE_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
