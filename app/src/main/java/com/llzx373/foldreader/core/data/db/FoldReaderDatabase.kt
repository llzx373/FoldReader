package com.llzx373.foldreader.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * 从 v1.0.0 起已有公开发布，数据库**必须向前兼容**：老库要能原地升级，不丢数据。
 *
 * 因此：
 * - 改 schema 必须同时升 [version] 并在 [DATABASE_MIGRATIONS] 里补一条迁移（登记处见
 *   `DatabaseMigrations.kt`）；`app/schemas/` 下每个已发布版本的快照都保留，绝不改写；
 * - 不启用 `fallbackToDestructiveMigration`——版本对不上时宁可报错，也不能静默清库。
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
        PersonAppearanceEntity::class,
        TranslationEntity::class,
        GlossaryTermEntity::class,
        ComicPageTranslationEntity::class,
    ],
    version = 7,
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
    abstract fun personAppearanceDao(): PersonAppearanceDao
    abstract fun translationDao(): TranslationDao
    abstract fun glossaryTermDao(): GlossaryTermDao
    abstract fun comicPageTranslationDao(): ComicPageTranslationDao
}
