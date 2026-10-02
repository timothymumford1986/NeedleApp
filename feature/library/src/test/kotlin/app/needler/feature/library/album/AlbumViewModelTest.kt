package app.needler.feature.library.album

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NetworkStatus
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackState
import app.needler.feature.library.FakeFavouriteRepository
import app.needler.feature.library.FakeLibraryRepository
import app.needler.feature.library.FakePinRepository
import app.needler.feature.library.FakePlaybackController
import app.needler.feature.library.FakePullRepository
import app.needler.feature.library.FakeSessions
import app.needler.feature.library.MainDispatcherRule
import app.needler.feature.library.SampleLibrary
import java.util.Optional
import kotlin.time.Instant
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
class AlbumViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val library = FakeLibraryRepository()
    private val pulls = FakePullRepository()
    private val pins = FakePinRepository()
    private val favourites = FakeFavouriteRepository()
    private val sessions = FakeSessions()
    private val playbackSettings = FakeAlbumPlaybackSettings()
    private val playback = FakePlaybackController()

    private val submarine = SampleLibrary.submarine
    private val mbid = submarine.releaseGroupMbid

    private fun viewModel(albumId: String = mbid.value) = AlbumViewModel(
        savedStateHandle = SavedStateHandle(mapOf(AlbumViewModel.ALBUM_ID_ARG to albumId)),
        library = library,
        pulls = pulls,
        pins = pins,
        favourites = favourites,
        sessions = sessions,
        playbackSettings = playbackSettings,
        playback = Optional.of(playback),
    )

    /**
     * Pull, then confirm the sheet.
     *
     * Pulling is two steps now: the tap opens the sheet and the sheet places the request.
     * Every test that used to call `onPull` alone goes through both, because a test that
     * stopped at the tap would assert that nothing had been sent.
     */
    private fun AlbumViewModel.pullAndConfirm(monitorArtist: Boolean = false) {
        onPull()
        if (monitorArtist) onMonitorArtistChange(true)
        onConfirmRequest()
    }

    private fun ownedSubmarine() {
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarineTracks)
    }

    /** A crate of [tracks], as `playAlbum` or a playlist would have built it. */
    private fun crate(tracks: List<Track>, currentIndex: Int = 0): PlayQueue = PlayQueue(
        items = tracks.mapIndexed { index, track -> QueueItem(id = "q-" + index, track = track) },
        currentIndex = currentIndex,
    )

    /**
     * Put [queue] in the session and say whether it is playing.
     *
     * Both flows, because the album screen's transport needs both: the state says what the session
     * is doing, the crate says what it is doing it to. Setting only one of them is how a test comes
     * to assert on a transport the screen could never show.
     */
    private fun loadCrate(queue: PlayQueue, playing: Boolean) {
        playback.queue.value = queue
        playback.playbackState.value =
            PlaybackState(currentItem = queue.currentItem, isPlaying = playing)
    }

    // ---- states -------------------------------------------------------------

    @Test
    fun `an owned album offers Play`() = runTest {
        ownedSubmarine()
        viewModel().state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(AlbumPrimaryAction.PLAY, loaded.primaryAction)
            assertEquals(8, loaded.tracks.size)
            assertTrue(loaded.hasPlayableTracks)
            assertFalse(loaded.isPartiallyDelivered)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an album you do not own offers Pull and draws the catalogue track list`() = runTest {
        val notOwned = SampleLibrary.blackClassicalMusic
        library.albumsByMbid.value = mapOf(notOwned.releaseGroupMbid.value to notOwned)
        library.tracksByMbid.value =
            mapOf(notOwned.releaseGroupMbid.value to SampleLibrary.blackClassicalTracks)

        viewModel(notOwned.releaseGroupMbid.value).state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(AlbumPrimaryAction.PULL, loaded.primaryAction)
            assertEquals(SampleLibrary.blackClassicalTracks.size, loaded.tracks.size)
            // Nothing in a catalogue track list plays: those files exist
            // nowhere yet. Screen 05 greys every row for this reason.
            assertTrue(loaded.tracks.none { it.available })
            assertFalse(loaded.hasPlayableTracks)
            // It is not "partially delivered" either - you own none of it.
            assertFalse(loaded.isPartiallyDelivered)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a request waiting for approval offers no action at all`() = runTest {
        library.albumsByMbid.value = mapOf(
            mbid.value to submarine.copy(state = AlbumState.PendingApproval()),
        )
        viewModel().state.test {
            awaitItem()
            assertEquals(AlbumPrimaryAction.WAITING, awaitItem().primaryAction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an acquiring album reports its progress`() = runTest {
        library.albumsByMbid.value = mapOf(
            mbid.value to submarine.copy(
                state = AlbumState.Acquiring(progress = PullProgress(percent = 62)),
            ),
        )
        viewModel().state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(AlbumPrimaryAction.ACQUIRING, loaded.primaryAction)
            val state = loaded.album?.state as AlbumState.Acquiring
            assertEquals(0.62f, state.progress.fraction!!, 0.0001f)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a failed pull offers Retry`() = runTest {
        library.albumsByMbid.value = mapOf(
            mbid.value to submarine.copy(
                state = AlbumState.Failed(
                    reason = app.needler.core.domain.model.PullFailureReason.NO_SOURCE_FOUND,
                ),
            ),
        )
        viewModel().state.test {
            awaitItem()
            assertEquals(AlbumPrimaryAction.RETRY, awaitItem().primaryAction)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a pinned album reports its download state`() = runTest {
        ownedSubmarine()
        library.albumsByMbid.value = mapOf(
            mbid.value to submarine.copy(
                state = AlbumState.Pinned(download = OfflineDownloadState.Complete),
            ),
        )
        pins.pins.value = mapOf(
            mbid.value to Pin(
                releaseGroupMbid = mbid,
                pinnedAt = Instant.parse("2026-09-20T10:00:00Z"),
                source = PinSource.MANUAL,
                download = OfflineDownloadState.Downloading(tracksComplete = 3, tracksTotal = 8),
            ),
        )
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.download == null) loaded = awaitItem()
            val downloading = loaded.download as OfflineDownloadState.Downloading
            assertEquals(3, downloading.tracksComplete)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- partial delivery ---------------------------------------------------

    @Test
    fun `a part-delivered album plays what arrived and marks what did not`() = runTest {
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarinePartialTracks)

        viewModel().state.test {
            awaitItem()
            val loaded = awaitItem()
            assertTrue(loaded.isPartiallyDelivered)
            assertEquals(2, loaded.missingTracks.size)
            assertEquals(listOf(4, 7), loaded.missingTracks.map { it.position })
            // The album is still in the library and still plays.
            assertEquals(AlbumPrimaryAction.PLAY, loaded.primaryAction)
            assertTrue(loaded.hasPlayableTracks)
            // The missing tracks keep their positions rather than being dropped.
            assertEquals(8, loaded.tracks.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an album whose first track never arrived still starts on a playable one`() = runTest {
        val tracks = SampleLibrary.submarineTracks.mapIndexed { index, track ->
            if (index == 0) {
                SampleLibrary.track(
                    albumSlug = "submarine",
                    number = 1,
                    title = track.title,
                    artistName = track.artistName,
                    durationMs = track.durationMs ?: 0L,
                    available = false,
                )
            } else {
                track
            }
        }
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to tracks)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(1, loaded.firstPlayableIndex)
            model.onPlayPause()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, playback.playAlbumCalls.single().startIndex)
    }

    @Test
    fun `tapping a missing track never reaches the player`() = runTest {
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarinePartialTracks)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            model.onPlayTrack(loaded.missingTracks.first())
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.playAlbumCalls.isEmpty())
    }

    @Test
    fun `retrying a missing track asks for its recording, not the whole album`() = runTest {
        val withRecording = SampleLibrary.submarinePartialTracks.map { existing ->
            if (existing.key.trackNumber == 4) {
                existing.copy(
                    recordingMbid = app.needler.core.domain.model.RecordingMbid("rec-paranoia"),
                )
            } else {
                existing
            }
        }
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to withRecording)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            model.onRetryTrack(loaded.tracks.first { it.position == 4 })
            advanceUntilIdle()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, pulls.trackRequests.size)
        assertEquals("rec-paranoia", pulls.trackRequests.single().recordingMbid.value)
        assertTrue("the album request was not used", pulls.retried.isEmpty())
    }

    @Test
    fun `a missing track with no recording MBID falls back to retrying the album`() = runTest {
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarinePartialTracks)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            model.onRetryTrack(loaded.tracks.first { it.position == 4 })
            advanceUntilIdle()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(pulls.trackRequests.isEmpty())
        assertEquals(listOf(mbid), pulls.retried)
    }

    // ---- the transport ------------------------------------------------------
    // Punch-list 8b. Every test here asserts what the control *does* as well as what it says. The
    // device audit found a button reading "Play" on the album that was already playing, and tapping
    // it took `position=32975` to `position=0` and threw 76 seconds of buffer away. A test that
    // only read the label would have passed on that defect, which is the lesson of the exercise.

    @Test
    fun `this album playing offers Pause, and pausing does not reload the crate`() = runTest {
        ownedSubmarine()
        loadCrate(crate(SampleLibrary.submarineTracks, currentIndex = 1), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(AlbumTransport.PAUSE, loaded.transport)
            assertEquals("Pause", loaded.transport.primaryLabel)
            assertEquals("Pause Submarine", loaded.transport.primaryDescription("Submarine"))
            model.onPlayPause()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("pause"), playback.transportCommands)
        assertTrue("pausing must not reload the album", playback.playAlbumCalls.isEmpty())
    }

    @Test
    fun `this album paused resumes where it stopped rather than starting over`() = runTest {
        ownedSubmarine()
        loadCrate(crate(SampleLibrary.submarineTracks, currentIndex = 1), playing = false)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(AlbumTransport.RESUME, loaded.transport)
            assertEquals("Resume", loaded.transport.primaryLabel)
            assertEquals(
                "Resume Submarine where it stopped",
                loaded.transport.primaryDescription("Submarine"),
            )
            model.onPlayPause()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("play"), playback.transportCommands)
        // This is the defect itself. `playAlbum` replaces the crate and seeks to zero, so reaching
        // it from a paused album is the lost position, whatever the button happened to say.
        assertTrue(
            "resuming must not reach playAlbum - that is what destroys the position",
            playback.playAlbumCalls.isEmpty(),
        )
    }

    @Test
    fun `a different album playing leaves Play meaning play from the start`() = runTest {
        ownedSubmarine()
        loadCrate(crate(SampleLibrary.blackClassicalTracks), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(AlbumTransport.START, loaded.transport)
            assertEquals("Play", loaded.transport.primaryLabel)
            assertEquals("Play Submarine", loaded.transport.primaryDescription("Submarine"))
            // Nothing of this record is playing, so no row of it is marked either.
            assertTrue(loaded.tracks.none { it.key == loaded.nowPlayingTrackKey })
            model.onPlayPause()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("playAlbum"), playback.transportCommands)
        val call = playback.playAlbumCalls.single()
        assertEquals(mbid, call.mbid)
        assertEquals(0, call.startIndex)
        assertFalse(call.shuffle)
    }

    @Test
    fun `nothing loaded offers Play and starts this album`() = runTest {
        ownedSubmarine()
        // The session is left Idle with an empty crate: a cold start, or everything cleared.

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(AlbumTransport.START, loaded.transport)
            assertNull(loaded.nowPlayingTrackKey)
            model.onPlayPause()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(mbid, playback.playAlbumCalls.single().mbid)
    }

    /**
     * The wrong answer, caught.
     *
     * "The current track belongs to this album" is true here and "this album is the loaded queue"
     * is false. Had the screen used the first test, every album with a track in a playlist crate
     * would have offered Pause, and none of them could have been played from its own screen.
     */
    @Test
    fun `a crate holding one track of this album is not this album`() = runTest {
        ownedSubmarine()
        val mixed: List<Track> =
            listOf(SampleLibrary.submarineTracks[1]) + SampleLibrary.blackClassicalTracks
        loadCrate(crate(mixed, currentIndex = 0), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(AlbumTransport.START, loaded.transport)
            assertEquals("Play", loaded.transport.primaryLabel)
            // The row highlight answers a different question and keeps its own answer: this *is*
            // the track you are hearing, whatever else is queued behind it.
            assertEquals(SampleLibrary.submarineTracks[1].key, loaded.nowPlayingTrackKey)
            model.onPlayPause()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("playAlbum"), playback.transportCommands)
        assertEquals(mbid, playback.playAlbumCalls.single().mbid)
    }

    /**
     * A part-delivered album is still "the loaded queue".
     *
     * `playAlbum` queues the tracks that arrived, so the crate is a subset of the track list. A
     * rule that asked for the whole album would have left the part-delivered records - the ones
     * most likely to be replayed while the rest is still coming - as the only ones still losing
     * their position. REQUIREMENTS.md "Partial content is a normal state".
     */
    @Test
    fun `a part-delivered album counts as loaded from the tracks that arrived`() = runTest {
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarinePartialTracks)
        val delivered: List<Track> = SampleLibrary.submarinePartialTracks
            .filter { it.key.trackNumber != 4 && it.key.trackNumber != 7 }
        loadCrate(crate(delivered), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals(6, delivered.size)
            assertEquals(AlbumTransport.PAUSE, loaded.transport)
            model.onPlayPause()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf("pause"), playback.transportCommands)
    }

    @Test
    fun `the control follows the session, not the state the screen opened in`() = runTest {
        ownedSubmarine()
        loadCrate(crate(SampleLibrary.submarineTracks, currentIndex = 1), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            assertEquals(AlbumTransport.PAUSE, awaitItem().transport)
            // The lock screen, a widget, Auto or Wear pauses the same session - REQUIREMENTS.md
            // "The player boundary": this screen is one more client of it, not its owner.
            playback.playbackState.value = playback.playbackState.value.copy(isPlaying = false)
            assertEquals(AlbumTransport.RESUME, awaitItem().transport)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- actions ------------------------------------------------------------

    @Test
    fun `shuffle asks the controller to shuffle rather than shuffling the list here`() = runTest {
        ownedSubmarine()
        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals("Shuffle", loaded.transport.shuffleLabel)
            assertEquals("Shuffle Submarine", loaded.transport.shuffleDescription("Submarine"))
            model.onShuffle()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.playAlbumCalls.single().shuffle)
    }

    /**
     * Re-shuffling is a restart, and it says so before the tap.
     *
     * There is no non-destructive re-shuffle to offer, so the fix is the label rather than the
     * behaviour - see `AlbumTransport`. The spoken description names the restart because that is
     * the one thing a listener cannot undo afterwards.
     */
    @Test
    fun `shuffling an album you are already inside says it will start over`() = runTest {
        ownedSubmarine()
        loadCrate(crate(SampleLibrary.submarineTracks, currentIndex = 3), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            val loaded = awaitItem()
            assertEquals("Shuffle again", loaded.transport.shuffleLabel)
            assertEquals(
                "Shuffle Submarine again. This starts the album over in a new order",
                loaded.transport.shuffleDescription("Submarine"),
            )
            model.onShuffle()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.playAlbumCalls.single().shuffle)
    }

    @Test
    fun `a pull that needs approval says so rather than claiming it started`() = runTest {
        val notOwned = SampleLibrary.blackClassicalMusic
        library.albumsByMbid.value = mapOf(notOwned.releaseGroupMbid.value to notOwned)
        pulls.requestAlbumOutcome = Outcome.Success(
            RequestReceipt(
                releaseGroupMbid = notOwned.releaseGroupMbid,
                status = RequestStatus.PENDING_APPROVAL,
            ),
        )

        val model = viewModel(notOwned.releaseGroupMbid.value)
        model.state.test {
            awaitItem()
            awaitItem()
            model.pullAndConfirm()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertEquals(AlbumNotice.PullPendingApproval, current.notice)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, pulls.albumRequests.size)
        assertEquals(notOwned.title, pulls.albumRequests.single().albumTitle)
    }

    @Test
    fun `a pull placed offline is queued, not lost`() = runTest {
        val notOwned = SampleLibrary.blackClassicalMusic
        library.albumsByMbid.value = mapOf(notOwned.releaseGroupMbid.value to notOwned)
        sessions.connectivityFlow.value =
            app.needler.core.domain.model.ConnectivityState.Offline
        pulls.requestAlbumOutcome = Outcome.Success(
            RequestReceipt(
                releaseGroupMbid = notOwned.releaseGroupMbid,
                status = RequestStatus.QUEUED_OFFLINE,
            ),
        )

        val model = viewModel(notOwned.releaseGroupMbid.value)
        model.state.test {
            awaitItem()
            awaitItem()
            model.pullAndConfirm()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertEquals(AlbumNotice.PullQueuedOffline, current.notice)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `downloading to the device reports that it is waiting for Wi-Fi`() = runTest {
        ownedSubmarine()
        pins.preferences.value =
            app.needler.core.domain.model.StoragePreferences(downloadToDeviceOnWifiOnly = true)
        sessions.connectivityFlow.value = app.needler.core.domain.model.ConnectivityState(
            status = app.needler.core.domain.model.NetworkStatus.METERED,
        )

        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onDownloadToDevice()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertEquals(AlbumNotice.DownloadWaitingForWifi, current.notice)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(mbid), pins.pinned)
    }

    @Test
    fun `removing a download reports the bytes it actually freed`() = runTest {
        ownedSubmarine()
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onRemoveFromDevice()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            val notice = current.notice as AlbumNotice.RemovedFromDevice
            assertEquals(412_000_000L, notice.freedBytes)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(mbid), pins.unpinned)
    }

    @Test
    fun `an administrator who forbids downloads hides the affordance`() = runTest {
        ownedSubmarine()
        sessions.capabilitiesFlow.value = ServerCapabilities(
            subsonicEnabled = true,
            transcodingAvailable = false,
            libraryDownloadAllowed = false,
        )
        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.downloadAllowed) loaded = awaitItem()
            assertFalse(loaded.downloadAllowed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an expired session explains itself instead of failing silently`() = runTest {
        val notOwned = SampleLibrary.blackClassicalMusic
        library.albumsByMbid.value = mapOf(notOwned.releaseGroupMbid.value to notOwned)
        pulls.requestAlbumOutcome = Outcome.Failure(NeedlerError.SessionExpired)

        val model = viewModel(notOwned.releaseGroupMbid.value)
        model.state.test {
            awaitItem()
            awaitItem()
            model.pullAndConfirm()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            val notice = current.notice as AlbumNotice.Problem
            assertTrue(notice.isProblem)
            assertTrue(notice.message.contains("keep playing"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an album the mirror does not have reports not-found rather than loading forever`() =
        runTest {
            viewModel("rg-missing").state.test {
                awaitItem()
                val loaded = awaitItem()
                assertFalse(loaded.loading)
                assertTrue(loaded.notFound)
                assertNull(loaded.album)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the album is refreshed once on open`() = runTest {
        ownedSubmarine()
        viewModel().state.test {
            awaitItem()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(listOf(mbid), library.refreshedAlbums)
    }

    // ---- the request sheet --------------------------------------------------

    @Test
    fun `Pull opens the sheet and sends nothing until it is confirmed`() = runTest {
        val notOwned = SampleLibrary.blackClassicalMusic
        library.albumsByMbid.value = mapOf(notOwned.releaseGroupMbid.value to notOwned)

        val model = viewModel(notOwned.releaseGroupMbid.value)
        model.state.test {
            awaitItem()
            awaitItem()
            model.onPull()
            var open = awaitItem()
            while (open.requestSheet == null) open = awaitItem()
            assertEquals(notOwned.title, open.requestSheet?.title)
            assertFalse(open.requestSheet?.monitorArtist ?: true)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        // The whole point: a tap is not a request.
        assertTrue(pulls.albumRequests.isEmpty())
    }

    @Test
    fun `the monitor artist toggle reaches the request body`() = runTest {
        val notOwned = SampleLibrary.blackClassicalMusic
        library.albumsByMbid.value = mapOf(notOwned.releaseGroupMbid.value to notOwned)

        val model = viewModel(notOwned.releaseGroupMbid.value)
        model.state.test {
            awaitItem()
            awaitItem()
            model.pullAndConfirm(monitorArtist = true)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        // RequestAlbumUseCase has taken this flag since it was written and no caller ever
        // passed it. This assertion is the one that would have caught that.
        assertTrue(pulls.albumRequests.single().monitorArtist)
    }

    @Test
    fun `dismissing the sheet sends nothing`() = runTest {
        val notOwned = SampleLibrary.blackClassicalMusic
        library.albumsByMbid.value = mapOf(notOwned.releaseGroupMbid.value to notOwned)

        val model = viewModel(notOwned.releaseGroupMbid.value)
        model.state.test {
            awaitItem()
            awaitItem()
            model.onPull()
            model.onDismissRequestSheet()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(pulls.albumRequests.isEmpty())
    }

    // ---- favourites ---------------------------------------------------------

    @Test
    fun `starring the album stars the release group, never a file id`() = runTest {
        ownedSubmarine()
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onToggleFavourite()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(
            listOf(app.needler.core.domain.model.FavouriteTarget.OfAlbum(mbid) to true),
            favourites.calls,
        )
    }

    @Test
    fun `un-starring an already starred album asks for the opposite`() = runTest {
        library.albumsByMbid.value = mapOf(
            mbid.value to submarine.copy(state = AlbumState.Owned, isFavourite = true),
        )
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarineTracks)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (!loaded.isFavourite) loaded = awaitItem()
            model.onToggleFavourite()
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(favourites.calls.single().second)
    }

    /**
     * REQUIREMENTS.md "Track identity is not stable": `file_id` "must never be used as an
     * offline cache key", and the same reasoning makes it useless as a favourite key. A
     * star keyed on it would come unstuck from its track the first time the server found a
     * better copy.
     */
    @Test
    fun `starring a track is keyed on the stable track key`() = runTest {
        ownedSubmarine()
        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.tracks.isEmpty()) loaded = awaitItem()
            model.onToggleTrackFavourite(loaded.tracks.first())
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        val target = favourites.calls.single().first
        assertTrue(target is app.needler.core.domain.model.FavouriteTarget.OfTrack)
        assertEquals(
            SampleLibrary.submarineTracks.first().key,
            (target as app.needler.core.domain.model.FavouriteTarget.OfTrack).key,
        )
    }

    @Test
    fun `a star the server refuses says so`() = runTest {
        ownedSubmarine()
        favourites.setOutcome = Outcome.Failure(NeedlerError.SessionExpired)
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
            model.onToggleFavourite()
            advanceUntilIdle()
            var current = awaitItem()
            while (current.notice == null) current = awaitItem()
            assertTrue((current.notice as AlbumNotice.Problem).isProblem)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- the quality tag pair -----------------------------------------------

    @Test
    fun `a pulled album makes Pulled the tag in force and dims Server`() = runTest {
        // Submarine is pinned and complete in the sample library, and the header has to say that the
        // local copy is what plays - a local copy always wins over any streaming setting.
        library.albumsByMbid.value = mapOf(mbid.value to submarine)
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarineTracks)
        sessions.capabilitiesFlow.value = TRANSCODING_SERVER
        sessions.connectivityFlow.value = ConnectivityState(NetworkStatus.METERED)
        playbackSettings.setDataStreamRung(StreamRung.MP3_192)

        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            assertTrue(loaded.isPulled)
            assertEquals("FLAC", loaded.pulledTagValue)
            // Still shown, still true, and no longer what you would hear.
            assertEquals("MP3 192", loaded.serverTagValue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the server tag follows the connection`() = runTest {
        ownedSubmarine()
        sessions.capabilitiesFlow.value = TRANSCODING_SERVER
        playbackSettings.setDataStreamRung(StreamRung.MP3_192)

        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            // Unmetered: the Wi-Fi rung is Original, so the file is sent untouched and the tag names
            // the source rather than a rung nothing reached.
            assertEquals("FLAC", loaded.serverTagValue)
            assertFalse(loaded.isPulled)

            sessions.connectivityFlow.value = ConnectivityState(NetworkStatus.METERED)
            var metered = awaitItem()
            while (metered.serverTagValue == "FLAC") metered = awaitItem()
            assertEquals("MP3 192", metered.serverTagValue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an album override beats the mode default on both connections`() = runTest {
        ownedSubmarine()
        sessions.capabilitiesFlow.value = TRANSCODING_SERVER
        val model = viewModel()

        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            assertEquals("FLAC", loaded.serverTagValue)
            assertNull(loaded.qualityOverride)

            // Unmetered, Wi-Fi rung Original, and the album still transcodes: this is what makes the
            // override absolute rather than "metered only".
            model.onOverrideQuality(StreamRung.MP3_128)
            advanceUntilIdle()
            var overridden = awaitItem()
            while (overridden.qualityOverride == null) overridden = awaitItem()
            assertEquals("MP3 128", overridden.serverTagValue)

            model.onClearQualityOverride()
            advanceUntilIdle()
            var cleared = awaitItem()
            while (cleared.qualityOverride != null) cleared = awaitItem()
            assertEquals("FLAC", cleared.serverTagValue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a server that cannot transcode shows the source whatever the rung`() = runTest {
        // REQUIREMENTS.md rule 3 of "Streaming": with no ffmpeg every rung resolves to original bytes,
        // so a tag promising MP3 192 would be describing something the server will never send.
        ownedSubmarine()
        sessions.connectivityFlow.value = ConnectivityState(NetworkStatus.METERED)
        playbackSettings.setDataStreamRung(StreamRung.MP3_192)

        viewModel().state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            assertEquals("FLAC", loaded.serverTagValue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- the crate ----------------------------------------------------------
    // Nothing in the library could be added to the crate: Play, Shuffle and every track row
    // replaced it, and long-pressing a row played that track and threw the queue away. Each test
    // here asserts the negative as well as the positive - that `enqueue` was used and that
    // `playAlbum` and `playTracks`, the two commands that replace the crate, were not.

    @Test
    fun `adding an album appends to the crate rather than replacing it`() = runTest {
        ownedSubmarine()
        loadCrate(crate(SampleLibrary.submarineTracks.take(2)), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            model.onAddToCrate(playNext = false)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals(AlbumNotice.AddedToCrate(trackCount = 8), after.notice)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, playback.enqueueCalls.size)
        assertEquals(8, playback.enqueueCalls.single().tracks.size)
        assertFalse(playback.enqueueCalls.single().playNext)
        assertTrue("the crate must not be replaced", playback.playAlbumCalls.isEmpty())
        assertTrue("the crate must not be replaced", playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `the crate's count and duration follow the add`() = runTest {
        ownedSubmarine()
        loadCrate(crate(SampleLibrary.submarineTracks.take(2)), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            assertEquals(2, loaded.crateTrackCount)

            model.onAddToCrate(playNext = false)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            // Two tracks already in it plus the record's eight, and the total duration of all
            // ten - read off the session's own crate, not predicted here.
            assertEquals(10, after.crateTrackCount)
            assertEquals(2_120_000L, after.crateDurationMs)
            assertEquals("10 in the crate · 35 min", after.crateLine)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `adding while nothing is loaded starts playback and says so`() = runTest {
        // `enqueue` never starts sound, so an add to an empty crate would otherwise leave a
        // loaded crate and silence - a tap with no evidence it registered.
        ownedSubmarine()

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            model.onAddToCrate(playNext = false)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals(
                AlbumNotice.AddedToCrate(trackCount = 8, started = true),
                after.notice,
            )
            assertEquals(8, after.crateTrackCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, playback.playTracksCalls.size)
        assertTrue("nothing to append to", playback.enqueueCalls.isEmpty())
    }

    @Test
    fun `play next asks for the insert, not the append`() = runTest {
        ownedSubmarine()
        loadCrate(crate(SampleLibrary.submarineTracks.take(2)), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            model.onAddToCrate(playNext = true)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals(
                AlbumNotice.AddedToCrate(trackCount = 8, playNext = true),
                after.notice,
            )
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.enqueueCalls.single().playNext)
        // Inserted after the Playing row rather than at the end: the crate's second row is the
        // first track of the record that was added.
        assertEquals(
            SampleLibrary.submarineTracks.first().key,
            playback.queue.value.items[1].track.key,
        )
    }

    @Test
    fun `adding one row adds that track and nothing else`() = runTest {
        ownedSubmarine()
        loadCrate(crate(SampleLibrary.submarineTracks.take(2)), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            model.onAddTrackToCrate(loaded.tracks.first { it.position == 3 }, playNext = false)
            advanceUntilIdle()
            val after = expectMostRecentItem()
            assertEquals(AlbumNotice.AddedToCrate(trackCount = 1), after.notice)
            assertEquals(3, after.crateTrackCount)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals("Hamptons", playback.enqueueCalls.single().tracks.single().title)
    }

    @Test
    fun `adding a row with no file behind it never reaches the player`() = runTest {
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarinePartialTracks)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            model.onAddTrackToCrate(loaded.missingTracks.first(), playNext = false)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(playback.enqueueCalls.isEmpty())
        assertTrue(playback.playTracksCalls.isEmpty())
    }

    @Test
    fun `adding a record drops the tracks a part-delivered pull never brought`() = runTest {
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarinePartialTracks)
        loadCrate(crate(SampleLibrary.submarineTracks.take(1)), playing = true)

        val model = viewModel()
        model.state.test {
            awaitItem()
            var loaded = awaitItem()
            while (loaded.album == null) loaded = awaitItem()
            model.onAddToCrate(playNext = false)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        // Eight tracks, two of which exist nowhere: a crate holding them would stall at a gap
        // the user cannot see the reason for.
        assertEquals(6, playback.enqueueCalls.single().tracks.size)
    }

    private companion object {
        /** A server with ffmpeg. `FakeSessions` defaults to one without, which is the other case above. */
        val TRANSCODING_SERVER: ServerCapabilities = ServerCapabilities(
            subsonicEnabled = true,
            transcodingAvailable = true,
            libraryDownloadAllowed = true,
        )
    }
}
