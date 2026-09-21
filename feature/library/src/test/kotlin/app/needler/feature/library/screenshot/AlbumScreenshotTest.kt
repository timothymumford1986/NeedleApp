package app.needler.feature.library.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.PullFailureReason
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.Track
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.album.AlbumScreen
import app.needler.feature.library.album.AlbumTrack
import app.needler.feature.library.album.AlbumUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the album screen in every state `AlbumState` has.
 *
 * REQUIREMENTS.md's state table is the list of tests: not owned, waiting for
 * approval, acquiring with a percentage, owned, pinned, failed with a retry —
 * plus the part-delivered album, which is `Owned` with holes in it and is the
 * state most likely to be got wrong.
 *
 * Screens 04 and 05 are the same composable with a different `Album.state`, and
 * these two PNGs are the evidence: `album-owned-phone.png` beside
 * `design/png/04-AlbumOwned.png`, `album-not-owned-phone.png` beside
 * `design/png/05-AlbumPull.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class AlbumScreenshotTest {

    @Test
    fun `an album you own`() {
        capture("album-owned", NeedlerDevice.Phone, owned())
    }

    @Test
    fun `an album you own, on a tablet`() {
        val file = captureNeedlerScreen("album-owned", NeedlerDevice.Tablet) {
            TabletFrame { Screen(owned(), WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `an album you do not own offers Pull this album`() {
        capture("album-not-owned", NeedlerDevice.Phone, notOwned())
    }

    @Test
    fun `an album you do not own, on a tablet`() {
        val file = captureNeedlerScreen("album-not-owned", NeedlerDevice.Tablet) {
            TabletFrame { Screen(notOwned(), WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `waiting for an administrator to approve the pull`() {
        capture(
            "album-pending-approval",
            NeedlerDevice.Phone,
            notOwned().withState(AlbumState.PendingApproval()),
        )
    }

    @Test
    fun `acquiring, with its percentage`() {
        capture(
            "album-acquiring",
            NeedlerDevice.Phone,
            notOwned().withState(
                AlbumState.Acquiring(
                    progress = PullProgress(percent = 62, filesCompleted = 12, filesTotal = 19),
                    stage = PullState.DOWNLOADING,
                ),
            ),
        )
    }

    @Test
    fun `searching for a source, before anything is downloading`() {
        capture(
            "album-searching",
            NeedlerDevice.Phone,
            notOwned().withState(
                AlbumState.Acquiring(
                    progress = PullProgress.Unknown,
                    stage = PullState.SEARCHING,
                ),
            ),
        )
    }

    @Test
    fun `pinned and fully on this device`() {
        capture(
            "album-on-device",
            NeedlerDevice.Phone,
            owned().let { state ->
                state.copy(
                    album = state.album?.copy(
                        state = AlbumState.Pinned(download = OfflineDownloadState.Complete),
                    ),
                    download = OfflineDownloadState.Complete,
                )
            },
        )
    }

    @Test
    fun `pinned and still downloading`() {
        capture(
            "album-downloading",
            NeedlerDevice.Phone,
            owned().let { state ->
                state.copy(
                    album = state.album?.copy(
                        state = AlbumState.Pinned(
                            download = OfflineDownloadState.Downloading(3, 8),
                        ),
                    ),
                    download = OfflineDownloadState.Downloading(tracksComplete = 3, tracksTotal = 8),
                )
            },
        )
    }

    @Test
    fun `failed, with a reason and a retry`() {
        capture(
            "album-failed",
            NeedlerDevice.Phone,
            notOwned().withState(
                AlbumState.Failed(reason = PullFailureReason.NO_SOURCE_FOUND),
            ),
        )
    }

    @Test
    fun `a part-delivered pull plays what arrived and greys what did not`() {
        capture("album-partial", NeedlerDevice.Phone, partial())
    }

    @Test
    fun `a part-delivered pull, on a tablet`() {
        val file = captureNeedlerScreen("album-partial", NeedlerDevice.Tablet) {
            TabletFrame { Screen(partial(), WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `an administrator who forbids downloads hides Pull local`() {
        capture(
            "album-download-forbidden",
            NeedlerDevice.Phone,
            owned().copy(downloadAllowed = false),
        )
    }

    @Test
    fun `the notice after a pull is queued offline`() {
        capture(
            "album-queued-offline",
            NeedlerDevice.Phone,
            notOwned().copy(offline = true, notice = AlbumNotice.PullQueuedOffline),
        )
    }

    @Test
    fun `loading, before the mirror has answered`() {
        capture("album-loading", NeedlerDevice.Phone, AlbumUiState(loading = true))
    }

    @Test
    fun `an album the mirror does not have`() {
        capture("album-not-found", NeedlerDevice.Phone, AlbumUiState(loading = false))
    }

    @Test
    fun `at 200 percent text size`() {
        capture("album-large-text", NeedlerDevice.Phone, owned(), fontScale = 2f)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: AlbumUiState,
        fontScale: Float = 1f,
    ) {
        val file = captureNeedlerScreen(name, device, fontScale) {
            Screen(
                state = state,
                widthSizeClass = when (device) {
                    NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                    NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
                },
            )
        }
        assertRendered(file, device)
    }

    @Composable
    private fun Screen(state: AlbumUiState, widthSizeClass: WindowWidthSizeClass) {
        AlbumScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onBack = {},
            onPlay = {},
            onShuffle = {},
            onPlayTrack = {},
            onPull = {},
            onCancelPull = {},
            onRetryPull = {},
            onDownloadToDevice = {},
            onRemoveFromDevice = {},
            onRetryTrack = {},
            onOpenArtist = {},
            onDismissNotice = {},
        )
    }

    private fun AlbumUiState.withState(state: AlbumState): AlbumUiState =
        copy(album = album?.copy(state = state))

    /** The same mapping `AlbumViewModel` makes: playable needs owned *and* a file. */
    private fun rows(tracks: List<Track>, owned: Boolean): List<AlbumTrack> =
        tracks.mapIndexed { index, track ->
            AlbumTrack(
                position = track.key.trackNumber.takeIf { it > 0 } ?: (index + 1),
                track = track,
                available = owned && track.fetch.fileId.value != "unavailable",
            )
        }

    private fun owned(): AlbumUiState = AlbumUiState(
        loading = false,
        album = SampleLibrary.submarine.copy(state = AlbumState.Owned),
        tracks = rows(SampleLibrary.submarineTracks, owned = true),
        // Screen 04 draws Sienna as the playing track.
        nowPlayingTrackKey = SampleLibrary.submarineTracks[1].key,
    )

    private fun notOwned(): AlbumUiState = AlbumUiState(
        loading = false,
        album = SampleLibrary.blackClassicalMusic,
        tracks = rows(SampleLibrary.blackClassicalTracks, owned = false),
    )

    private fun partial(): AlbumUiState = AlbumUiState(
        loading = false,
        album = SampleLibrary.submarine.copy(state = AlbumState.Owned),
        tracks = rows(SampleLibrary.submarinePartialTracks, owned = true),
    )
}
