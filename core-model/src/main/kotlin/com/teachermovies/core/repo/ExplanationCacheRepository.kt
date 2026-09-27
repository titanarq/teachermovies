package com.teachermovies.core.repo

/**
 * The persistent "Explicar" cache (#274; #292 is the consumer that asks the bridge and stores what
 * comes back). Claude explains a line in the context of a movie, with the prompt the bridge happens
 * to be running (ADR-0005 §7), so a row is keyed by the title, the line and that [promptVersion]: a
 * new prompt version starts fresh answers instead of serving ones an older prompt wrote. Only
 * successes are cached (§8) -- a hit means the bridge did answer for this line of this film, and the
 * daily cap is not spent again on it.
 *
 * The payload is the bridge's JSON verbatim; `:core-model` stores and returns it without reading it.
 * Both methods take [now] from the caller, as [TranslationCacheRepository] does.
 */
interface ExplanationCacheRepository {
    /**
     * The cached explanation JSON for [line] in [movieTitle] under [promptVersion], or null on a miss.
     * A hit counts as a use and refreshes the row.
     */
    suspend fun explanationFor(
        movieTitle: String,
        line: String,
        promptVersion: String,
        now: Long,
    ): String?

    /**
     * Caches one successful explanation, replacing any row for the same title, line and prompt
     * version, then prunes back inside the policy. A blank title, line or [explanationJson] is stored
     * as nothing at all; a blank [promptVersion] is a key part like any other.
     */
    suspend fun store(
        movieTitle: String,
        line: String,
        promptVersion: String,
        explanationJson: String,
        now: Long,
    )
}
