package com.teachermovies.bridge.claude

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** One stdout line of a `--output-format stream-json` process, as far as the transport cares. */
sealed interface ClaudeEvent {
    /**
     * `{"type":"system","subtype":"init",...}`: the process's own account of how it is billed.
     * [apiKeySource] is `none` under the human's CLI login and names the key otherwise.
     */
    data class Init(
        val apiKeySource: String?,
    ) : ClaudeEvent

    /** The text blocks of one `assistant` message, joined. */
    data class AssistantText(
        val text: String,
    ) : ClaudeEvent

    /**
     * `{"type":"result",...}`, the end of a turn. [totalCostUsd] is the process's running total
     * over every turn so far, not this turn's; [apiErrorStatus] is the HTTP-like status of a failed
     * turn (`429` for the subscription's rate limit).
     */
    data class Result(
        val isError: Boolean,
        val subtype: String?,
        val apiErrorStatus: Int?,
        val totalCostUsd: Double?,
        val text: String?,
    ) : ClaudeEvent {
        val isRateLimit: Boolean
            get() = isError && (apiErrorStatus == RATE_LIMIT_STATUS || (apiErrorStatus == null && mentionsRateLimit()))

        private fun mentionsRateLimit(): Boolean = text?.let { RATE_LIMIT_TEXT.containsMatchIn(it) } == true
    }

    /** Any other well-formed event (`stream_event`, `user`, other `system` subtypes). */
    data object Other : ClaudeEvent

    /** A line that is not a JSON object. */
    data object Unreadable : ClaudeEvent
}

private const val RATE_LIMIT_STATUS = 429
private val RATE_LIMIT_TEXT = Regex("""\b429\b""")

/** Parses [ClaudeEvent]s and writes the one kind of line the transport sends. */
object ClaudeEvents {
    private val JSON = Json { ignoreUnknownKeys = true }

    fun parse(line: String): ClaudeEvent {
        val element =
            try {
                JSON.parseToJsonElement(line)
            } catch (_: SerializationException) {
                return ClaudeEvent.Unreadable
            }
        val event = element as? JsonObject ?: return ClaudeEvent.Unreadable
        return when (event.string("type")) {
            "system" -> {
                if (event.string("subtype") ==
                    "init"
                ) {
                    ClaudeEvent.Init(event.string("apiKeySource"))
                } else {
                    ClaudeEvent.Other
                }
            }

            "assistant" -> {
                assistantText(event)
            }

            "result" -> {
                result(event)
            }

            else -> {
                ClaudeEvent.Other
            }
        }
    }

    /** One `--input-format stream-json` turn: a user message of a single text block. */
    fun userTurn(text: String): String =
        buildJsonObject {
            put("type", "user")
            put(
                "message",
                buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                },
                            )
                        },
                    )
                },
            )
        }.toString()

    private fun assistantText(event: JsonObject): ClaudeEvent {
        val content = (event["message"] as? JsonObject)?.get("content") as? JsonArray ?: return ClaudeEvent.Other
        val texts =
            content.mapNotNull { block ->
                (block as? JsonObject)?.takeIf { it.string("type") == "text" }?.string("text")
            }
        if (texts.isEmpty()) return ClaudeEvent.Other
        return ClaudeEvent.AssistantText(texts.joinToString(""))
    }

    private fun result(event: JsonObject): ClaudeEvent.Result {
        val subtype = event.string("subtype")
        val isError = event.primitive("is_error")?.booleanOrNull == true || (subtype != null && subtype != "success")
        return ClaudeEvent.Result(
            isError = isError,
            subtype = subtype,
            apiErrorStatus = event.primitive("api_error_status")?.content?.toIntOrNull(),
            totalCostUsd = event.primitive("total_cost_usd")?.doubleOrNull,
            text = event.string("result"),
        )
    }

    private fun JsonObject.primitive(name: String): JsonPrimitive? = get(name) as? JsonPrimitive

    private fun JsonObject.string(name: String): String? = primitive(name)?.takeIf { it.isString }?.content
}
