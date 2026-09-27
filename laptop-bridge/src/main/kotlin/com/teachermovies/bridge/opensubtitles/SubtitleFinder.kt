package com.teachermovies.bridge.opensubtitles

/** The two languages the bridge fetches subtitles in (ADR-0005 §5). */
enum class SubtitleLanguage {
    ENGLISH,
    SPANISH,
}

/**
 * Which kind of subtitle was found. [label] is what the TV shows next to it: only Latin-American
 * Spanish is labelled, "latino" (ADR-0005 §5, parent #266 decision 5).
 */
enum class SubtitleVariant(
    val label: String?,
) {
    ENGLISH(null),
    CASTILIAN(null),
    LATINO("latino"),
}

/**
 * The movie a subtitle is wanted for. [moviehash] is the OpenSubtitles hash the TV computed (#279);
 * [imdbId], [title] and [year] are the fallbacks when no subtitle matches the hash.
 */
data class SubtitleRequest(
    val language: SubtitleLanguage,
    val moviehash: String? = null,
    val imdbId: String? = null,
    val title: String? = null,
    val year: Int? = null,
)

/** A downloaded subtitle; [text] is already normalised (see [SubtitleText]), UTF-8 once encoded. */
data class FetchedSubtitle(
    val variant: SubtitleVariant,
    val languageCode: String,
    val fileId: Long,
    val fileName: String?,
    val release: String?,
    val hashMatch: Boolean,
    val text: String,
) {
    val label: String? get() = variant.label

    fun utf8Bytes(): ByteArray = text.toByteArray(Charsets.UTF_8)

    override fun toString(): String =
        "FetchedSubtitle(variant=$variant, languageCode=$languageCode, fileId=$fileId, fileName=$fileName, " +
            "release=$release, hashMatch=$hashMatch, text=<${text.length} chars>)"
}

/** Outcome of [SubtitleFinder.find]; every case reports the [quota] as last known. */
sealed interface SubtitleSearch {
    val quota: Quota

    data class Found(
        val subtitle: FetchedSubtitle,
        override val quota: Quota,
    ) : SubtitleSearch

    /** No acceptable subtitle in any search; ADR-0005 §5 retries these after 7 days (#282). */
    data class NotFound(
        override val quota: Quota,
    ) : SubtitleSearch

    /** A subtitle was found but the day's downloads are spent; retry after [Quota.resetsAt]. */
    data class QuotaExhausted(
        override val quota: Quota,
    ) : SubtitleSearch

    data class Failed(
        val failure: OsFailure,
        override val quota: Quota,
    ) : SubtitleSearch
}

/**
 * Finds and downloads the best subtitle for one movie in one language (#281, ADR-0005 §5).
 *
 * Searches run in order -- moviehash, then IMDb id, then title and year -- skipping any the request
 * has no data for, and stop at the first that yields an acceptable subtitle ([SubtitleRanker]).
 * Spanish asks for Castilian and Latin-American together, but a Latin-American result is only taken
 * as a last resort: every search is tried for a Castilian one first, and only then does the best
 * Latin-American result across all of them win, labelled [SubtitleVariant.LATINO].
 *
 * Only the chosen file is downloaded, so a search costs one download of the daily quota at most.
 */
class SubtitleFinder(
    private val api: OpenSubtitlesApi,
) {
    suspend fun find(request: SubtitleRequest): SubtitleSearch {
        val codes = codesFor(request.language)
        val seen = mutableListOf<SubtitleCandidate>()
        for (query in queriesFor(request, codes)) {
            when (val result = api.search(query)) {
                is OsResult.Failure -> return SubtitleSearch.Failed(result.failure, api.quota.current)
                is OsResult.Success -> seen += result.value
            }
            val preferred = SubtitleRanker.best(seen.filter { preferredVariant(it, request.language) != null })
            if (preferred != null) return fetch(preferred, request.language)
        }
        val lastResort =
            if (request.language == SubtitleLanguage.SPANISH) {
                SubtitleRanker.best(seen.filter { it.languageCode in LATINO_CODES })
            } else {
                null
            }
        return lastResort?.let { fetch(it, request.language) } ?: SubtitleSearch.NotFound(api.quota.current)
    }

    private suspend fun fetch(
        candidate: SubtitleCandidate,
        language: SubtitleLanguage,
    ): SubtitleSearch {
        val link =
            when (val outcome = api.download(candidate.fileId)) {
                is DownloadOutcome.Link -> outcome
                is DownloadOutcome.QuotaExhausted -> return SubtitleSearch.QuotaExhausted(outcome.quota)
                is DownloadOutcome.Failed -> return SubtitleSearch.Failed(outcome.failure, api.quota.current)
            }
        val bytes =
            when (val result = api.fetch(link.link)) {
                is OsResult.Success -> result.value
                is OsResult.Failure -> return SubtitleSearch.Failed(result.failure, api.quota.current)
            }
        val subtitle =
            FetchedSubtitle(
                variant = preferredVariant(candidate, language) ?: SubtitleVariant.LATINO,
                languageCode = candidate.languageCode,
                fileId = candidate.fileId,
                fileName = link.fileName ?: candidate.fileName,
                release = candidate.release,
                hashMatch = candidate.hashMatch,
                text = SubtitleText.normalise(bytes),
            )
        return SubtitleSearch.Found(subtitle, api.quota.current)
    }

    internal companion object {
        /** OpenSubtitles' codes for English. */
        val ENGLISH_CODES = setOf("en")

        /** `es` is Spanish (Spain); `sp` is the "Spanish (EU)" code some listings use for the same. */
        val CASTILIAN_CODES = setOf("es", "sp")

        /** `ea`: Spanish (Latin America). */
        val LATINO_CODES = setOf("ea")

        fun codesFor(language: SubtitleLanguage): List<String> =
            when (language) {
                SubtitleLanguage.ENGLISH -> ENGLISH_CODES.toList()
                SubtitleLanguage.SPANISH -> (CASTILIAN_CODES + LATINO_CODES).toList()
            }

        /** The variant [candidate] counts as before the last resort, or null when it must wait for it. */
        fun preferredVariant(
            candidate: SubtitleCandidate,
            language: SubtitleLanguage,
        ): SubtitleVariant? =
            when {
                language == SubtitleLanguage.ENGLISH && candidate.languageCode in ENGLISH_CODES -> {
                    SubtitleVariant.ENGLISH
                }

                language == SubtitleLanguage.SPANISH && candidate.languageCode in CASTILIAN_CODES -> {
                    SubtitleVariant.CASTILIAN
                }

                else -> {
                    null
                }
            }

        /** Moviehash first, then IMDb id, then title (+ year): only those the request can fill. */
        fun queriesFor(
            request: SubtitleRequest,
            codes: List<String>,
        ): List<SearchQuery> =
            listOfNotNull(
                request.moviehash?.takeIf { it.isNotBlank() }?.let { SearchQuery(codes, moviehash = it) },
                request.imdbId
                    ?.takeIf { OpenSubtitlesApi.imdbDigits(it) != null }
                    ?.let { SearchQuery(codes, imdbId = it) },
                request.title?.takeIf { it.isNotBlank() }?.let { SearchQuery(codes, title = it, year = request.year) },
            )
    }
}
