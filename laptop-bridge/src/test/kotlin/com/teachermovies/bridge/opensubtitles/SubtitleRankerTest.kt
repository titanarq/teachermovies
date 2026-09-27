package com.teachermovies.bridge.opensubtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleRankerTest {
    private fun candidate(
        id: Long,
        hashMatch: Boolean = false,
        hearingImpaired: Boolean = false,
        fromTrusted: Boolean = false,
        downloads: Int = 0,
        machineTranslated: Boolean = false,
        aiTranslated: Boolean = false,
        foreignPartsOnly: Boolean = false,
    ) = SubtitleCandidate(
        fileId = id,
        fileName = null,
        languageCode = "en",
        hearingImpaired = hearingImpaired,
        machineTranslated = machineTranslated,
        aiTranslated = aiTranslated,
        foreignPartsOnly = foreignPartsOnly,
        hashMatch = hashMatch,
        fromTrusted = fromTrusted,
        downloadCount = downloads,
    )

    @Test
    fun `a hash match wins over everything else`() {
        val ranked =
            SubtitleRanker.rank(
                listOf(
                    candidate(1, fromTrusted = true, downloads = 9000),
                    candidate(2, hashMatch = true, hearingImpaired = true),
                ),
            )

        assertEquals(listOf(2L, 1L), ranked.map { it.fileId })
    }

    @Test
    fun `then non-hearing-impaired, then trusted, then downloads`() {
        val ranked =
            SubtitleRanker.rank(
                listOf(
                    candidate(1, hearingImpaired = true, fromTrusted = true, downloads = 9000),
                    candidate(2, downloads = 10),
                    candidate(3, downloads = 500),
                    candidate(4, fromTrusted = true, downloads = 1),
                ),
            )

        assertEquals(listOf(4L, 3L, 2L, 1L), ranked.map { it.fileId })
    }

    @Test
    fun `machine-translated, AI-translated and foreign-parts-only are excluded`() {
        val best =
            SubtitleRanker.best(
                listOf(
                    candidate(1, hashMatch = true, machineTranslated = true),
                    candidate(2, hashMatch = true, aiTranslated = true),
                    candidate(3, hashMatch = true, foreignPartsOnly = true),
                ),
            )

        assertNull(best)
    }

    @Test
    fun `ties keep the server's order`() {
        assertEquals(listOf(5L, 6L), SubtitleRanker.rank(listOf(candidate(5), candidate(6))).map { it.fileId })
    }
}
