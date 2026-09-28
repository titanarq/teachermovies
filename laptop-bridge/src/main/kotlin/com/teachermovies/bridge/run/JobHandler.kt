package com.teachermovies.bridge.run

import com.teachermovies.bridge.protocol.BridgeJobDto
import com.teachermovies.bridge.protocol.BridgeJobResultDto
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What one job `kind` does on the laptop (#277): the translate and explain handlers of #286/#291
 * implement this and join [JobHandlerRegistry], and the run loop never changes for them.
 *
 * [handle] gets the typed job of its own [kind] and returns the one result the loop posts back. It
 * may take as long as the TV's timeout allows: it runs in a coroutine of its own and is cancelled
 * when the TV sends `cancel` for the job or the stream it came on ends. A thrown exception becomes
 * a `handler_error` result, so a bug still answers the TV rather than leaving it waiting.
 */
interface JobHandler {
    /** The `kind` discriminator of the jobs this handler takes (`translate`, `explain`). */
    val kind: String

    suspend fun handle(job: BridgeJobDto): BridgeJobResultDto
}

/**
 * The job handlers of this bridge, keyed by `kind`: `run` builds it from
 * [com.teachermovies.bridge.cli.JobHandlers] (#291: explain; #286 adds translate). [dispatch] answers
 * a kind nobody handles -- every kind, when the laptop has no usable Claude CLI -- with an immediate
 * [BridgeJobResultDto.Failed], code [UNSUPPORTED_KIND], instead of leaving the TV to time out.
 */
class JobHandlerRegistry(
    handlers: List<JobHandler>,
) {
    private val byKind: Map<String, JobHandler> =
        handlers.associateBy { it.kind }.also {
            require(it.size == handlers.size) { "two job handlers share one kind" }
        }

    val kinds: Set<String> get() = byKind.keys

    /**
     * Runs the handler for [job]'s `kind` and returns its result, or a [BridgeJobResultDto.Failed]:
     * [UNSUPPORTED_KIND] when no handler takes it (a kind this bridge does not even know decodes no
     * further), [BAD_JOB] when the handler's kind is known but the job does not decode, and
     * [HANDLER_ERROR] when the handler throws. Never throws except for cancellation.
     */
    suspend fun dispatch(job: JsonObject): BridgeJobResultDto {
        val kind = (job[KIND_FIELD] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val handler =
            kind?.let { byKind[it] }
                ?: return BridgeJobResultDto.Failed(
                    code = UNSUPPORTED_KIND,
                    message = "este puente no atiende trabajos '${kind?.take(KIND_ECHO_CHARS)}'",
                )
        val typed =
            try {
                JSON.decodeFromJsonElement(BridgeJobDto.serializer(), job)
            } catch (_: SerializationException) {
                return BridgeJobResultDto.Failed(code = BAD_JOB, message = "trabajo '$kind' ilegible")
            } catch (_: IllegalArgumentException) {
                return BridgeJobResultDto.Failed(code = BAD_JOB, message = "trabajo '$kind' ilegible")
            }
        return try {
            handler.handle(typed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BridgeJobResultDto.Failed(code = HANDLER_ERROR, message = e::class.simpleName)
        }
    }

    companion object {
        /** Result code for a job whose `kind` has no handler here. */
        const val UNSUPPORTED_KIND = "unsupported_kind"

        /** Result code for a job of a handled kind whose fields do not decode. */
        const val BAD_JOB = "bad_job"

        /** Result code for a handler that threw. */
        const val HANDLER_ERROR = "handler_error"

        /** A registry with no handler at all, for tests of the loop itself. */
        fun default(): JobHandlerRegistry = JobHandlerRegistry(emptyList())

        private const val KIND_FIELD = "kind"
        private const val KIND_ECHO_CHARS = 40

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
