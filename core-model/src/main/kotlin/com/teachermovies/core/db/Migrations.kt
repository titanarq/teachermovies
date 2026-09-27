package com.teachermovies.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 -> v2 (#274): adds the four tables the assistant phases need -- translation cache, explanation
 * cache, subtitle fetch state and subtitle alignment (ADR-0005 §5, §6, §8). Nothing in v1 changes, so
 * an existing install keeps its torrents and playback positions; the new tables simply start empty.
 *
 * The `CREATE TABLE` statements are the ones Room exports for version 2 in
 * `core-model/schemas/.../2.json`: a migration whose tables differ from the entities by even a column
 * order makes Room refuse to open the database. `Migration1To2Test` is what proves they still match.
 */
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `translation_cache` (`sourceText` TEXT NOT NULL, " +
                    "`translationEs` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, " +
                    "`lastUsedAtEpochMs` INTEGER NOT NULL, `hitCount` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`sourceText`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `explanation_cache` (`movieTitle` TEXT NOT NULL, " +
                    "`line` TEXT NOT NULL, `promptVersion` TEXT NOT NULL, " +
                    "`explanationJson` TEXT NOT NULL, `createdAtEpochMs` INTEGER NOT NULL, " +
                    "`lastUsedAtEpochMs` INTEGER NOT NULL, `hitCount` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`movieTitle`, `line`, `promptVersion`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `subtitle_fetch_state` (`infoHash` TEXT NOT NULL, " +
                    "`language` TEXT NOT NULL, `state` TEXT NOT NULL, `movieHash` TEXT, " +
                    "`localPath` TEXT, `variantLabel` TEXT, `attempts` INTEGER NOT NULL, " +
                    "`lastAttemptEpochMs` INTEGER, `nextRetryEpochMs` INTEGER, `errorMessage` TEXT, " +
                    "`updatedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`infoHash`, `language`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `subtitle_alignment` (`infoHash` TEXT NOT NULL, " +
                    "`subtitlePath` TEXT NOT NULL, `offsetMs` INTEGER NOT NULL, " +
                    "`frameRateScale` REAL NOT NULL, `qualityScore` REAL NOT NULL, " +
                    "`computedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`infoHash`, `subtitlePath`))",
            )
        }
    }
