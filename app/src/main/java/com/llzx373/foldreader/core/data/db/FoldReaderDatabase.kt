package com.llzx373.foldreader.core.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        AnnotationEntity::class,
        ChapterEntity::class,
        ReadingSessionEntity::class,
        OffsetIndexEntity::class,
        OffsetIndexMetaEntity::class,
        BookPrefsEntity::class,
    ],
    version = 13,
    // 10 → 11 起都涉及 AutoMigration 覆盖不了的结构调整（索引增删 / 列改名 / 换模型），
    // 均为手写 SQL，见 FoldReaderMigrations。
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
    ],
    exportSchema = true,
)
abstract class FoldReaderDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun offsetIndexDao(): OffsetIndexDao
    abstract fun bookPrefsDao(): BookPrefsDao
}
