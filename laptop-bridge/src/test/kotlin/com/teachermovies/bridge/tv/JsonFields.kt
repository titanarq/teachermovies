package com.teachermovies.bridge.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A string field of a JSON body, or null when [this] is not JSON or has no such field: how the tests
 * read what the bridge sent (and what [FakeTv] received) without a second model of the TV's
 * requests. Tolerant on purpose, so a malformed body shows up as an assertion about a missing field
 * rather than as an exception from the fake server.
 */
internal fun String.jsonField(name: String): String? {
    val field =
        runCatching {
            Json
                .parseToJsonElement(this)
                .jsonObject[name]
                ?.jsonPrimitive
                ?.content
        }
    return field.getOrNull()
}
