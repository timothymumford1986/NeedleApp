package app.needler.feature.library.playlists

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.expectMostRecentItem
import app.cash.turbine.test
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.PlaylistEdit
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playlists = FakePlaylistRepository()
    private val search = FakeSearchRepository()
    private val sessions = FakeSessions()
    private val playback = FakePlaybackController()

    private val playlist = SamplePlaylists.onServer

    private fun viewModel(id: String = playlist.id.value) = PlaylistViewModel(
        savedStateHandle = SavedStateHandle(mapOf(PlaylistViewModel.PLAYLIST_ID_ARG to id)),
        playlists = playlists,
        search = search,
        sessions = sessions,
        playback = Optional.of(playback),
    )

    private fun stocked(tracks: List<Track> = SampleLibrary.submarineTracks) {
        playlists.playlistsFlow.value = listOf(playlist)
        playlists.entriesById.value = mapOf(playlist.id.value to SamplePlaylists.entries(tracks))
    }

    // ---- reading ------------------------------------------------------------

    @Test
    fun `entries keep their stored order and are numbered from one`() = runTest {
        stocked()
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            assertEquals(8, loaded.entries.size)
            // Positions are the protocol's 0-based indices; the drawn numbers are not.
            assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), loaded.entries.map { it.position })
            assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), loaded.entries.map { it.displayNumber })
            assertEquals("Last Time", loaded.entries.first().track.title)
            assertEquals("8 tracks · 28 min", loaded.headerLine)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `this screen refreshes the playlist, because getPlaylists carries no entries`() = runTest {
        stocked()
        viewModel().state.test {
            awaitItem()
            awaitItem()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(playlist.id), playlists.refreshedPlaylists)
    }

    @Test
    fun `a playlist the mirror does not have reads as not found, not as empty`() = runTest {
        viewModel("404").state.test {
            assertTrue(awaitItem().loading)
            val loaded = awaitItem()
            assertTrue(loaded.notFound)
            assertFalse(loaded.isEmpty)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a playlist created offline is marked as never sent`() = runTest {
        playlists.playlistsFlow.value = listOf(SamplePlaylists.neverSent)
        playlists.entriesById.value = mapOf(
            SamplePlaylists.neverSent.id.value to
                SamplePlaylists.entries(SampleLibrary.submarineTracks.take(3)),
        )
        viewModel(SamplePlaylists.neverSent.id.value).state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlist == null) loaded = awaitItem()
            assertEquals(PlaylistSyncState.NEVER_SENT, loaded.syncState)
            // It plays like any other playlist. Nothing about it is broken.
            assertTrue(loaded.hasPlayableTracks)
            assertNull(loaded.notice)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a playlist with holes in it is partly unplayable and says how many`() = runTest {
        stocked(SampleLibrary.submarinePartialTracks)
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            assertTrue(loaded.isPartlyUnplayable)
            assertEquals(2, loaded.unplayableEntries.size)
            // The rows keep their places rather than being dropped.
            assertEquals(8, loaded.entries.size)
            assertTrue(loaded.hasPlayableTracks)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- editing ------------------------------------------------------------

    @Test
    fun `removing a track is sent as its index`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            model.onRemoveTrack(loaded.entries[2])
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertEquals(
                PlaylistNotice.TracksRemoved(count = 1, queued = false),
                current.notice,
            )
            cancelAndIgnoreRemainingEvents()
        }
        val removal: PlaylistEdit.RemoveTracks =
            playlists.edits.filterIsInstance<PlaylistEdit.RemoveTracks>().single()
        assertEquals(listOf(2), removal.positions)
    }

    @Test
    fun `moving a row down sends the whole new order`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            model.onMoveDown(loaded.entries.first())
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertEquals(PlaylistNotice.Reordered(queued = false), current.notice)
            cancelAndIgnoreRemainingEvents()
        }
        // The protocol has no move: a reorder is `createPlaylist` with the complete
        // desired order, so the edit carries every key.
        val reorder: PlaylistEdit.Reorder =
            playlists.edits.filterIsInstance<PlaylistEdit.Reorder>().single()
        assertEquals(8, reorder.trackKeys.size)
        assertEquals(SampleLibrary.submarineTracks[1].key, reorder.trackKeys[0])
        assertEquals(SampleLibrary.submarineTracks[0].key, reorder.trackKeys[1])
        assertEquals(SampleLibrary.submarineTracks[2].key, reorder.trackKeys[2])
    }

    @Test
    fun `moving the first row up sends nothing`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            model.onMoveUp(loaded.entries.first())
            model.onMoveDown(loaded.entries.last())
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playlists.edits.isEmpty())
    }

    @Test
    fun `an unplayable row can still be moved`() = runTest {
        // Its album may simply be missing from the mirror for a sync or two.
        // Refusing to move it would strand it wherever that left it.
        stocked(SampleLibrary.submarinePartialTracks)
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            model.onMoveUp(loaded.entries.first { !it.available })
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, playlists.edits.filterIsInstance<PlaylistEdit.Reorder>().size)
    }

    @Test
    fun `renaming to the same name sends nothing`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlist == null) loaded = awaitItem()
            model.onRenameClick()
            var opened = awaitItem()
            while (opened.rename == null) opened = awaitItem()
            assertEquals("Sunday morning", opened.rename?.name)
            model.onRenameConfirm()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playlists.edits.isEmpty())
    }

    @Test
    fun `renaming with no connection says the server has not been told`() = runTest {
        stocked()
        sessions.connectivityFlow.value = ConnectivityState.Offline
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlist == null) loaded = awaitItem()
            model.onRenameClick()
            var opened = awaitItem()
            while (opened.rename == null) opened = awaitItem()
            model.onRenameNameChange("Sunday mornings")
            model.onRenameConfirm()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            val notice = current.notice
            assertTrue(notice is PlaylistNotice.Renamed && notice.queued)
            assertFalse(current.notice?.isProblem ?: true)
            assertNull(current.rename)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            "Sunday mornings",
            playlists.edits.filterIsInstance<PlaylistEdit.Rename>().single().name,
        )
    }

    @Test
    fun `a confirmed delete leaves the screen`() = runTest {
        stocked()
        var left = false
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.playlist == null) loaded = awaitItem()
            model.onDeleteClick()
            var asked = awaitItem()
            while (!asked.confirmingDelete) asked = awaitItem()
            model.onDeleteConfirm(onDeleted = { left = true })
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue("the host is told to pop the screen", left)
        assertEquals(
            listOf(playlist.id),
            playlists.edits.filterIsInstance<PlaylistEdit.Delete>().map { it.id },
        )
    }

    // ---- adding tracks ------------------------------------------------------

    @Test
    fun `the picker searches the mirror and adds what was ticked, in the order ticked`() = runTest {
        stocked(SampleLibrary.submarineTracks.take(2))
        val found = SampleLibrary.blackClassicalTracks.take(3)
        search.tracksByQuery.value = mapOf("dayes" to found)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()

            model.onAddTracksClick()
            var open = awaitItem()
            while (open.picker == null) open = awaitItem()
            // Nothing typed yet: the hint, not the library.
            assertFalse(open.picker?.hasQuery ?: true)
            assertTrue(open.picker?.results?.isEmpty() ?: false)

            model.onPickerQueryChange("dayes")
            var searched = awaitItem()
            while (searched.picker?.results?.isEmpty() != false) searched = awaitItem()
            assertEquals(3, searched.picker?.results?.size)

            // Ticked in reverse, so the order the playlist receives is the order
            // chosen rather than the order listed.
            model.onToggleCandidate(found[2])
            model.onToggleCandidate(found[0])
            var chosen = awaitItem()
            while ((chosen.picker?.selectedCount ?: 0) < 2) chosen = awaitItem()
            assertEquals("Add 2 tracks", chosen.picker?.confirmLabel)

            model.onAddSelected()
            advanceUntilIdle()
            // The settled state, not the first emission carrying a notice. `picker` is a combine
            // behind a flatMapLatest, so the outer combine publishes the new notice against its
            // cached picker one step before the null reaches it: a loop that stops at the first
            // non-null notice reads a picker that is on its way out. Nothing is idle at that point,
            // and after `advanceUntilIdle` everything is - so the last emission is the assertion.
            val current = expectMostRecentItem()
            assertEquals(
                PlaylistNotice.TracksAdded(count = 2, queued = false),
                current.notice,
            )
            assertNull("the picker closes once the tracks are added", current.picker)
            cancelAndIgnoreRemainingEvents()
        }

        val add: PlaylistEdit.AddTracks =
            playlists.edits.filterIsInstance<PlaylistEdit.AddTracks>().single()
        assertEquals(listOf(found[2].key, found[0].key), add.trackKeys)
        // The local lane only, with the screen's own cap.
        assertEquals(listOf("dayes" to 60), search.queries.filter { it.first == "dayes" })
    }

    @Test
    fun `the picker never offers a track the server has no file for`() = runTest {
        stocked()
        val missing = SampleLibrary.submarinePartialTracks
            .filter { it.fetch.fileId.value == "unavailable" }
        search.tracksByQuery.value = mapOf("paranoia" to missing)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            model.onAddTracksClick()
            var open = awaitItem()
            while (open.picker == null) open = awaitItem()
            model.onPickerQueryChange("paranoia")
            advanceUntilIdle()
            var searched = awaitItem()
            while (searched.picker?.query != "paranoia") searched = awaitItem()
            // Found, and filtered out: an entry with no file is not something the
            // user can have meant to add.
            assertTrue(searched.picker?.results?.isEmpty() ?: false)
            assertTrue(searched.picker?.foundNothing ?: false)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playlists.edits.isEmpty())
    }

    @Test
    fun `cancelling the picker adds nothing and forgets the selection`() = runTest {
        stocked()
        search.tracksByQuery.value = mapOf("dayes" to SampleLibrary.blackClassicalTracks.take(2))
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            model.onAddTracksClick()
            var open = awaitItem()
            while (open.picker == null) open = awaitItem()
            model.onPickerQueryChange("dayes")
            var searched = awaitItem()
            while (searched.picker?.results?.isEmpty() != false) searched = awaitItem()
            model.onToggleCandidate(searched.picker!!.results.first())
            var chosen = awaitItem()
            while ((chosen.picker?.selectedCount ?: 0) == 0) chosen = awaitItem()
            model.onPickerCancel()
            var closed = awaitItem()
            while (closed.picker != null) closed = awaitItem()

            // Re-opening starts clean rather than resuming a selection the user
            // walked away from.
            model.onAddTracksClick()
            var reopened = awaitItem()
            while (reopened.picker == null) reopened = awaitItem()
            assertEquals(0, reopened.picker?.selectedCount)
            assertEquals("", reopened.picker?.query)
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playlists.edits.isEmpty())
    }

    // ---- playback -----------------------------------------------------------

    @Test
    fun `play skips the rows with no file behind them`() = runTest {
        stocked(SampleLibrary.submarinePartialTracks)
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            model.onPlay()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(6, playback.playTracksCalls.single().size)
    }

    @Test
    fun `tapping a row starts it, counting only the playable rows`() = runTest {
        // Track 4 never arrived, so the fifth row of the playlist is the fourth
        // track of the crate. An index into the drawn list would start the wrong song.
        stocked(SampleLibrary.submarinePartialTracks)
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            val fifthRow: PlaylistTrack = loaded.entries[4]
            assertTrue(fifthRow.available)
            model.onPlayTrack(fifthRow)
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
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            model.onPlayTrack(loaded.unplayableEntries.first())
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `shuffle puts the same tracks in the crate, in some order`() = runTest {
        stocked()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.entries.isEmpty()) loaded = awaitItem()
            model.onShuffle()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        // The shuffle happens here rather than by switching the session's global
        // shuffle mode on, so what the player receives is the whole playlist as a
        // plain list. Order is not asserted: a shuffle may legitimately return the
        // original order, and a test that failed one time in 40,000 would be worse
        // than no test.
        val crate = playback.playTracksCalls.single()
        assertEquals(
            SampleLibrary.submarineTracks.map { it.key }.toSet(),
            crate.map { it.key }.toSet(),
        )
    }
}
