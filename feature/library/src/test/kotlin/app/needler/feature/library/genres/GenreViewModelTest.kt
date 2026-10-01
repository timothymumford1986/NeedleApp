package app.needler.feature.library.genres

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.needler.core.domain.model.Track
import app.needler.feature.library.FakePlaybackController
import app.needler.feature.library.FakeSessions
import app.needler.feature.library.MainDispatcherRule
import app.needler.feature.library.SampleLibrary
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GenreViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val library = FakeGenreLibrary(SampleGenres.all)
    private val sessions = FakeSessions()
    private val playback = FakePlaybackController()

    private val genre = "Dream pop"

    private fun viewModel(name: String = genre) = GenreViewModel(
        savedStateHandle = SavedStateHandle(mapOf(GenreViewModel.GENRE_ARG to name)),
        library = library,
        sessions = sessions,
        playback = Optional.of(playback),
    )

    private fun stocked(tracks: List<Track>) {
        library.tracksByGenre.value = mapOf(genre to tracks)
    }

    @Test
    fun `the genre from the route is the query and the title`() = runTest {
        stocked(SampleLibrary.submarineTracks)
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.tracks.isEmpty()) loaded = awaitItem()
            assertEquals(genre, loaded.genre)
            assertEquals(8, loaded.tracks.size)
            cancelAndIgnoreRemainingEvents()
        }
        // The cap is passed explicitly rather than left to the repository's default,
        // so that the screen's "showing the first N" line and the query cannot
        // disagree about N.
        assertEquals(genre to GenreViewModel.TRACK_LIMIT, library.genreQueries.first())
    }

    @Test
    fun `a genre with nothing under it is an empty state`() = runTest {
        viewModel().state.test {
            assertTrue(awaitItem().loading)
            val loaded = awaitItem()
            assertTrue(loaded.showEmptyState)
            assertFalse(loaded.hasPlayableTracks)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the order the query returned is the order drawn`() = runTest {
        // The query orders by album artist, then album title, then disc and track:
        // a genre reads as records in running order, not an alphabet of song titles.
        // Nothing here re-sorts it.
        val tracks = SampleLibrary.submarineTracks.reversed()
        stocked(tracks)
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.tracks.isEmpty()) loaded = awaitItem()
            assertEquals(tracks.map { it.title }, loaded.tracks.map { it.title })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the running time is summed only when every track has one`() = runTest {
        stocked(SampleLibrary.submarineTracks)
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.tracks.isEmpty()) loaded = awaitItem()
            assertEquals("8 tracks · 28 min", loaded.headerLine)
            cancelAndIgnoreRemainingEvents()
        }

        // One unknown duration and the figure is dropped rather than under-reported.
        val withUnknown = SampleLibrary.submarineTracks.toMutableList()
        withUnknown[0] = withUnknown[0].copy(durationMs = null)
        assertEquals(
            "8 tracks",
            GenreUiState(loading = false, genre = genre, tracks = withUnknown).headerLine,
        )
    }

    @Test
    fun `a full page says so, because the query is a cap and not a cursor`() {
        val three = SampleLibrary.submarineTracks.take(3)
        assertTrue(GenreUiState(loading = false, tracks = three, trackLimit = 3).atLimit)
        assertFalse(GenreUiState(loading = false, tracks = three, trackLimit = 200).atLimit)
        // A screen with no limit recorded must never claim to be capped.
        assertFalse(GenreUiState(loading = false, tracks = three).atLimit)
    }

    @Test
    fun `play puts only the playable tracks in the crate`() = runTest {
        stocked(SampleLibrary.submarinePartialTracks)
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.tracks.isEmpty()) loaded = awaitItem()
            // The rows stay in the list; only the crate loses them.
            assertEquals(8, loaded.tracks.size)
            model.onPlayAll()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(6, playback.playTracksCalls.single().size)
    }

    @Test
    fun `tapping a row counts only the playable rows`() = runTest {
        stocked(SampleLibrary.submarinePartialTracks)
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.tracks.isEmpty()) loaded = awaitItem()
            // The fifth row of the list is the fourth track of the crate, because
            // track 4 never arrived.
            model.onPlayTrack(loaded.tracks[4])
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        val crate = playback.playTracksCalls.single()
        assertEquals(6, crate.size)
        assertEquals("If Only", crate[3].title)
    }

    @Test
    fun `tapping an unplayable row never reaches the player`() = runTest {
        stocked(SampleLibrary.submarinePartialTracks)
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.tracks.isEmpty()) loaded = awaitItem()
            val missing = loaded.tracks.first { it.fetch.fileId.value == "unavailable" }
            model.onPlayTrack(missing)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `shuffle puts the same tracks in the crate, in some order`() = runTest {
        stocked(SampleLibrary.submarineTracks)
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.tracks.isEmpty()) loaded = awaitItem()
            model.onShuffleAll()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        // Order is not asserted: a shuffle may legitimately return the original
        // order, and a test that failed one time in 40,000 is worse than no test.
        assertEquals(
            SampleLibrary.submarineTracks.map { it.key }.toSet(),
            playback.playTracksCalls.single().map { it.key }.toSet(),
        )
    }
}
