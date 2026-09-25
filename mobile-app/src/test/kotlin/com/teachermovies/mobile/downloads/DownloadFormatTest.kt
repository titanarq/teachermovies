package com.teachermovies.mobile.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFormatTest {
    @Test
    fun `every state the TV reports has its Spanish label`() {
        val labels =
            listOf(
                "fetching_metadata" to "Obteniendo metadata",
                "queued" to "Esperando",
                "downloading" to "Descargando",
                "paused" to "En pausa",
                "verifying" to "Verificando",
                "completed" to "Completado",
                "error" to "Error",
            )

        for ((state, label) in labels) {
            assertEquals(state, label, DownloadFormat.stateLabel(state))
        }
    }

    @Test
    fun `a state outside the documented set is shown as it came`() {
        assertEquals("seeding", DownloadFormat.stateLabel("seeding"))
        assertEquals("", DownloadFormat.stateLabel(""))
    }

    @Test
    fun `progress keeps one decimal with the Spanish comma`() {
        assertEquals("72,4 %", DownloadFormat.progress(72.44))
        assertEquals("17,5 %", DownloadFormat.progress(17.45))
        assertEquals("0,0 %", DownloadFormat.progress(0.0))
        assertEquals("100,0 %", DownloadFormat.progress(100.0))
        assertEquals("0,1 %", DownloadFormat.progress(0.05))
        assertEquals("0,0 %", DownloadFormat.progress(-3.0))
    }

    @Test
    fun `speed is megabytes per second with one decimal`() {
        assertEquals("8,3 MB/s", DownloadFormat.speed(8_300_000))
        assertEquals("1,5 MB/s", DownloadFormat.speed(1_500_000))
        assertEquals("1,0 MB/s", DownloadFormat.speed(999_999))
        assertEquals("0,8 MB/s", DownloadFormat.speed(824_000))
        assertEquals("0,0 MB/s", DownloadFormat.speed(0))
        assertEquals("0,0 MB/s", DownloadFormat.speed(-1))
        assertEquals("12,5 MB/s", DownloadFormat.speed(12_456_789))
    }

    @Test
    fun `downloaded and total share the gigabyte unit`() {
        assertEquals("18,4 / 25,6 GB", DownloadFormat.size(18_400_000_000, 25_600_000_000))
        assertEquals("0,0 / 1,5 GB", DownloadFormat.size(0, 1_500_000_000))
        assertEquals("1,5 / 1,5 GB", DownloadFormat.size(1_500_000_000, 1_500_000_000))
    }

    @Test
    fun `a total that is not known yet reads as zero`() {
        assertEquals("1,5 / 0,0 GB", DownloadFormat.size(1_500_000_000, 0))
        assertEquals("0,0 / 0,0 GB", DownloadFormat.size(0, 0))
    }
}
