package com.teachermovies.tv.format

import com.teachermovies.core.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun bytesUsesDecimalUnitsOneDecimalAndTheSpanishComma() {
        assertEquals("1,5 GB", Formatters.bytes(1_500_000_000))
        assertEquals("8,3 MB", Formatters.bytes(8_300_000))
        assertEquals("500 B", Formatters.bytes(500))
        assertEquals("0 B", Formatters.bytes(0))
    }

    @Test
    fun speedAppendsPerSecond() {
        assertEquals("8,3 MB/s", Formatters.speed(8_300_000))
        assertEquals("1,0 TB/s", Formatters.speed(1_000_000_000_000))
    }

    @Test
    fun sizeTextSharesOneUnitBetweenDownloadedAndTotal() {
        assertEquals("18,4 / 25,6 GB", Formatters.sizeText(18_400_000_000, 25_600_000_000))
    }

    @Test
    fun sizeTextFallsBackToTheDownloadedUnitBeforeTotalIsKnown() {
        assertEquals("0 / 0 B", Formatters.sizeText(0, 0))
    }

    @Test
    fun etaFormatsHoursMinutesAndSeconds() {
        assertEquals("1 h 2 min", Formatters.eta(3725))
        assertEquals("59 s", Formatters.eta(59))
        assertEquals("—", Formatters.eta(null))
        assertEquals("2 min 5 s", Formatters.eta(125))
    }

    @Test
    fun percentRoundsToTheNearestWholeNumber() {
        assertEquals("72 %", Formatters.percent(72.44))
        assertEquals("73 %", Formatters.percent(72.5))
    }

    @Test
    fun ratioUsesTwoDecimalsAndTheSpanishComma() {
        assertEquals("0,46", Formatters.ratio(0.456))
        assertEquals("1,00", Formatters.ratio(1.0))
    }

    @Test
    fun stateLabelIsSpanishForEveryState() {
        assertEquals("Obteniendo metadata", Formatters.stateLabel(DownloadState.FetchingMetadata))
        assertEquals("En cola", Formatters.stateLabel(DownloadState.Queued))
        assertEquals("Descargando", Formatters.stateLabel(DownloadState.Downloading))
        assertEquals("En pausa", Formatters.stateLabel(DownloadState.Paused))
        assertEquals("Verificando", Formatters.stateLabel(DownloadState.Verifying))
        assertEquals("Completado", Formatters.stateLabel(DownloadState.Completed))
        assertEquals("Error", Formatters.stateLabel(DownloadState.Error))
    }
}
