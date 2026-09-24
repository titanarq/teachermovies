package com.teachermovies.tv.player

import com.teachermovies.player.api.Track

/**
 * One row of the track panel (#79). [id] is what the row selects: an audio [Track.id], a subtitle
 * [Track.id], or null for the `Desactivados` subtitle row. [selected] marks the active track.
 */
data class TrackOption(
    val id: String?,
    val label: String,
    val selected: Boolean,
)

/**
 * The audio/subtitle side panel (#79): the `Audio` list and the `Subtítulos` list, whose first row
 * is always `Desactivados` (subtitles off). Rebuilt from the player's current tracks and selection
 * on every change while the panel is open.
 */
data class TracksPanelState(
    val audio: List<TrackOption>,
    val subtitles: List<TrackOption>,
) {
    companion object {
        const val SUBTITLES_OFF = "Desactivados"

        /** Pure mapping from the player's track lists and selected ids to the panel rows. */
        fun of(
            audio: List<Track>,
            subtitles: List<Track>,
            selectedAudioId: String?,
            selectedSubtitleId: String?,
        ): TracksPanelState =
            TracksPanelState(
                audio =
                    audio.mapIndexed { index, track ->
                        TrackOption(track.id, label(track, "Pista ${index + 1}"), track.id == selectedAudioId)
                    },
                subtitles =
                    listOf(TrackOption(null, SUBTITLES_OFF, selectedSubtitleId == null)) +
                        subtitles.mapIndexed { index, track ->
                            TrackOption(
                                track.id,
                                label(track, "Subtítulo ${index + 1}"),
                                track.id == selectedSubtitleId,
                            )
                        },
            )

        /** The track name, with its language appended when the name does not already say it. */
        private fun label(
            track: Track,
            fallback: String,
        ): String {
            val name = track.name.trim()
            val language = track.language?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                name.isEmpty() -> language ?: fallback
                language == null || name.contains(language, ignoreCase = true) -> name
                else -> "$name ($language)"
            }
        }
    }
}
