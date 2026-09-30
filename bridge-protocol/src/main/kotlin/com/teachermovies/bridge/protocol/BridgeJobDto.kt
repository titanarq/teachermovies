@file:OptIn(ExperimentalSerializationApi::class)

package com.teachermovies.bridge.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * `data` of an `event: job` frame of `GET /api/bridge/jobs` (#275, ADR-0005 §2): one typed job the
 * TV hands to the connected bridge. [id] is random and single-use; the bridge answers it once with
 * `POST /api/bridge/jobs/{id}/result` and a [BridgeJobResultDto]. The `kind` field names the type
 * (`translate` | `explain`), the key a bridge dispatches on (#277).
 */
@Serializable
@JsonClassDiscriminator("kind")
sealed interface BridgeJobDto {
    val id: String
}

/** Translate one captured English subtitle [line] into Castilian Spanish (ADR-0005 §6). */
@Serializable
@SerialName("translate")
data class TranslateJobDto(
    override val id: String,
    val line: String,
) : BridgeJobDto

/**
 * Explain the captured English [line] in short Castilian Spanish (ADR-0005 §7): [title] of the
 * movie when known, the English lines [before] and [after] it (oldest first) and the aligned
 * Spanish line [spanishLine] when there is one.
 */
@Serializable
@SerialName("explain")
data class ExplainJobDto(
    override val id: String,
    val title: String?,
    val line: String,
    val before: List<String>,
    val after: List<String>,
    val spanishLine: String?,
) : BridgeJobDto

/**
 * `data` of an `event: cancel` frame of `GET /api/bridge/jobs`: the TV no longer wants the answer
 * to job [id] (replaced by a newer job of the same kind, timed out, or its caller gave up), so the
 * bridge may stop working on it. A result posted for it afterwards is 409.
 */
@Serializable
data class BridgeCancelDto(
    val id: String,
)

/**
 * Body of `POST /api/bridge/jobs/{id}/result`: the bridge's one answer to a job, discriminated by
 * `status`.
 */
@Serializable
@JsonClassDiscriminator("status")
sealed interface BridgeJobResultDto {
    /**
     * The job succeeded: [text] is the translation, or for `explain` the explanation document in
     * whatever form the explain handler defines (#291) -- the TV hub passes it through untouched.
     */
    @Serializable
    @SerialName("ok")
    data class Done(
        val text: String,
    ) : BridgeJobResultDto

    /**
     * The job failed on the bridge: [code] is a short machine-readable reason (e.g. `rate_limited`,
     * `daily_cap`, `invalid_reply`) and [message] optional detail, never a secret.
     */
    @Serializable
    @SerialName("error")
    data class Failed(
        val code: String,
        val message: String? = null,
    ) : BridgeJobResultDto
}

/** Names and limits of the job stream, shared by the TV hub and the bridge. */
object BridgeJobProtocol {
    /** SSE event name of a frame carrying a [BridgeJobDto]. */
    const val JOB_EVENT: String = "job"

    /** SSE event name of a frame carrying a [BridgeCancelDto]. */
    const val CANCEL_EVENT: String = "cancel"

    /** Longest single subtitle line (`line` of a translate job, each of `before`/`after`, `spanishLine`, `title`) in a job, in chars. */
    const val MAX_LINE_CHARS: Int = 500

    /** Longest `line` of an `explain` job, in chars: the TV joins several phrases into it (ADR-0005 §7). */
    const val MAX_EXPLAIN_LINE_CHARS: Int = 1500

    /** Largest encoded job (the JSON `data` of its frame) and largest result body, in UTF-8 bytes. */
    const val MAX_PAYLOAD_BYTES: Int = 4 * 1024
}
