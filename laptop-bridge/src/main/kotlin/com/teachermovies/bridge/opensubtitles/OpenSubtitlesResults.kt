package com.teachermovies.bridge.opensubtitles

import java.time.Instant

/** Outcome of an [OpenSubtitlesApi] call. No method of that class throws. */
sealed interface OsResult<out T> {
    data class Success<T>(
        val value: T,
    ) : OsResult<T>

    data class Failure(
        val failure: OsFailure,
    ) : OsResult<Nothing>
}

/** Why an OpenSubtitles call did not succeed. No case carries a credential, a JWT or a body. */
sealed interface OsFailure {
    /** `POST /login` answered 401: the username or password in the credentials file is wrong. */
    data object LoginRefused : OsFailure

    /** 401 or 403 even with a fresh JWT: the API key is not accepted. */
    data object Unauthorized : OsFailure

    /** 429: too many requests per second; the caller retries later. */
    data object RateLimited : OsFailure

    /** Any other non-2xx answer. */
    data class Http(
        val status: Int,
    ) : OsFailure

    /** OpenSubtitles could not be reached or answered something undecodable. */
    data class Network(
        val reason: String,
    ) : OsFailure
}

/**
 * The daily download quota as OpenSubtitles last reported it (#281). Every field is null until an
 * answer carried it: [remaining] and [resetsAt] come from `POST /download` and `GET /infos/user`,
 * [allowed] from `POST /login` and `GET /infos/user`.
 */
data class Quota(
    val remaining: Int? = null,
    val allowed: Int? = null,
    val resetsAt: Instant? = null,
) {
    /**
     * True when OpenSubtitles said no download is left and the reset it announced is still ahead of
     * [now]: downloading again before then would only earn another refusal. Without a known reset
     * time the server is asked again.
     */
    fun isExhausted(now: Instant): Boolean {
        val left = remaining ?: return false
        val reset = resetsAt ?: return false
        return left <= 0 && now.isBefore(reset)
    }
}

/**
 * Holds the latest [Quota]. Each report only replaces the fields it carries, so a login (which
 * knows `allowed` but not `remaining`) does not forget what the last download said.
 */
class QuotaTracker {
    @Volatile
    var current: Quota = Quota()
        private set

    @Synchronized
    fun update(
        remaining: Int? = null,
        allowed: Int? = null,
        resetsAt: Instant? = null,
    ) {
        current =
            Quota(
                remaining = remaining ?: current.remaining,
                allowed = allowed ?: current.allowed,
                resetsAt = resetsAt ?: current.resetsAt,
            )
    }
}

/** One subtitle file a search returned, reduced to what ranking and downloading need. */
data class SubtitleCandidate(
    val fileId: Long,
    val fileName: String?,
    /** OpenSubtitles' language code: `en`, `es` (Castilian), `ea` (Latin-American), ... */
    val languageCode: String,
    val hearingImpaired: Boolean = false,
    val machineTranslated: Boolean = false,
    val aiTranslated: Boolean = false,
    val foreignPartsOnly: Boolean = false,
    /** OpenSubtitles says this subtitle was uploaded for a file with the searched moviehash. */
    val hashMatch: Boolean = false,
    val fromTrusted: Boolean = false,
    val downloadCount: Int = 0,
    val release: String? = null,
)

/** Outcome of [OpenSubtitlesApi.download]. */
sealed interface DownloadOutcome {
    /** [link] is a temporary URL to the file itself. */
    data class Link(
        val link: String,
        val fileName: String?,
    ) : DownloadOutcome

    /** 406, or the tracked quota is already exhausted: nothing was downloaded. */
    data class QuotaExhausted(
        val quota: Quota,
    ) : DownloadOutcome

    data class Failed(
        val failure: OsFailure,
    ) : DownloadOutcome
}
