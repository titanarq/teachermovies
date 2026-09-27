package com.teachermovies.core.db

import androidx.room.Entity

/**
 * How one downloaded Spanish subtitle file lines up with a movie's timeline (#274, ADR-0005 §6),
 * keyed by ([infoHash], [subtitlePath]) -- the alignment belongs to the pair of files, so a movie
 * with two candidate Spanish files keeps two rows and the panel can pick the better-scored one.
 *
 * [qualityScore] is 0..1, higher is better; deciding how good is good enough is the aligner's and the
 * panel's business (#283), not the schema's.
 */
@Entity(
    tableName = "subtitle_alignment",
    primaryKeys = ["infoHash", "subtitlePath"],
)
data class SubtitleAlignmentEntity(
    val infoHash: String,
    val subtitlePath: String,
    val offsetMs: Long,
    val frameRateScale: Double,
    val qualityScore: Double,
    val computedAtEpochMs: Long,
)
