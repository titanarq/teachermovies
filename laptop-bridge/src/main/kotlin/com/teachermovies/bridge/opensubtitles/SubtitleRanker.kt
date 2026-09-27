package com.teachermovies.bridge.opensubtitles

/**
 * Orders search results best-first (#281, ADR-0005 §5).
 *
 * Excluded outright: machine-translated and AI-translated subtitles (a learner must not study a
 * machine's English or Spanish) and foreign-parts-only files (they cover only the dialogue in another
 * language). The rest are ordered by: a moviehash match (the subtitle
 * was timed for this very file), then not hearing-impaired (no `[door slams]` lines), then a
 * trusted uploader, then the download count.
 */
object SubtitleRanker {
    private val ORDER: Comparator<SubtitleCandidate> =
        compareByDescending<SubtitleCandidate> { it.hashMatch }
            .thenBy { it.hearingImpaired }
            .thenByDescending { it.fromTrusted }
            .thenByDescending { it.downloadCount }

    fun isAcceptable(candidate: SubtitleCandidate): Boolean =
        !candidate.machineTranslated && !candidate.aiTranslated && !candidate.foreignPartsOnly

    /** The acceptable [candidates], best first; the sort is stable, so ties keep the server's order. */
    fun rank(candidates: List<SubtitleCandidate>): List<SubtitleCandidate> =
        candidates.filter(::isAcceptable).sortedWith(ORDER)

    fun best(candidates: List<SubtitleCandidate>): SubtitleCandidate? = rank(candidates).firstOrNull()
}
