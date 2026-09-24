package com.teachermovies.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.resolveResource
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Classpath folder (`src/main/resources/web/`) holding the phone web UI (#63). */
internal const val WEB_UI_RESOURCES = "web"

/**
 * The phone web UI (#63): `GET /` is `web/index.html`, `GET /static/<file>` any other file in
 * [WEB_UI_RESOURCES]. Public (ADR-0002): the page holds no data; it runs the pairing flow and sends
 * the token itself on every protected API call.
 */
internal fun Route.webUiRoutes() {
    get("/") {
        val index = call.resolveResource("index.html", WEB_UI_RESOURCES)
        if (index == null) call.respond(HttpStatusCode.NotFound) else call.respond(index)
    }
    staticResources("/static", WEB_UI_RESOURCES, index = null)
}
