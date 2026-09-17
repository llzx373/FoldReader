package com.llzx373.foldreader.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 应用尚在预发布阶段：**只保证新安装，不保留任何 schema 历史与迁移**。
 *
 * 因此没有 `autoMigrations`，也没有手写 `Migration`——schema 变更时直接重置基线
 * （清库/重装即可，不需要在代码里留兼容路径）。`version` 恒为 1，每次变更重新导出
 * 同名 schema 快照。
 */
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
    version = 1,
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
