package com.teachermovies.core.db

import androidx.room.Entity

/**
 * One cached "Explicar" answer (#274, ADR-0005 §7 and §8: only successes are stored). Claude explains
 * a line in the context of a movie, and it explains it with whatever prompt the bridge is running, so
 * the row is keyed by all three: the same idiom in two films can deserve two answers, asking about the
 * same line twice in one film must not spend the daily cap again, and a new [promptVersion] starts a
 * fresh set of answers instead of serving ones an older prompt wrote (#292).
 *
 * [explanationJson] is the bridge's payload verbatim -- `:core-model` does not model what the panel
 * renders (#292 owns that), it only stores it and hands it back.
 *
 * [lastUsedAtEpochMs] and [hitCount] are what the pruning policy ranks by.
 */
@Entity(
    tableName = "explanation_cache",
    primaryKeys = ["movieTitle", "line", "promptVersion"],
)
data class ExplanationCacheEntity(
    val movieTitle: String,
    val line: String,
    val promptVersion: String,
    val explanationJson: String,
    val createdAtEpochMs: Long,
    val lastUsedAtEpochMs: Long,
    val hitCount: Int,
)
