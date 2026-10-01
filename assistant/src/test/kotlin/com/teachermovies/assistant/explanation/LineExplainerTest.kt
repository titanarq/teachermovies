package com.teachermovies.assistant.explanation

import com.teachermovies.assistant.explanation.fake.FakeBridgeExplainGateway
import com.teachermovies.core.repo.ExplanationCacheRepository
import com.teachermovies.core.repo.fake.InMemoryExplanationCacheRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LineExplainerTest {
    private val gateway = FakeBridgeExplainGateway()
    private val cache = InMemoryExplanationCacheRepository()
    private var now = 1_000L

    private fun explainer(
        cache: ExplanationCacheRepository = this.cache,
        initialPromptVersion: String = LineExplainer.PROMPT_VERSION,
    ) = LineExplainer(gateway, cache, clock = { now }, initialPromptVersion = initialPromptVersion)

    private val context = ExplanationContext("Heat", "I'm gonna go", listOf("a"), listOf("b"), "Me voy")

    private fun document(
        summary: String,
        version: String? = null,
    ) = buildString {
        append("""{"resumen": "$summary", "puntos": [{"expresion": "gonna", "explicacion": "going to"}]""")
        if (version != null) append(""", "promptVersion": "$version"""")
        append("}")
    }

    @Test
    fun `sends the whole context once and caches the answer`() =
        runTest {
            gateway.documents[context.line] = document("Me voy a ir.")
            val explainer = explainer()

            val first = explainer.explain(context) as ExplanationResult.Explained
            assertEquals("Me voy a ir.", first.explanation.summary)
            assertEquals(false, first.fromCache)
            assertEquals(listOf(context), gateway.requests.map { it.context })
            assertEquals(LineExplainer.TIMEOUT, gateway.requests.single().timeout)

            val second = explainer.explain(context) as ExplanationResult.Explained
            assertEquals(true, second.fromCache)
            assertEquals(first.explanation, second.explanation)
            assertEquals(1, gateway.requests.size)
        }

    @Test
    fun `the cache survives a new explainer over the same table, stored verbatim`() =
        runTest {
            val doc = document("Me voy a ir.")
            gateway.documents[context.line] = doc
            explainer().explain(context)

            assertEquals(doc, cache.explanationFor("Heat", context.line, LineExplainer.PROMPT_VERSION, now))
            assertTrue((explainer().explain(context) as ExplanationResult.Explained).fromCache)
            assertEquals(1, gateway.requests.size)
        }

    @Test
    fun `the prompt version is explain-v2 and no explain-v1 row is served`() =
        runTest {
            assertEquals("explain-v2", LineExplainer.PROMPT_VERSION)
            cache.store("Heat", context.line, "explain-v1", document("Vieja"), now)
            gateway.documents[context.line] = document("Nueva")

            val result = explainer().explain(context) as ExplanationResult.Explained
            assertEquals("Nueva", result.explanation.summary)
            assertEquals(false, result.fromCache)
        }

    @Test
    fun `a multi-phrase line is a different cache row from its first phrase`() =
        runTest {
            val longer = context.copy(line = context.line + " and the next one")
            gateway.documents[context.line] = document("Una")
            gateway.documents[longer.line] = document("Dos")
            val explainer = explainer()

            explainer.explain(context)
            val result = explainer.explain(longer) as ExplanationResult.Explained
            assertEquals("Dos", result.explanation.summary)
            assertEquals(false, result.fromCache)
            assertEquals(2, gateway.requests.size)
        }

    @Test
    fun `the cache key includes the prompt version`() =
        runTest {
            cache.store("Heat", context.line, "explain-v0", document("Respuesta vieja"), now)
            gateway.documents[context.line] = document("Respuesta nueva")

            val result = explainer(initialPromptVersion = "explain-v1").explain(context) as ExplanationResult.Explained
            assertEquals("Respuesta nueva", result.explanation.summary)
            assertEquals(false, result.fromCache)
            assertEquals(1, gateway.requests.size)
        }

    @Test
    fun `a reply tagged with a new prompt version is stored under it and used from then on`() =
        runTest {
            val explainer = explainer(initialPromptVersion = "explain-v1")
            val other = context.copy(line = "Other line")
            cache.store("Heat", other.line, "explain-v1", document("Vieja"), now)
            gateway.documents[context.line] = document("Nueva", version = "explain-v2")
            gateway.documents[other.line] = document("Otra nueva", version = "explain-v2")

            explainer.explain(context)
            assertEquals("explain-v2", explainer.promptVersion)
            assertTrue(cache.explanationFor("Heat", context.line, "explain-v2", now) != null)
            assertNull(cache.explanationFor("Heat", context.line, "explain-v1", now))

            // The v1 row of another line is no longer served once the bridge has said v2.
            val result = explainer.explain(other) as ExplanationResult.Explained
            assertEquals("Otra nueva", result.explanation.summary)
            assertEquals(false, result.fromCache)
        }

    @Test
    fun `a line with no title is explained but never cached`() =
        runTest {
            val untitled = context.copy(title = null)
            gateway.documents[untitled.line] = document("Sin título")
            val explainer = explainer()
            explainer.explain(untitled)
            explainer.explain(untitled)
            assertEquals(2, gateway.requests.size)
        }

    @Test
    fun `failures map to results and store nothing`() =
        runTest {
            val explainer = explainer()
            gateway.nextOutcome = BridgeExplainOutcome.NoBridge
            assertEquals(ExplanationResult.Unavailable(LineExplainer.NO_BRIDGE_REASON), explainer.explain(context))
            gateway.nextOutcome = BridgeExplainOutcome.TimedOut
            assertEquals(ExplanationResult.Offline, explainer.explain(context))
            gateway.nextOutcome = BridgeExplainOutcome.Disconnected
            assertEquals(ExplanationResult.Offline, explainer.explain(context))
            gateway.nextOutcome = BridgeExplainOutcome.BridgeError("daily_cap")
            assertEquals(ExplanationResult.Unavailable("bridge error: daily_cap"), explainer.explain(context))
            gateway.nextOutcome = BridgeExplainOutcome.Done("not a document")
            assertEquals(ExplanationResult.Unavailable(LineExplainer.INVALID_REPLY_REASON), explainer.explain(context))

            assertNull(cache.explanationFor("Heat", context.line, LineExplainer.PROMPT_VERSION, now))
            assertEquals(5, gateway.requests.size)
        }

    @Test
    fun `a blank line never reaches the bridge`() =
        runTest {
            assertEquals(
                ExplanationResult.Unavailable(LineExplainer.BLANK_LINE_REASON),
                explainer().explain(context.copy(line = "  ")),
            )
            assertEquals(0, gateway.requests.size)
        }

    @Test
    fun `a gateway that throws is unavailable`() =
        runTest {
            val throwing = LineExplainer({ _, _ -> throw IllegalStateException("boom") }, cache, clock = { now })
            assertEquals(
                ExplanationResult.Unavailable("bridge error: IllegalStateException"),
                throwing.explain(context),
            )
        }

    @Test
    fun `a broken cache neither hides nor loses the answer`() =
        runTest {
            val broken =
                object : ExplanationCacheRepository {
                    override suspend fun explanationFor(
                        movieTitle: String,
                        line: String,
                        promptVersion: String,
                        now: Long,
                    ): String? = throw IOException("disk")

                    override suspend fun store(
                        movieTitle: String,
                        line: String,
                        promptVersion: String,
                        explanationJson: String,
                        now: Long,
                    ): Unit = throw IOException("disk")
                }
            gateway.documents[context.line] = document("Aun así")
            val result = explainer(cache = broken).explain(context) as ExplanationResult.Explained
            assertEquals("Aun así", result.explanation.summary)
        }

    @Test
    fun `a cached row that no longer parses is asked again and replaced`() =
        runTest {
            cache.store("Heat", context.line, LineExplainer.PROMPT_VERSION, "garbage", now)
            gateway.documents[context.line] = document("Arreglado")
            val result = explainer().explain(context) as ExplanationResult.Explained
            assertEquals(false, result.fromCache)
            val stored = cache.explanationFor("Heat", context.line, LineExplainer.PROMPT_VERSION, now)
            assertEquals(document("Arreglado"), stored)
        }
}
