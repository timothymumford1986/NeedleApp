package app.needler.feature.search.search

import app.cash.turbine.test
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.NeedlerError
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

    /**
     * The whole offline lane, from the connectivity the use case reads to the
     * sentence the screen draws.
     *
     * [SearchUiStateTest] asserts the copy from a literal state, which is where
     * the fault was. This asserts that the real wiring reaches that state, so a
     * change to `UnifiedSearchUseCase.catalogueLaneBlocker` cannot leave the
     * screen settled, empty and claiming a lookup again. Both halves are needed:
     * the fixture test would pass over a use case that reported the lane as
     * `Ready` offline, and this one would pass over copy that read the wrong
     * signal.
     */
    @Test
    fun `offline with nothing matching reaches the state that claims no lookup`() = runTest {
        sessions.connectivityFlow.value = ConnectivityState.Offline
        search.localResults.value = LocalSearchResults(query = "beastiedido")

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onQueryChange("beastiedido")
            advanceUntilIdle()
            val state = expectMostRecentItem()

            assertTrue("the lane has to settle or the screen draws skeletons", state.showEmptyResult)
            assertEquals(
                CatalogueLaneState.Unavailable(NeedlerError.Offline()),
                state.catalogue,
            )
            assertFalse("MusicBrainz was never asked", state.catalogueAnswered)
            assertFalse(
                state.emptyResultDetail.contains("MusicBrainz catalogue matches"),
            )
            assertEquals(CATALOGUE_OFFLINE, state.catalogueNote)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The same query with a connection: the lane ran, so the claim is allowed. */
    @Test
    fun `online with nothing matching is allowed to report the catalogue`() = runTest {
        search.localResults.value = LocalSearchResults(query = "beastiedido")

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onQueryChange("beastiedido")
            advanceUntilIdle()
            val state = expectMostRecentItem()

            assertTrue(state.showEmptyResult)
            assertTrue(state.catalogueAnswered)
            assertTrue(state.emptyResultDetail.contains("MusicBrainz catalogue matches"))
            assertNull("nothing went wrong, so there is nothing to say", state.catalogueNote)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The path that works today and is the easiest to regress: offline, the
     * mirror answers, and the screen is a results screen rather than an empty
     * one.
     */
    @Test
    fun `offline with cached results still draws them`() = runTest {
        sessions.connectivityFlow.value = ConnectivityState.Offline
        search.localResults.value = LocalSearchResults(
            query = "beastie",
            tracks = listOf(song),
        )

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onQueryChange("beastie")
            advanceUntilIdle()
            val state = expectMostRecentItem()

            assertFalse(state.showEmptyResult)
            assertFalse(state.searching)
            assertEquals(1, state.tracks.size)
            assertEquals(CATALOGUE_OFFLINE, state.catalogueNote)
            assertEquals(FROM_LAST_SYNC, state.albumsSourceNote)
            assertFalse("no page of a catalogue that cannot be reached", state.canPageCatalogue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The other half of the offline lane: connectivity reaching the sheet that
     * places a write, not just the note about the one that reads.
     *
     * REQUIREMENTS.md "Failure handling" queues a pull placed offline for
     * replay, and the user is entitled to know that before confirming rather
     * than from the notice afterwards. Asserted through the real flow because
     * the copy is only as good as the flag under it.
     */
    @Test
    fun `the pull sheet warns that an offline pull will be queued`() = runTest {
        sessions.connectivityFlow.value = ConnectivityState.Offline

        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onPull(SampleSearch.buzz)
            advanceUntilIdle()
            val offline = expectMostRecentItem()

            assertEquals(SampleSearch.buzz, offline.pullSheetAlbum)
            assertEquals(QUEUE_PULL_LABEL, offline.pullConfirmLabel)
            assertTrue(offline.pullSheetNote.orEmpty().contains("queued"))

            sessions.connectivityFlow.value = ConnectivityState.Unmetered
            advanceUntilIdle()
            val online = expectMostRecentItem()

            assertEquals(PULL_LABEL, online.pullConfirmLabel)
            assertNull("nothing extra to say with a connection", online.pullSheetNote)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The promise `SUBSCRIPTION_TIMEOUT_MS` is there to keep, asserted rather
     * than assumed: "a rotation, or a trip into an album and straight back, does
     * not lose the user's search".
     *
     * The screen going away cancels the only collector, which is what that
     * timeout is about. It is worth a test of its own because the search field
     * now *selects* that surviving query on arrival rather than clearing it
     * (`SearchFieldEntryTest`), and the alternative fix - blanking the query on
     * entry - would have deleted it here. Nothing would have failed.
     */
    @Test
    fun `the query survives the screen going away and coming back`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            model.onQueryChange("dido")
            advanceUntilIdle()
            assertEquals("dido", expectMostRecentItem().query)
            cancelAndIgnoreRemainingEvents()
        }

        // Long enough for the subscription to have timed out, so this is the
        // cold start the album screen leaves behind and not a live flow.
        advanceUntilIdle()

        model.state.test {
            assertEquals("dido", awaitItem().query)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
