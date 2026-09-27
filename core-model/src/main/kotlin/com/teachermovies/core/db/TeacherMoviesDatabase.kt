package com.teachermovies.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's Room database. Every version's schema is exported to `core-model/schemas/`; bumping the
 * version needs a migration and the new schema JSON committed.
 *
 * Version 2 (#274) adds the assistant caches and the automatic-subtitle tables of ADR-0005:
 * `translation_cache` and `explanation_cache` (only successes are stored, §8), `subtitle_fetch_state`
 * (§5) and `subtitle_alignment` (§6). [build] installs [MIGRATION_1_2], so an upgrade keeps every
 * torrent row instead of recreating the database.
 */
@Database(
    entities = [
        TorrentEntity::class,
        TranslationCacheEntity::class,
        ExplanationCacheEntity::class,
        SubtitleFetchStateEntity::class,
        SubtitleAlignmentEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class TeacherMoviesDatabase : RoomDatabase() {
    abstract fun torrentDao(): TorrentDao

    abstract fun translationCacheDao(): TranslationCacheDao

    abstract fun explanationCacheDao(): ExplanationCacheDao

    abstract fun subtitleFetchStateDao(): SubtitleFetchStateDao

    abstract fun subtitleAlignmentDao(): SubtitleAlignmentDao

    companion object {
        const val NAME = "teachermovies.db"

        /** The production database, stored in the app's database directory as [NAME]. */
        fun build(context: Context): TeacherMoviesDatabase =
            Room
                .databaseBuilder(context.applicationContext, TeacherMoviesDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
