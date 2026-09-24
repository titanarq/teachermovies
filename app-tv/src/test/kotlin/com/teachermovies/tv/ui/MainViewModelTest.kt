package com.teachermovies.tv.ui

import com.teachermovies.core.model.TorrentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MainViewModelTest {

    @Test
    fun initialStateSelectsTheFirstSection() {
        val viewModel = MainViewModel()

        assertEquals(Destination.Library, viewModel.uiState.value.selected)
    }

    @Test
    fun selectReplacesTheSelectedSection() {
        val viewModel = MainViewModel()

        viewModel.select(Destination.Downloads)

        assertEquals(Destination.Downloads, viewModel.uiState.value.selected)
    }

    @Test
    fun selectReachesEverySection() {
        val viewModel = MainViewModel()

        Destination.entries.forEach { destination ->
            viewModel.select(destination)
            assertEquals(destination, viewModel.uiState.value.selected)
        }
    }

    @Test
    fun selectingTheAlreadySelectedSectionLeavesTheStateInstanceAlone() {
        val viewModel = MainViewModel()
        val stateBefore = viewModel.uiState.value

        viewModel.select(stateBefore.selected)

        // A `StateFlow` conflates equal values, so the shell does not recompose for a re-selection.
        assertSame(stateBefore, viewModel.uiState.value)
    }

    @Test
    fun initialRouteIsTheShell() {
        assertEquals(AppRoute.Shell, MainViewModel().uiState.value.route)
    }

    @Test
    fun openPlayerNavigatesToThePlayerRouteOfThatItem() {
        val viewModel = MainViewModel()
        val id = TorrentId("a".repeat(40))

        viewModel.openPlayer(id)

        assertEquals(AppRoute.Player(id), viewModel.uiState.value.route)
        assertEquals("player/${"a".repeat(40)}", viewModel.uiState.value.route.route)
        assertEquals("player/{id}", AppRoute.Player.PATTERN)
    }

    @Test
    fun closePlayerReturnsToTheShellOnTheSameSection() {
        val viewModel = MainViewModel()
        viewModel.openPlayer(TorrentId("b".repeat(40)))

        viewModel.closePlayer()

        assertEquals(AppRoute.Shell, viewModel.uiState.value.route)
        assertEquals(Destination.Library, viewModel.uiState.value.selected)
    }
}
