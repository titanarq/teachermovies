package com.teachermovies.bridge.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeSubtitleDtoTest {
    private val id = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `a need encodes to the golden json with every key, nulls included`() {
        val need = SubtitleNeedDto(id, "Heat", "es", null, BridgeSubtitleProtocol.STATUS_NOT_FOUND, 2)
        val golden =
            """{"torrentId":"$id","title":"Heat","language":"es",""" +
                """"movieHash":null,"state":"not_found","attempts":2}"""
        assertEquals(golden, Json.encodeToString(SubtitleNeedDto.serializer(), need))
        assertEquals(need, Json.decodeFromString(SubtitleNeedDto.serializer(), golden))
    }

    @Test
    fun `a subtitles-needed nudge carries the movie that changed, or nothing`() {
        val golden = """{"torrentId":"$id"}"""
        assertEquals(golden, Json.encodeToString(SubtitlesNeededDto.serializer(), SubtitlesNeededDto(id)))
        assertEquals(SubtitlesNeededDto(id), Json.decodeFromString(SubtitlesNeededDto.serializer(), golden))
        assertNull(Json.decodeFromString(SubtitlesNeededDto.serializer(), """{"torrentId":null}""").torrentId)
    }

    @Test
    fun `a status decodes with and without its optional message`() {
        assertEquals(
            SubtitleStatusDto(id, "es", BridgeSubtitleProtocol.STATUS_FAILED, "daily_cap"),
            Json.decodeFromString(
                SubtitleStatusDto.serializer(),
                """{"torrentId":"$id","language":"es","status":"failed","message":"daily_cap"}""",
            ),
        )
        assertEquals(
            SubtitleStatusDto(id, "en", BridgeSubtitleProtocol.STATUS_SEARCHING, null),
            Json.decodeFromString(
                SubtitleStatusDto.serializer(),
                """{"torrentId":"$id","language":"en","status":"searching"}""",
            ),
        )
    }

    @Test
    fun `an upload answer carries the relative path the TV wrote to`() {
        val uploaded = SubtitleUploadedDto("subs/Heat.es.opensubtitles.srt")
        val golden = """{"path":"subs/Heat.es.opensubtitles.srt"}"""
        assertEquals(golden, Json.encodeToString(SubtitleUploadedDto.serializer(), uploaded))
        assertEquals(uploaded, Json.decodeFromString(SubtitleUploadedDto.serializer(), golden))
    }

    @Test
    fun `the file name is base, language and the opensubtitles marker`() {
        assertEquals("Heat.es.opensubtitles.srt", BridgeSubtitleProtocol.fileName("Heat", "es"))
        assertEquals("The.Matrix.en.opensubtitles.srt", BridgeSubtitleProtocol.fileName("The.Matrix", "en"))
    }
}
