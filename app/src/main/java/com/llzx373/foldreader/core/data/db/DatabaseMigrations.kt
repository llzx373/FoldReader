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
 * v3：新建人物出场索引表 `person_appearances`（M13.2），并给 `book_prefs` 加
 * 按书自定义章节规则列 `chapterRules`（M15）。建表语句照 `app/schemas/…/3.json` 快照逐字誊写。
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `person_appearances` (`bookId` INTEGER NOT NULL, " +
                "`name` TEXT NOT NULL, `firstChapterIndex` INTEGER NOT NULL, " +
                "`firstCharOffset` INTEGER NOT NULL, `mentionCount` INTEGER NOT NULL, " +
                "PRIMARY KEY(`bookId`, `name`), FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "ALTER TABLE `book_prefs` ADD COLUMN `chapterRules` TEXT NOT NULL DEFAULT ''",
        )
    }
}

/**
 * v4：新建翻译单位台账表 `translations`（M19），并给 `reading_progress` 加
 * 译文模式锚点列 `translationAnchor`（可空，原/译/页式三锚点对称共存）。
 * 建表语句照 `app/schemas/…/4.json` 快照逐字誊写。
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `translations` (`bookId` INTEGER NOT NULL, " +
                "`lang` TEXT NOT NULL, `unitKind` TEXT NOT NULL, `unitIndex` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, `model` TEXT NOT NULL, `paragraphCount` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `lang`, `unitIndex`), " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL(
            "ALTER TABLE `reading_progress` ADD COLUMN `translationAnchor` INTEGER",
        )
    }
}

/**
 * v5：新建术语表 `glossary_terms`（M20，R6）。自增主键 + (scope, ownerKey, source)
 * 唯一索引；不设书籍外键（global/series 行不属于任何书）。
 * 建表语句照 Room 期望的结构书写，由迁移测试与 `app/schemas/…/5.json` 快照核对。
 */
internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `glossary_terms` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`scope` TEXT NOT NULL, `ownerKey` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`target` TEXT NOT NULL, `origin` TEXT NOT NULL, `confirmed` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_glossary_terms_scope_ownerKey_source` " +
                "ON `glossary_terms` (`scope`, `ownerKey`, `source`)",
        )
    }
}

/**
 * v6：`books` 加元数据补全字段（M17）——题材标签 `genreTag`（可空，老行落 NULL）与
 * 逐字段来源标记 `metaSource`（非空，老行取 DEFAULT ''）。只做结构变更，不回填数据。
 */
internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `books` ADD COLUMN `genreTag` TEXT")
        db.execSQL("ALTER TABLE `books` ADD COLUMN `metaSource` TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * v7：新建漫画页翻译台账表 `comic_page_translations`（M22）。主键 (bookId, lang, pageIndex)，
 * 随书级联删除；译文不落库（落 filesDir/comic_translate/），只存状态机与元信息。
 * 建表语句照 `app/schemas/…/7.json` 快照逐字誊写。
 */
internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `comic_page_translations` (`bookId` INTEGER NOT NULL, " +
                "`lang` TEXT NOT NULL, `pageIndex` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, `bubbleCount` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `lang`, `pageIndex`), " +
                "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
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
val DATABASE_MIGRATIONS: Array<Migration> =
    arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
