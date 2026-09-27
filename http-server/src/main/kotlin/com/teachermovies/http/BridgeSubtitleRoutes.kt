package com.teachermovies.http

import com.teachermovies.bridge.protocol.BridgeSubtitleProtocol
import com.teachermovies.bridge.protocol.SubtitleNeedDto
import com.teachermovies.bridge.protocol.SubtitleStatusDto
import com.teachermovies.bridge.protocol.SubtitleUploadedDto
import com.teachermovies.core.model.SubtitleFetch
import com.teachermovies.core.model.TorrentId
import com.teachermovies.http.auth.TokenScope
import com.teachermovies.http.auth.requireBearer
import com.teachermovies.http.bridge.bridgeJson
import com.teachermovies.http.dto.toNeedDto
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.first
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import java.io.File

/** A language tag on the wire: the two letters the downloaded file carries (`en`, `es`). */
private val LANGUAGE_PATTERN = Regex("[a-z]{2}")

private const val LANGUAGE_MESSAGE = "Language must be a two-letter tag such as en or es"

private const val STATUS_MESSAGE = "status must be one of searching, not_found or failed"

/** Longest `variant` label stored with a downloaded subtitle (`"latino"`, ADR-0005 §5). */
private const val MAX_VARIANT_CHARS = 32

/** The required multipart fields of `POST /api/bridge/subtitles`, as a `bad_request` lists them. */
private val UPLOAD_FIELDS: String =
    listOf(
        BridgeSubtitleProtocol.TORRENT_ID_FIELD,
        BridgeSubtitleProtocol.LANGUAGE_FIELD,
        BridgeSubtitleProtocol.FILE_FIELD,
    ).joinToString(", ")

/**
 * The bridge's automatic-subtitle routes (#280, ADR-0005 §5): what the TV still needs, the file a
 * search found, and what became of a search that found nothing. Bridge tokens only, in the
 * `Authorization` header only, exactly like the job routes of [bridgeRoutes] (ADR-0005 §4).
 *
 * The needs themselves are the fetch-state rows of #274 that a search may act on now
 * (`SubtitleFetch.isDue`, the one place the ADR's retry rules live) and whose movie is still in the
 * library. This route only publishes them: deciding that a completed movie needs a language at all,
 * and computing its moviehash (#279), stays with whoever writes those rows.
 */
internal fun Route.bridgeSubtitleRoutes(deps: ServerDeps) {
    requireBearer(deps.pairing, setOf(TokenScope.BRIDGE)) {
        get("/api/bridge/subtitle-needs") {
            call.respond(deps.subtitleNeeds())
        }
        post("/api/bridge/subtitles") {
            when (val upload = call.receiveBridgeSubtitleUpload()) {
                BridgeSubtitleUpload.Missing -> {
                    call.respondApiError(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "Expected multipart fields $UPLOAD_FIELDS"),
                    )
                }

                BridgeSubtitleUpload.TooLarge -> {
                    call.respondApiError(
                        HttpStatusCode.PayloadTooLarge,
                        ApiError("too_large", "A subtitle file may be at most 2 MiB"),
                    )
                }

                is BridgeSubtitleUpload.Ready -> {
                    call.storeBridgeSubtitle(deps, upload)
                }
            }
        }
        post("/api/bridge/subtitle-status") {
            val limit = BridgeSubtitleProtocol.MAX_STATUS_BYTES
            val bytes = call.receiveChannel().readRemaining(limit + 1L).readByteArray()
            if (bytes.size > limit) {
                call.respondApiError(
                    HttpStatusCode.BadRequest,
                    ApiError("too_large", "A status is at most $limit bytes"),
                )
                return@post
            }
            val status =
                try {
                    bridgeJson.decodeFromString(SubtitleStatusDto.serializer(), bytes.decodeToString())
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            if (status == null) {
                call.respondApiError(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        "bad_request",
                        "Body must be {\"torrentId\":...,\"language\":...,\"status\":\"searching|not_found|failed\"}",
                    ),
                )
                return@post
            }
            call.recordSubtitleStatus(deps, status)
        }
    }
}

/**
 * The needs list: every fetch row a search may act on at [ServerDeps.clock], most recently updated
 * first, joined with the title of the movie it is for. A row whose movie has left the library is
 * dropped rather than published -- there is no file left to subtitle.
 */
private suspend fun ServerDeps.subtitleNeeds(): List<SubtitleNeedDto> {
    val titles = library.observeLibrary().first().associate { it.id to it.title }
    val due = subtitleFetches.dueForFetch(clock())
    return due.mapNotNull { row -> titles[row.torrentId]?.let { row.toNeedDto(it) } }
}

private suspend fun ApplicationCall.storeBridgeSubtitle(
    deps: ServerDeps,
    upload: BridgeSubtitleUpload.Ready,
) {
    val id = torrentIdFieldOrRespond(upload.torrentId) ?: return
    val language = languageOrRespond(upload.language) ?: return
    val variant = upload.variant?.trim()?.takeIf { it.isNotEmpty() }
    if (variant != null && variant.length > MAX_VARIANT_CHARS) {
        respondApiError(
            HttpStatusCode.BadRequest,
            ApiError("bad_request", "The variant label is at most $MAX_VARIANT_CHARS chars"),
        )
        return
    }
    // The movie's own file names the subtitle (ADR-0005 §5), so a movie with no library row has
    // nothing to name it after -- and nothing to put the file next to.
    val item = deps.library.getLibraryItem(id)
    if (item == null) {
        respondUnknownTorrent()
        return
    }
    val fileName = BridgeSubtitleProtocol.fileName(File(item.mainFilePath).nameWithoutExtension, language)
    deps.subtitles.save(id.value, fileName, upload.bytes).fold(
        onSuccess = { absolutePath ->
            deps.recordDownloaded(id, language, absolutePath, variant)
            respond(
                HttpStatusCode.Created,
                SubtitleUploadedDto(path = "$SUBTITLES_SUBDIR/${File(absolutePath).name}"),
            )
        },
        onFailure = {
            respondApiError(
                HttpStatusCode.InternalServerError,
                ApiError("io_error", "Could not save the subtitle file"),
            )
        },
    )
}

