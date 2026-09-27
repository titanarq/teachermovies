package com.teachermovies.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One cached English -> Castilian Spanish translation of a subtitle line (#274, ADR-0005 §8: only
 * successes are stored, so a row's existence means the bridge answered). The source text is its own
 * primary key -- like `torrents.infoHash`, the table stays readable in a `.db` dump and the key needs
 * no derivation a caller could get wrong; [sourceText] is the normalized text, see
 * `com.teachermovies.core.repo.normalizeCacheText`.
 *
 * [lastUsedAtEpochMs] and [hitCount] are what the pruning policy ranks by, so a line that keeps
 * being asked for survives and a one-off does not.
 */
@Entity(tableName = "translation_cache")
data class TranslationCacheEntity(
    @PrimaryKey val sourceText: String,
    val translationEs: String,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
    val hitCount: Int,
)
