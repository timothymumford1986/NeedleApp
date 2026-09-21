package app.needler.feature.library.album

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.RequestReceipt
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.ServerCapabilities
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
    private val sessions = FakeSessions()
    private val playback = FakePlaybackController()

    private val submarine = SampleLibrary.submarine
    private val mbid = submarine.releaseGroupMbid

    private fun viewModel(albumId: String = mbid.value) = AlbumViewModel(
        savedStateHandle = SavedStateHandle(mapOf(AlbumViewModel.ALBUM_ID_ARG to albumId)),
        library = library,
        pulls = pulls,
        pins = pins,
        sessions = sessions,
        playback = Optional.of(playback),
    )

    private fun ownedSubmarine() {
        library.albumsByMbid.value = mapOf(mbid.value to submarine.copy(state = AlbumState.Owned))
        library.tracksByMbid.value = mapOf(mbid.value to SampleLibrary.submarineTracks)
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
            model.onPlay()
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

    // ---- actions ------------------------------------------------------------

    @Test
    fun `shuffle asks the controller to shuffle rather than shuffling the list here`() = runTest {
        ownedSubmarine()
        val model = viewModel()
        model.state.test {
            awaitItem()
            awaitItem()
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
            model.onPull()
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
            model.onPull()
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
            model.onPull()
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
}
