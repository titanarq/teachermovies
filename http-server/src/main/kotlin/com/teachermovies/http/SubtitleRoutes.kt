package com.teachermovies.http

import com.teachermovies.core.model.TorrentId
import com.teachermovies.http.auth.requireBearer
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.ContentTransformationException
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import java.io.File

/** Largest subtitle file accepted by `POST /api/subtitles`; anything bigger is 413 `too_large`. */
const val MAX_SUBTITLE_FILE_BYTES: Long = 2L * 1024 * 1024

/** Name of the multipart field carrying the target torrent's id. */
const val SUBTITLE_TORRENT_ID_FIELD = "torrentId"

/** Name of the multipart field carrying the subtitle file itself. */
const val SUBTITLE_FILE_FIELD = "file"

/** File extensions `POST /api/subtitles` accepts, compared case-insensitively. */
val ALLOWED_SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt")

/** Success body of `POST /api/subtitles`: 201 `{"path":"subs/<name>"}`. */
@Serializable
data class SubtitleUploadResponse(
    val path: String,
)

/**
 * `POST /api/subtitles` (#61), behind [requireBearer] (ADR-0002): a phone uploads a `.srt`/`.ass`/
 * `.ssa`/`.vtt` file for a torrent, which lands in that torrent's own `subs/` directory through
 * [ServerDeps.subtitles] so the player can later load it (#77).
 */
internal fun Route.subtitleRoutes(deps: ServerDeps) {
    requireBearer(deps.pairing) {
        post("/api/subtitles") {
            when (val upload = call.receiveSubtitleUpload()) {
                SubtitleUpload.Missing -> {
                    call.respondApiError(
                        HttpStatusCode.BadRequest,
                        ApiError(
                            "bad_request",
                            "Expected multipart fields \"$SUBTITLE_TORRENT_ID_FIELD\" and \"$SUBTITLE_FILE_FIELD\"",
                        ),
                    )
                }

                SubtitleUpload.TooLarge -> {
                    call.respondApiError(
                        HttpStatusCode.PayloadTooLarge,
                        ApiError("too_large", "A subtitle file may be at most 2 MiB"),
                    )
                }

                is SubtitleUpload.Ready -> {
                    call.handleSubtitleUpload(deps, upload)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.handleSubtitleUpload(
    deps: ServerDeps,
    upload: SubtitleUpload.Ready,
) {
    val id =
        try {
            TorrentId(upload.torrentId)
        } catch (_: IllegalArgumentException) {
            respondApiError(HttpStatusCode.BadRequest, ApiError("invalid_id", "Not a valid torrent id"))
            return
        }
    if (deps.snapshotOf(id) == null) {
        respondUnknownTorrent()
        return
    }
    val extension = upload.fileName.substringAfterLast('.', "").lowercase()
    if (extension !in ALLOWED_SUBTITLE_EXTENSIONS) {
        respondApiError(
            HttpStatusCode.BadRequest,
            ApiError(
                "unsupported_subtitle",
                "Subtitle file must be one of ${ALLOWED_SUBTITLE_EXTENSIONS.sorted().joinToString()}",
            ),
        )
        return
    }
    deps.subtitles.save(id.value, upload.fileName, upload.bytes).fold(
        onSuccess = { absolutePath ->
            respond(
                HttpStatusCode.Created,
                SubtitleUploadResponse(path = "$SUBTITLES_SUBDIR/${File(absolutePath).name}"),
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

private sealed interface SubtitleUpload {
    data class Ready(
        val torrentId: String,
        val fileName: String,
        val bytes: ByteArray,
    ) : SubtitleUpload

    data object TooLarge : SubtitleUpload

    data object Missing : SubtitleUpload
}

/**
 * Reads the [SUBTITLE_TORRENT_ID_FIELD] and [SUBTITLE_FILE_FIELD] parts, never holding more than
 * [MAX_SUBTITLE_FILE_BYTES] + 1 bytes of the file part in memory. A non-multipart body, or one
 * missing either field, counts as [SubtitleUpload.Missing].
 */
private suspend fun ApplicationCall.receiveSubtitleUpload(): SubtitleUpload {
    val multipart =
        try {
            receiveMultipart(formFieldLimit = MAX_SUBTITLE_FILE_BYTES + 1)
        } catch (_: ContentTransformationException) {
            return SubtitleUpload.Missing
        } catch (_: BadRequestException) {
            return SubtitleUpload.Missing
        }
    var torrentId: String? = null
    var fileName: String? = null
    var bytes: ByteArray? = null
    var tooLarge = false
    while (true) {
        val part = multipart.readPart() ?: break
        try {
            when {
                part is PartData.FormItem && part.name == SUBTITLE_TORRENT_ID_FIELD -> {
                    torrentId = part.value
                }

                part is PartData.FileItem && part.name == SUBTITLE_FILE_FIELD -> {
                    fileName = part.originalFileName
                    val data = part.provider().readRemaining(MAX_SUBTITLE_FILE_BYTES + 1).readByteArray()
                    if (data.size > MAX_SUBTITLE_FILE_BYTES) tooLarge = true else bytes = data
                }

                else -> {
                    Unit
                }
            }
        } finally {
            part.dispose()
        }
    }
    if (tooLarge) return SubtitleUpload.TooLarge
    val id = torrentId
    val name = fileName
    val data = bytes
    return if (id.isNullOrBlank() || name.isNullOrBlank() || data == null) {
        SubtitleUpload.Missing
    } else {
        SubtitleUpload.Ready(id, name, data)
    }
}
