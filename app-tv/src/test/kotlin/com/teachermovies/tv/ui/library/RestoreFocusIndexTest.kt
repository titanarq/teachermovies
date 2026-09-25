package com.teachermovies.tv.ui.library

import com.teachermovies.core.model.TorrentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreFocusIndexTest {
    private fun id(n: Int) = TorrentId(n.toString().padStart(40, '0'))

    private val cards =
        (1..5).map {
            LibraryCard(
                id = id(it),
                title = "Movie $it",
                sizeText = "1 GB",
                resumeText = null,
            )
        }

    @Test
    fun nothingToRestoreWithoutAnId() {
        assertNull(restoreFocusIndex(cards, null))
    }

    @Test
    fun thePlayedCardIsFoundAlsoWhenItIsNotTheFirst() {
        assertEquals(0, restoreFocusIndex(cards, id(1)))
        assertEquals(3, restoreFocusIndex(cards, id(4)))
    }

    @Test
    fun aMovieNoLongerInTheLibraryRestoresNothing() {
        assertNull(restoreFocusIndex(cards, id(9)))
        assertNull(restoreFocusIndex(emptyList(), id(1)))
    }
}
