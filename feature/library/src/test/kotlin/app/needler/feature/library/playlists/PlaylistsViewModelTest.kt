package app.needler.feature.library.playlists

import app.cash.turbine.test
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlaylistEdit
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playlists = FakePlaylistRepository()
    private val sessions = FakeSessions()
    private val playback = FakePlaybackController()

    private fun viewModel() = PlaylistsViewModel(
        playlists = playlists,
        sessions = sessions,
        playback = Optional.of(playback),
    )

    private fun stocked() {
        playlists.playlistsFlow.value = listOf(
            SamplePlaylists.onServer,
            SamplePlaylists.withQueuedEdit,
            SamplePlaylists.neverSent,
        )
    }

    // ---- reading ------------------------------------------------------------

    @Test
    fun `the order the mirror gave is the order drawn`() = runTest {
        // Deliberately not alphabetical. REQUIREMENTS.md orders playlists
        // alphabetically and `observePlaylists` is what does it, so a screen that
        // sorted again would be a second opinion about the same list.
        stocked()
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlists.isEmpty()) loaded = awaitItem()
            assertEquals(
                listOf("Sunday morning", "Long drive", "Jazz for rain"),
                loaded.playlists.map { it.name },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a playlist the server has never seen is not the same state as a queued edit`() = runTest {
        stocked()
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlists.isEmpty()) loaded = awaitItem()
            assertEquals(
                listOf(
                    PlaylistSyncState.ON_SERVER,
                    PlaylistSyncState.EDITS_QUEUED,
                    PlaylistSyncState.NEVER_SENT,
                ),
                loaded.playlists.map { it.syncState },
            )
            // Both pending states are pending, and neither is a problem.
            assertEquals(2, loaded.pendingCount)
            assertNull(loaded.notice)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the header says how many are waiting to be sent`() = runTest {
        stocked()
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlists.isEmpty()) loaded = awaitItem()
            assertEquals("3 playlists", loaded.countLine)
            assertEquals(2, loaded.pendingCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no playlists is an empty state, not a loading one`() = runTest {
        viewModel().state.test {
            assertTrue(awaitItem().loading)
            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertTrue(loaded.showEmptyState)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `this screen never refreshes playlists from the server`() = runTest {
        // `PlaylistRepository.refreshPlaylists` says of itself "called by sync, not
        // by screens", and sync calls it on both passes. A refresh here would make
        // the list's contents depend on a network call the architecture promises it
        // does not wait for.
        stocked()
        viewModel().state.test {
            awaitItem()
            awaitItem()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(0, playlists.refreshAllCalls)
        assertTrue(playlists.refreshedPlaylists.isEmpty())
    }

    // ---- creating -----------------------------------------------------------

    @Test
    fun `creating sends the trimmed name and closes the form`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onCreateClick()
            var opened = awaitItem()
            while (opened.draft == null) opened = awaitItem()
            model.onDraftNameChange("  Jazz for rain  ")
            model.onCreateConfirm()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertEquals(
                PlaylistNotice.Created(name = "Jazz for rain", queued = false),
                current.notice,
            )
            assertNull("the form closes on a successful create", current.draft)
            cancelAndIgnoreRemainingEvents()
        }
        val create: PlaylistEdit.Create = playlists.edits.filterIsInstance<PlaylistEdit.Create>().single()
        assertEquals("Jazz for rain", create.name)
        // Created empty: tracks are added from an album or a song row, not here.
        assertTrue(create.trackKeys.isEmpty())
    }

    @Test
    fun `creating with no connection says the server has not been told`() = runTest {
        sessions.connectivityFlow.value = ConnectivityState.Offline
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onCreateClick()
            var opened = awaitItem()
            while (opened.draft == null) opened = awaitItem()
            model.onDraftNameChange("Jazz for rain")
            model.onCreateConfirm()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            val notice = current.notice
            assertTrue(notice is PlaylistNotice.Created && notice.queued)
            // Offline is not a failure here. REQUIREMENTS.md: edits made offline
            // queue locally and replay on reconnect.
            assertFalse(current.notice?.isProblem ?: true)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, playlists.edits.filterIsInstance<PlaylistEdit.Create>().size)
    }

    @Test
    fun `a blank name is never sent`() = runTest {
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onCreateClick()
            var opened = awaitItem()
            while (opened.draft == null) opened = awaitItem()
            model.onDraftNameChange("   ")
            model.onCreateConfirm()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playlists.edits.isEmpty())
    }

    @Test
    fun `a create the server refuses keeps the typed name on screen`() = runTest {
        playlists.createOutcome = Outcome.Failure(NeedlerError.SessionExpired)
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onCreateClick()
            var opened = awaitItem()
            while (opened.draft == null) opened = awaitItem()
            model.onDraftNameChange("Jazz for rain")
            model.onCreateConfirm()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertTrue(current.notice?.isProblem == true)
            assertEquals("Jazz for rain", current.draft?.name)
            assertFalse("the form is usable again", current.draft?.submitting ?: true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- deleting -----------------------------------------------------------

    @Test
    fun `deleting is confirmed before anything is deleted`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlists.isEmpty()) loaded = awaitItem()
            model.onDeleteRequest(SamplePlaylists.onServer)
            var asked = awaitItem()
            while (asked.pendingDeletion == null) asked = awaitItem()
            assertNotNull(asked.playlistPendingDeletion)
            assertTrue("nothing left yet", playlists.edits.isEmpty())

            model.onDeleteConfirm()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertEquals(
                PlaylistNotice.Deleted(name = "Sunday morning", queued = false),
                current.notice,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(SamplePlaylists.onServer.id),
            playlists.edits.filterIsInstance<PlaylistEdit.Delete>().map { it.id },
        )
    }

    @Test
    fun `cancelling the confirmation deletes nothing`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlists.isEmpty()) loaded = awaitItem()
            model.onDeleteRequest(SamplePlaylists.onServer)
            var asked = awaitItem()
            while (asked.pendingDeletion == null) asked = awaitItem()
            model.onDeleteCancel()
            var cancelled = awaitItem()
            while (cancelled.pendingDeletion != null) cancelled = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playlists.edits.isEmpty())
    }

    // ---- playback -----------------------------------------------------------

    @Test
    fun `playing a playlist puts only its playable tracks in the crate`() = runTest {
        stocked()
        // Two of these eight tracks never arrived with the pull.
        playlists.entriesById.value = mapOf(
            SamplePlaylists.onServer.id.value to
                SamplePlaylists.entries(SampleLibrary.submarinePartialTracks),
        )
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlists.isEmpty()) loaded = awaitItem()
            model.onPlay(SamplePlaylists.onServer)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        val crate = playback.playTracksCalls.single()
        assertEquals(6, crate.size)
        assertTrue(
            "a track with no file must never reach the player",
            crate.none { it.fetch.fileId.value == "unavailable" },
        )
    }

    @Test
    fun `a playlist with nothing playable says so instead of starting silence`() = runTest {
        stocked()
        // The two tracks of Submarine the pull never brought, and nothing else.
        val missingOnly = SampleLibrary.submarinePartialTracks
            .filter { it.fetch.fileId.value == "unavailable" }
        playlists.entriesById.value = mapOf(
            SamplePlaylists.onServer.id.value to SamplePlaylists.entries(missingOnly),
        )
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlists.isEmpty()) loaded = awaitItem()
            model.onPlay(SamplePlaylists.onServer)
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertTrue(current.notice?.isProblem == true)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `adding to the crate appends rather than replacing what is playing`() = runTest {
        stocked()
        playlists.entriesById.value = mapOf(
            SamplePlaylists.onServer.id.value to
                SamplePlaylists.entries(SampleLibrary.submarineTracks),
        )
        // Something has to be playing for "appends rather than replacing" to mean
        // anything. Without this the crate is empty, the add correctly starts
        // playback instead of appending, and the test passed while asserting the
        // opposite of its own name.
        val playingQueue = PlayQueue(
            items = listOf(QueueItem(id = "q-0", track = SampleLibrary.submarineTracks.first())),
            currentIndex = 0,
        )
        playback.queue.value = playingQueue
        playback.playbackState.value =
            PlaybackState(currentItem = playingQueue.currentItem, isPlaying = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlists.isEmpty()) loaded = awaitItem()
            model.onAddToCrate(SamplePlaylists.onServer)
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertEquals(PlaylistNotice.AddedToCrate(8), current.notice)
            cancelAndIgnoreRemainingEvents()
        }
        // `enqueue` and not `playTracks`: the crate gains the playlist and keeps
        // playing whatever it was playing.
        assertTrue(playback.playTracksCalls.isEmpty())
    }
}
