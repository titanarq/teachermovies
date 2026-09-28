package com.teachermovies.bridge.run

import com.teachermovies.bridge.protocol.BridgeJobDto
import com.teachermovies.bridge.protocol.BridgeJobResultDto
import com.teachermovies.bridge.protocol.TranslateJobDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dispatch by `kind` (#277): the registry the handlers of #286/#291 join. */
class JobHandlerRegistryTest {
    private fun job(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private class Recording(
        override val kind: String,
        private val answer: (BridgeJobDto) -> BridgeJobResultDto,
    ) : JobHandler {
        val seen = mutableListOf<BridgeJobDto>()

        override suspend fun handle(job: BridgeJobDto): BridgeJobResultDto {
            seen += job
            return answer(job)
        }
    }

    @Test
    fun `an empty registry answers every job unsupported_kind`() =
        runBlocking {
            val registry = JobHandlerRegistry.default()
            assertTrue(registry.kinds.isEmpty())
            val result = registry.dispatch(job("""{"kind":"translate","id":"j1","line":"Hi"}"""))
            assertEquals(JobHandlerRegistry.UNSUPPORTED_KIND, (result as BridgeJobResultDto.Failed).code)
        }

    @Test
    fun `a job goes to the handler of its kind, typed`() =
        runBlocking {
            val translate = Recording("translate") { BridgeJobResultDto.Done("Hola") }
            val explain = Recording("explain") { BridgeJobResultDto.Done("?") }
            val registry = JobHandlerRegistry(listOf(translate, explain))
            val result = registry.dispatch(job("""{"kind":"translate","id":"j1","line":"Hi"}"""))
            assertEquals(BridgeJobResultDto.Done("Hola"), result)
            assertEquals(listOf(TranslateJobDto(id = "j1", line = "Hi")), translate.seen)
            assertTrue(explain.seen.isEmpty())
        }

    @Test
    fun `a kind this bridge does not know, or no kind at all, is unsupported_kind`() =
        runBlocking {
            val registry = JobHandlerRegistry(listOf(Recording("translate") { BridgeJobResultDto.Done("") }))
            val unknown = registry.dispatch(job("""{"kind":"summarise","id":"j2"}"""))
            val none = registry.dispatch(job("""{"id":"j3"}"""))
            assertEquals(JobHandlerRegistry.UNSUPPORTED_KIND, (unknown as BridgeJobResultDto.Failed).code)
            assertTrue(unknown.message!!.contains("summarise"))
            assertEquals(JobHandlerRegistry.UNSUPPORTED_KIND, (none as BridgeJobResultDto.Failed).code)
        }

    @Test
    fun `a handled kind whose fields do not decode is bad_job, and the handler is not called`() =
        runBlocking {
            val translate = Recording("translate") { BridgeJobResultDto.Done("") }
            val result = JobHandlerRegistry(listOf(translate)).dispatch(job("""{"kind":"translate","id":"j4"}"""))
            assertEquals(JobHandlerRegistry.BAD_JOB, (result as BridgeJobResultDto.Failed).code)
            assertTrue(translate.seen.isEmpty())
        }

    @Test
    fun `a handler that throws is answered handler_error`() =
        runBlocking {
            val broken = Recording("translate") { throw IllegalStateException("boom") }
            val result =
                JobHandlerRegistry(
                    listOf(broken),
                ).dispatch(job("""{"kind":"translate","id":"j5","line":"Hi"}"""))
            assertEquals(BridgeJobResultDto.Failed(JobHandlerRegistry.HANDLER_ERROR, "IllegalStateException"), result)
        }

    @Test
    fun `two handlers of one kind are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            JobHandlerRegistry(
                listOf(
                    Recording("translate") {
                        BridgeJobResultDto.Done("")
                    },
                    Recording("translate") { BridgeJobResultDto.Done("") },
                ),
            )
        }
    }
}
