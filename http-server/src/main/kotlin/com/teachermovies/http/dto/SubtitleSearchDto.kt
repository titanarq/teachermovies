package com.teachermovies.http.dto

import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.SubtitleFetchState
import kotlinx.serialization.Serializable

/**
 * Where one library movie's automatic-subtitle search stands, as the phone's "Buscar subtítulos"
 * action (#285) reads it from `GET` and `POST /api/library/{id}/subtitles[/search]`.
 *
 * [status] sums the whole movie up in one word the web UI turns into a sentence:
 * - `found`: every language the movie needs has a downloaded file;
 * - `laptop_offline`: something is still missing and no bridge is connected to look for it;
 * - `searching`: a connected bridge has it pending or in flight;
 * - `failed`: the last search errored and nothing is pending any more;
 * - `not_found`: OpenSubtitles had nothing for what is still missing;
 * - `no_needs`: the TV has not recorded any need for this movie yet.
 *
 * [laptopConnected] is whether a bridge stream is open right now; [languages] is one entry per
 * language row, in language order. No file system path ever goes on the wire.
 */
@Serializable
data class SubtitleSearchDto(
    val torrentId: String,
    val status: String,
    val laptopConnected: Boolean,
    val languages: List<SubtitleLanguageStateDto>,
)

/** One language of [SubtitleSearchDto]: its fetch state in snake_case, and `"latino"` if so. */
@Serializable
data class SubtitleLanguageStateDto(
    val language: String,
    val state: String,
    val variant: String?,
    val attempts: Int,
)

internal const val SEARCH_FOUND = "found"
internal const val SEARCH_LAPTOP_OFFLINE = "laptop_offline"
internal const val SEARCH_SEARCHING = "searching"
internal const val SEARCH_FAILED = "failed"
internal const val SEARCH_NOT_FOUND = "not_found"
internal const val SEARCH_NO_NEEDS = "no_needs"

/** The rows of one movie as its [SubtitleSearchDto]; see there for what each status means. */
internal fun List<SubtitleFetch>.toSearchDto(
    torrentId: String,
    laptopConnected: Boolean,
): SubtitleSearchDto {
    val states = map { it.state }
    val status =
        when {
            isEmpty() -> {
                SEARCH_NO_NEEDS
            }

            states.all { it == SubtitleFetchState.Downloaded } -> {
                SEARCH_FOUND
            }

            !laptopConnected -> {
                SEARCH_LAPTOP_OFFLINE
            }

            states.any { it == SubtitleFetchState.Pending || it == SubtitleFetchState.Searching } -> {
                SEARCH_SEARCHING
            }

            states.any { it == SubtitleFetchState.Failed } -> {
                SEARCH_FAILED
            }

            else -> {
                SEARCH_NOT_FOUND
            }
        }
    return SubtitleSearchDto(
        torrentId = torrentId,
        status = status,
        laptopConnected = laptopConnected,
        languages =
            sortedBy { it.language }.map {
                SubtitleLanguageStateDto(
                    language = it.language,
                    state = it.state.toWireValue(),
                    variant = it.variantLabel,
                    attempts = it.attempts,
                )
            },
    )
}
