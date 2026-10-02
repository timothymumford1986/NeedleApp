package app.needler.feature.search.search

import app.cash.turbine.test
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.Track
import app.needler.feature.search.FakeLibraryRepository
import app.needler.feature.search.FakePlaybackController
import app.needler.feature.search.FakePullRepository
import app.needler.feature.search.FakeSearchRepository
import app.needler.feature.search.FakeSessions
import app.needler.feature.search.MainDispatcherRule
import app.needler.feature.search.SampleSearch
import java.util.Optional
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the search screen can do to the crate.
 *
 * Search could play a song and nothing else: the tap replaced the crate, and a long press on the
 * same row did the same thing the tap did. Every test here asserts the negative as well as the
 * positive - that `enqueue` was used, and that `playTracks` and `playAlbum`, the two commands that
 * replace the crate, were not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val search = FakeSearchRepository()
    private val library = FakeLibraryRepository()
    private val pulls = FakePullRepository()
    private val sessions = FakeSessions()
    private val playback = FakePlaybackController()

    private val song: Track = SampleSearch.timeYouAndI
    private val secondSong: Track = SampleSearch.pelota

    private fun viewModel() = SearchViewModel(
        search = search,
        library = library,
        pulls = pulls,
        sessions = sessions,
        playback = Optional.of(playback),
    )

    /** Something already in the crate, so an add has to append rather than start. */
    private fun loaded(track: Track) {
        playback.queue.value = app.needler.core.domain.model.PlayQueue(
            items = listOf(QueueItem(id = "q-0", track = track)),
            currentIndex = 0,
        )
        playback.playbackState.value = playback.playbackState.value.copy(
            currentItem = playback.queue.value.currentItem,
            isPlaying = true,
        )
    }

    @Test
    fun `adding a song appends to the crate rather than replacing it`() = runTest {
        loaded(secondSong)

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onAddTrackToCrate(song, playNext = false)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals("Added 1 track to the crate.", after.notice?.message)
            assertTrue("the crate line is drawn with it", after.notice?.showsCrate == true)
            // Pelota's 200s plus Time (You and I)'s 227s, from the session's own crate.
            assertEquals(2, after.crateTrackCount)
            assertEquals("2 in the crate · 7 min", after.crateLine)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("Time (You and I)", playback.enqueueCalls.single().tracks.single().title)
        assertFalse(playback.enqueueCalls.single().playNext)
        assertTrue("the crate must not be replaced", playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `adding while nothing is loaded starts playback and says so`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onAddTrackToCrate(song, playNext = false)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals(
                "The crate was empty, so 1 track started playing.",
                after.notice?.message,
            )
            assertEquals(1, after.crateTrackCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, playback.playTracksCalls.size)
        assertTrue(playback.enqueueCalls.isEmpty())
    }

    @Test
    fun `play next asks for the insert, not the append`() = runTest {
        loaded(secondSong)

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onAddTrackToCrate(song, playNext = true)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals("Added 1 track to the crate, to play next.", after.notice?.message)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.enqueueCalls.single().playNext)
        assertEquals(song.key, playback.queue.value.items[1].track.key)
    }

    @Test
    fun `adding an album reads its tracks from the mirror`() = runTest {
        loaded(secondSong)
        library.tracksByMbid.value = mapOf(
            SampleSearch.mordechai.releaseGroupMbid.value to listOf(song, secondSong),
        )

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onAddAlbumToCrate(SampleSearch.mordechai, playNext = false)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals("Added 2 tracks to the crate.", after.notice?.message)
            assertEquals(3, after.crateTrackCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(2, playback.enqueueCalls.single().tracks.size)
        assertTrue("the crate must not be replaced", playback.playAlbumCalls.isEmpty())
    }

    @Test
    fun `an album the mirror holds no tracks for adds nothing and says nothing`() = runTest {
        loaded(secondSong)

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onAddAlbumToCrate(SampleSearch.blackClassicalMusic, playNext = false)
            advanceUntilIdle()
            // Read off the state rather than awaited: nothing happened, so there is no
            // emission to wait for, which is the assertion.
            assertNull(model.state.value.notice)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.enqueueCalls.isEmpty())
        assertTrue(playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `a song with no file behind it is never added`() = runTest {
        val missing: Track = SampleSearch.track(
            albumSlug = "mordechai",
            number = 9,
            title = "Nowhere",
            artistName = "Khruangbin",
            albumTitle = "Mordechai",
            durationMs = 100_000L,
            available = false,
        )

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onAddTrackToCrate(missing, playNext = false)
            advanceUntilIdle()
            // No emission to wait for, because nothing changed - which is the assertion.
            assertNull(model.state.value.notice)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.enqueueCalls.isEmpty())
        assertTrue(playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `a new search clears the added line`() = runTest {
        // It is attached to a result that is about to leave the screen; leaving "added to the
        // crate" over somebody else's results would attach it to the wrong row.
        search.localResults.value = LocalSearchResults(query = "khruangbin", tracks = listOf(song))

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onAddTrackToCrate(song, playNext = false)
            advanceUntilIdle()
            assertEquals(
                "The crate was empty, so 1 track started playing.",
                expectMostRecentItem().notice?.message,
            )

            model.onQueryChange("yussef")
            advanceUntilIdle()
            assertNull(expectMostRecentItem().notice)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
