package com.teachermovies.http

import com.teachermovies.core.model.TorrentId
import com.teachermovies.http.auth.requireBearer
import com.teachermovies.http.dto.toDto
import com.teachermovies.torrent.api.EngineError
import com.teachermovies.torrent.api.EngineResult
import com.teachermovies.torrent.api.FilePriority
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

/** Largest `.torrent` accepted by `POST /api/torrents/file`; anything bigger is 413 `too_large`. */
const val MAX_TORRENT_FILE_BYTES: Long = 10L * 1024 * 1024

/** Name of the multipart field that carries the `.torrent` file. */
const val TORRENT_FILE_FIELD = "torrent"

/** Body of `POST /api/torrents/magnet`. */
@Serializable
data class AddMagnetRequest(
    val magnet: String,
)

/** Success body of both add routes: 201 `{"id":"<hash>","state":"fetching_metadata"}`. */
@Serializable
data class AddTorrentResponse(
    val id: String,
    val state: String,
)

/**
 * Mutating `/api/torrents*` routes (#60), all behind [requireBearer] (ADR-0002). Engine failures go
 * through [toHttp]; request-shape errors are 400 `bad_request`.
 */
internal fun Route.torrentWriteRoutes(deps: ServerDeps) {
    requireBearer(deps.pairing) {
        post("/api/torrents/magnet") {
            val request = call.receiveOrNull<AddMagnetRequest>()
            if (request == null) {
                call.respondApiError(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "Expected {\"magnet\":\"magnet:?...\"}"),
                )
                return@post
            }
            call.respondAdded(deps, deps.engine.addMagnet(request.magnet))
        }
        post("/api/torrents/file") {
            when (val upload = call.receiveTorrentUpload()) {
                is TorrentUpload.Bytes -> {
                    call.respondAdded(deps, deps.engine.addTorrentFile(upload.bytes))
                }

                TorrentUpload.TooLarge -> {
                    call.respondApiError(
                        HttpStatusCode.PayloadTooLarge,
                        ApiError("too_large", "A .torrent file may be at most 10 MiB"),
                    )
                }

                TorrentUpload.Missing -> {
                    call.respondApiError(
                        HttpStatusCode.BadRequest,
                        ApiError("bad_request", "Expected a multipart field \"$TORRENT_FILE_FIELD\""),
                    )
                }
            }
        }
        post("/api/torrents/{id}/pause") {
            val id = call.torrentIdOrRespond() ?: return@post
            call.respondNoContentOr(deps.engine.pause(id))
        }
        post("/api/torrents/{id}/resume") {
            val id = call.torrentIdOrRespond() ?: return@post
            call.respondNoContentOr(deps.engine.resume(id))
        }
        delete("/api/torrents/{id}") {
            val id = call.torrentIdOrRespond() ?: return@delete
            val deleteFiles =
                when (call.request.queryParameters["deleteFiles"]) {
                    null, "false" -> {
                        false
                    }

                    "true" -> {
                        true
                    }

                    else -> {
                        call.respondApiError(
                            HttpStatusCode.BadRequest,
                            ApiError("bad_request", "deleteFiles must be true or false"),
                        )
                        return@delete
                    }
                }
            call.respondNoContentOr(deps.engine.remove(id, deleteFiles))
        }
        put("/api/torrents/{id}/files") {
            val id = call.torrentIdOrRespond() ?: return@put
            val snapshot = deps.snapshotOf(id) ?: return@put call.respondUnknownTorrent()
            if (!snapshot.hasMetadata) return@put call.respondEngineError(EngineError.NotReady)
            val changes = call.receiveOrNull<List<FilePriorityChange>>()
            if (changes == null) {
                call.respondApiError(
                    HttpStatusCode.BadRequest,
                    ApiError("bad_request", "Expected [{\"index\":0,\"priority\":\"skip|normal|high\"}]"),
                )
                return@put
            }
            val files =
                when (val result = deps.engine.files(id)) {
                    is EngineResult.Ok -> result.value
                    is EngineResult.Failure -> return@put call.respondEngineError(result.error)
                }
            val knownIndices = files.map { it.index }.toSet()
            val priorities = mutableMapOf<Int, FilePriority>()
            for (change in changes) {
                if (change.index !in knownIndices) {
                    call.respondApiError(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_index", "No file with index ${change.index}"),
                    )
                    return@put
                }
                val priority = change.priority.toFilePriority()
                if (priority == null) {
                    call.respondApiError(
                        HttpStatusCode.BadRequest,
                        ApiError("invalid_priority", "Priority must be skip, normal or high"),
                    )
                    return@put
                }
                priorities[change.index] = priority
            }
            call.respondNoContentOr(deps.engine.setFilePriorities(id, priorities))
        }
    }
}

