package com.teachermovies.http.bridge

import com.teachermovies.bridge.protocol.BridgeJobDto
import com.teachermovies.bridge.protocol.BridgeJobProtocol
import com.teachermovies.bridge.protocol.BridgeJobResultDto
import com.teachermovies.bridge.protocol.ExplainJobDto
import com.teachermovies.bridge.protocol.TranslateJobDto
import com.teachermovies.core.model.TorrentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration

/** The one JSON config of the job stream: every nullable field is written, as in Module.kt. */
internal val bridgeJson = Json { explicitNulls = true }

/** How many resolved job ids the hub remembers, so a late result is 409 rather than 404. */
private const val CLOSED_IDS_REMEMBERED = 256

/** One frame the hub queues for the bridge stream. */
internal sealed interface BridgeStreamEvent {
    data class Job(
        val job: BridgeJobDto,
    ) : BridgeStreamEvent

    data class Cancel(
        val id: String,
    ) : BridgeStreamEvent

    /**
     * A nudge to read `GET /api/bridge/subtitle-needs` again (#280); [torrentId] names the movie
     * whose need changed when the caller knows it, and is null for "the whole list may have".
     */
    data class SubtitlesNeeded(
        val torrentId: String?,
    ) : BridgeStreamEvent
}

/**
 * One open `GET /api/bridge/jobs` connection. The route writes every [events] frame and, when the
 * connection ends, hands it back to [BridgeJobHub.disconnect]. [events] closes when a newer
 * stream replaces this one.
 */
internal class BridgeStream {
    private val queue = Channel<BridgeStreamEvent>(Channel.UNLIMITED)
    val events: ReceiveChannel<BridgeStreamEvent> get() = queue

    fun send(event: BridgeStreamEvent) {
        queue.trySend(event)
    }

    fun close() {
        queue.close()
    }
}

/** What [BridgeJobHub.complete] did with a posted result. */
internal enum class CompleteResult {
    /** The job was waiting: its caller now has the outcome (204). */
    ACCEPTED,

    /** No job with that id was ever handed out, or it was forgotten long ago (404). */
    UNKNOWN,

    /** The job already ended -- answered, replaced, timed out, cancelled or disconnected (409). */
    CLOSED,
}

/**
 * The TV's job hub (#275, ADR-0005 §2): the [AssistantBridge] behind `/api/bridge/jobs`.
 *
 * - With no bridge stream open, [submit] answers [BridgeOutcome.NoBridge] at once.
 * - The newest stream wins: [connect] closes the previous stream, whose in-flight jobs resolve
 *   [BridgeOutcome.Disconnected]; [connected] stays true throughout.
 * - One job per [BridgeJob.slot]: a newer one resolves the older [BridgeOutcome.Replaced].
 * - Whenever a job ends without an answer while its stream is still open (replaced, timed out, its
 *   caller cancelled) the bridge gets an `event: cancel` for it.
 * - The same stream carries the `subtitles-needed` nudge of #280, which [notifySubtitlesNeeded]
 *   sends and which no job waits on.
 *
 * Ids are 16 random bytes, base64url without padding, and single-use. Thread-safe: every piece of
 * state is touched only under one lock, which never suspends.
 */
