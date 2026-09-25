package com.teachermovies.mobile.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * A real Ktor CIO server on a loopback port (`port = 0`, read back from the resolved connectors)
 * that answers every request with the one fixed [Answer] set by [answer], and records what it got.
 */
class LoopbackTvServer : Closeable {
    data class Answer(
        val status: HttpStatusCode,
        val json: String,
    )

    data class Received(
        val method: String,
        val path: String,
        val query: String,
        val authorization: String?,
        val body: String,
    )

    private val next =
        AtomicReference(Answer(HttpStatusCode.NotFound, """{"error":"not_found","message":"no answer set"}"""))

    val received: MutableList<Received> = CopyOnWriteArrayList()

    private val server: EmbeddedServer<*, *> =
        embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                route("{...}") {
                    handle {
                        received +=
                            Received(
                                method = call.request.httpMethod.value,
                                path = call.request.path(),
                                query =
                                    call.request.local.uri
                                        .substringAfter('?', ""),
                                authorization = call.request.headers[HttpHeaders.Authorization],
                                body = call.receiveText(),
                            )
                        val answer = next.get()
                        call.respondText(answer.json, ContentType.Application.Json, answer.status)
                    }
                }
            }
        }.start(wait = false)

    val baseUrl: String by lazy {
        val port =
            kotlinx.coroutines.runBlocking {
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        "http://127.0.0.1:$port"
    }

    fun answer(
        status: HttpStatusCode,
        json: String,
    ) {
        next.set(Answer(status, json))
    }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }
}