/**
 * The movie's [language] row as downloaded, created when the bridge delivers a file for a need the
 * TV has no row for: the file is on disk and useful either way, and a `downloaded` row is what stops
 * the next pass searching for it again. [path] is absolute, as `SubtitleFetch.localPath` stores it.
 */
private suspend fun ServerDeps.recordDownloaded(
    id: TorrentId,
    language: String,
    path: String,
    variant: String?,
) {
    val now = clock()
    val row = subtitleFetches.get(id, language) ?: SubtitleFetch.pending(id, language, now = now)
    subtitleFetches.save(row.downloadedAt(now, path, variant))
}

private suspend fun ApplicationCall.recordSubtitleStatus(
    deps: ServerDeps,
    dto: SubtitleStatusDto,
) {
    val id = torrentIdFieldOrRespond(dto.torrentId) ?: return
    val language = languageOrRespond(dto.language) ?: return
    val now = deps.clock()
    val row = deps.subtitleFetches.get(id, language)
    if (row == null) {
        respondApiError(
            HttpStatusCode.NotFound,
            ApiError("unknown_need", "That movie and language are not on the needs list"),
        )
        return
    }
    val updated =
        when (dto.status) {
            BridgeSubtitleProtocol.STATUS_SEARCHING -> row.searchingAt(now)
            BridgeSubtitleProtocol.STATUS_NOT_FOUND -> row.notFoundAt(now)
            BridgeSubtitleProtocol.STATUS_FAILED -> row.failedAt(now, dto.message)
            else -> null
        }
    if (updated == null) {
        respondApiError(HttpStatusCode.BadRequest, ApiError("bad_request", STATUS_MESSAGE))
        return
    }
    deps.subtitleFetches.save(updated)
    respond(HttpStatusCode.NoContent)
}

/** [raw] as a [TorrentId], or a 400 `invalid_id` already sent and null. */
private suspend fun ApplicationCall.torrentIdFieldOrRespond(raw: String): TorrentId? =
    try {
        TorrentId(raw)
    } catch (_: IllegalArgumentException) {
        respondApiError(HttpStatusCode.BadRequest, ApiError("invalid_id", "Not a valid torrent id"))
        null
    }

/** [raw] as a lower-case two-letter language tag, or a 400 `invalid_language` already sent and null. */
private suspend fun ApplicationCall.languageOrRespond(raw: String): String? {
    val language = raw.lowercase()
    if (!LANGUAGE_PATTERN.matches(language)) {
        respondApiError(HttpStatusCode.BadRequest, ApiError("invalid_language", LANGUAGE_MESSAGE))
        return null
    }
    return language
}

private sealed interface BridgeSubtitleUpload {
    data class Ready(
        val torrentId: String,
        val language: String,
        val variant: String?,
        val bytes: ByteArray,
    ) : BridgeSubtitleUpload

    data object TooLarge : BridgeSubtitleUpload

    data object Missing : BridgeSubtitleUpload
}

/**
 * Reads the [BridgeSubtitleProtocol] parts of a `POST /api/bridge/subtitles` body, never holding
 * more than [BridgeSubtitleProtocol.MAX_SUBTITLE_BYTES] + 1 bytes of the file part in memory. A
 * non-multipart body, or one missing the torrent id, the language or the file, counts as
 * [BridgeSubtitleUpload.Missing]; the uploaded file's own name is never read, because the TV names
 * the file it writes.
 */
private suspend fun ApplicationCall.receiveBridgeSubtitleUpload(): BridgeSubtitleUpload {
    val limit = BridgeSubtitleProtocol.MAX_SUBTITLE_BYTES
    val multipart =
        try {
            receiveMultipart(formFieldLimit = limit + 1)
        } catch (_: ContentTransformationException) {
            return BridgeSubtitleUpload.Missing
        } catch (_: BadRequestException) {
            return BridgeSubtitleUpload.Missing
        }
    var torrentId: String? = null
    var language: String? = null
    var variant: String? = null
    var bytes: ByteArray? = null
    var tooLarge = false
    while (true) {
        val part = multipart.readPart() ?: break
        try {
            when {
                part is PartData.FormItem && part.name == BridgeSubtitleProtocol.TORRENT_ID_FIELD -> {
                    torrentId = part.value
                }

                part is PartData.FormItem && part.name == BridgeSubtitleProtocol.LANGUAGE_FIELD -> {
                    language = part.value
                }

                part is PartData.FormItem && part.name == BridgeSubtitleProtocol.VARIANT_FIELD -> {
                    variant = part.value
                }

                part is PartData.FileItem && part.name == BridgeSubtitleProtocol.FILE_FIELD -> {
                    val data = part.provider().readRemaining(limit + 1).readByteArray()
                    if (data.size > limit) tooLarge = true else bytes = data
                }

                else -> {
                    Unit
                }
            }
        } finally {
            part.dispose()
        }
    }
    if (tooLarge) return BridgeSubtitleUpload.TooLarge
    val id = torrentId
    val tag = language
    val data = bytes
    return if (id.isNullOrBlank() || tag.isNullOrBlank() || data == null) {
        BridgeSubtitleUpload.Missing
    } else {
        BridgeSubtitleUpload.Ready(id, tag, variant, data)
    }
}