/** One element of the `PUT /api/torrents/{id}/files` body; [priority] is `skip`|`normal`|`high`. */
@Serializable
data class FilePriorityChange(
    val index: Int,
    val priority: String,
)

/** The inverse of `FileDto.priority`: lower-case names only, anything else is `null`. */
private fun String.toFilePriority(): FilePriority? = FilePriority.entries.firstOrNull { it.name.lowercase() == this }

/** 204 on success, else the engine error mapped by [toHttp] (e.g. 404 `unknown_torrent`). */
private suspend fun ApplicationCall.respondNoContentOr(result: EngineResult<Unit>) {
    when (result) {
        is EngineResult.Ok -> respond(HttpStatusCode.NoContent)
        is EngineResult.Failure -> respondEngineError(result.error)
    }
}

/** 201 with the new torrent's id and current state, or the engine error mapped by [toHttp]. */
private suspend fun ApplicationCall.respondAdded(
    deps: ServerDeps,
    result: EngineResult<TorrentId>,
) {
    when (result) {
        is EngineResult.Ok -> {
            val state = deps.snapshotOf(result.value)?.toDto()?.state ?: FETCHING_METADATA
            respond(HttpStatusCode.Created, AddTorrentResponse(result.value.value, state))
        }

        is EngineResult.Failure -> {
            respondEngineError(result.error)
        }
    }
}

/** State reported for a torrent the engine accepted but does not list yet. */
private const val FETCHING_METADATA = "fetching_metadata"

/** The JSON body as [T], or `null` if it is missing or does not match. */
internal suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? =
    try {
        receive<T>()
    } catch (_: ContentTransformationException) {
        null
    } catch (_: BadRequestException) {
        null
    }

private sealed interface TorrentUpload {
    class Bytes(
        val bytes: ByteArray,
    ) : TorrentUpload

    data object TooLarge : TorrentUpload

    data object Missing : TorrentUpload
}

/**
 * Reads the [TORRENT_FILE_FIELD] part, never holding more than [MAX_TORRENT_FILE_BYTES] + 1 bytes
 * of it in memory. A non-multipart body counts as [TorrentUpload.Missing].
 */
private suspend fun ApplicationCall.receiveTorrentUpload(): TorrentUpload {
    val multipart =
        try {
            receiveMultipart(formFieldLimit = MAX_TORRENT_FILE_BYTES + 1)
        } catch (_: ContentTransformationException) {
            return TorrentUpload.Missing
        } catch (_: BadRequestException) {
            return TorrentUpload.Missing
        }
    var upload: TorrentUpload = TorrentUpload.Missing
    while (upload == TorrentUpload.Missing) {
        val part = multipart.readPart() ?: break
        try {
            if (part is PartData.FileItem && part.name == TORRENT_FILE_FIELD) {
                val bytes = part.provider().readRemaining(MAX_TORRENT_FILE_BYTES + 1).readByteArray()
                upload = if (bytes.size > MAX_TORRENT_FILE_BYTES) TorrentUpload.TooLarge else TorrentUpload.Bytes(bytes)
            }
        } finally {
            part.dispose()
        }
    }
    return upload
}