class BridgeJobHub internal constructor(
    private val newId: () -> String,
) : AssistantBridge {
    constructor(random: SecureRandom = SecureRandom()) : this({ randomJobId(random) })

    private val lock = Any()
    private val state = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = state.asStateFlow()

    private var stream: BridgeStream? = null
    private val pending = mutableMapOf<String, PendingJob>()
    private val slots = mutableMapOf<String, String>()
    private val closedIds = LinkedHashSet<String>()

    private class PendingJob(
        val id: String,
        val slot: String,
        val stream: BridgeStream,
    ) {
        val outcome = CompletableDeferred<BridgeOutcome>()
    }

    override suspend fun submit(
        job: BridgeJob,
        timeout: Duration,
    ): BridgeOutcome {
        val pendingJob =
            synchronized(lock) {
                val current = stream ?: return BridgeOutcome.NoBridge
                val id = newId()
                val dto = job.toDto(id)
                violation(job, dto)?.let { return BridgeOutcome.Rejected(it) }
                slots[job.slot]?.let { olderId -> pending[olderId] }?.let { older ->
                    endLocked(older, BridgeOutcome.Replaced)
                    older.stream.send(BridgeStreamEvent.Cancel(older.id))
                }
                val created = PendingJob(id, job.slot, current)
                pending[id] = created
                slots[job.slot] = id
                current.send(BridgeStreamEvent.Job(dto))
                created
            }
        try {
            return withTimeoutOrNull(timeout) { pendingJob.outcome.await() }
                ?: abandon(pendingJob, BridgeOutcome.TimedOut)
        } catch (e: CancellationException) {
            abandon(pendingJob, BridgeOutcome.TimedOut) // nobody reads it: the caller is gone
            throw e
        }
    }

    /**
     * Ends [job] from the caller's side (timeout or cancellation) and tells the bridge. If an answer
     * won the race, that answer is returned instead.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun abandon(
        job: PendingJob,
        outcome: BridgeOutcome,
    ): BridgeOutcome =
        synchronized(lock) {
            if (pending[job.id] === job) {
                endLocked(job, outcome)
                job.stream.send(BridgeStreamEvent.Cancel(job.id))
            }
            job.outcome.getCompleted()
        }

    /**
     * Tells the connected bridge that the subtitle-needs list changed (#280, ADR-0005 §5): a movie
     * finished downloading, or the phone asked for a retry now (#285). The frame is a nudge only --
     * the bridge answers it by reading `GET /api/bridge/subtitle-needs` again. With no stream open
     * this does nothing and nothing is lost: a bridge reads the list when it connects (#282).
     */
    fun notifySubtitlesNeeded(torrentId: TorrentId? = null) {
        synchronized(lock) {
            stream?.send(BridgeStreamEvent.SubtitlesNeeded(torrentId?.value))
        }
    }

    /** Opens a new bridge stream, replacing (and closing) any older one. */
    internal fun connect(): BridgeStream =
        synchronized(lock) {
            stream?.let { older -> dropLocked(older) }
            val opened = BridgeStream()
            stream = opened
            state.value = true
            opened
        }

    /** The route is done with [closing]: its in-flight jobs resolve Disconnected. */
    internal fun disconnect(closing: BridgeStream) {
        synchronized(lock) {
            if (stream === closing) {
                dropLocked(closing)
                stream = null
                state.value = false
            }
        }
    }

    /**
     * Ends the open bridge stream, if any, as if the bridge had hung up: its in-flight jobs resolve
     * Disconnected and [connected] turns false. "Olvidar portátil" (#289) calls it right after
     * forgetting the bridge tokens, so the laptop's reconnect is refused instead of the old stream
     * staying open on a token that no longer exists.
     */
    fun disconnectBridge() {
        synchronized(lock) {
            stream?.let { current ->
                dropLocked(current)
                stream = null
                state.value = false
            }
        }
    }

    /** Resolves job [id] with the bridge's [result]. */
    internal fun complete(
        id: String,
        result: BridgeJobResultDto,
    ): CompleteResult =
        synchronized(lock) {
            val job = pending[id] ?: return if (id in closedIds) CompleteResult.CLOSED else CompleteResult.UNKNOWN
            val outcome =
                when (result) {
                    is BridgeJobResultDto.Done -> BridgeOutcome.Done(result.text)
                    is BridgeJobResultDto.Failed -> BridgeOutcome.BridgeError(result.code, result.message)
                }
            endLocked(job, outcome)
            CompleteResult.ACCEPTED
        }

    private fun dropLocked(closing: BridgeStream) {
        closing.close()
        pending.values
            .filter { it.stream === closing }
            .forEach { endLocked(it, BridgeOutcome.Disconnected) }
    }

    private fun endLocked(
        job: PendingJob,
        outcome: BridgeOutcome,
    ) {
        pending.remove(job.id)
        if (slots[job.slot] == job.id) slots.remove(job.slot)
        closedIds += job.id
        if (closedIds.size > CLOSED_IDS_REMEMBERED) closedIds.remove(closedIds.first())
        job.outcome.complete(outcome)
    }
}

private fun BridgeJob.toDto(id: String): BridgeJobDto =
    when (this) {
        is BridgeJob.Translate -> {
            TranslateJobDto(id = id, line = line)
        }

        is BridgeJob.Explain -> {
            ExplainJobDto(
                id = id,
                title = title,
                line = line,
                before = before,
                after = after,
                spanishLine = spanishLine,
            )
        }
    }

/** Why [job] breaks the per-job limits, or null when it is within them. */
private fun violation(
    job: BridgeJob,
    dto: BridgeJobDto,
): String? {
    val lines =
        when (job) {
            is BridgeJob.Translate -> listOf(job.line)
            is BridgeJob.Explain -> listOfNotNull(job.title, job.line, job.spanishLine) + job.before + job.after
        }
    if (lines.any { it.length > BridgeJobProtocol.MAX_LINE_CHARS }) {
        return "a line is over ${BridgeJobProtocol.MAX_LINE_CHARS} chars"
    }
    val bytes = bridgeJson.encodeToString(BridgeJobDto.serializer(), dto).toByteArray(Charsets.UTF_8).size
    if (bytes > BridgeJobProtocol.MAX_PAYLOAD_BYTES) {
        return "the job is over ${BridgeJobProtocol.MAX_PAYLOAD_BYTES} bytes"
    }
    return null
}

private fun randomJobId(random: SecureRandom): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
