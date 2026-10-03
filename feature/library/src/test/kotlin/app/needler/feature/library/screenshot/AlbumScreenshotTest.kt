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
import app.needler.feature.library.album.AlbumTransport
import app.needler.feature.library.album.AlbumUiState
import app.needler.feature.library.common.RequestSheetState
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

    // ---- the new controls ---------------------------------------------------

    @Test
    fun `a starred album, with a starred track in the list`() {
        capture("album-starred", NeedlerDevice.Phone, starred())
    }

    @Test
    fun `the request sheet, with the monitor artist toggle`() {
        capture(
            "album-request-sheet",
            NeedlerDevice.Phone,
            notOwned().copy(requestSheet = RequestSheetState.forAlbum(SampleLibrary.blackClassicalMusic)),
        )
    }

    @Test
    fun `the request sheet with monitoring turned on`() {
        capture(
            "album-request-sheet-monitoring",
            NeedlerDevice.Phone,
            notOwned().copy(
                requestSheet = RequestSheetState
                    .forAlbum(SampleLibrary.blackClassicalMusic)
                    .copy(monitorArtist = true),
            ),
        )
    }

    @Test
    fun `the request sheet at 200 percent text size`() {
        capture(
            "album-request-sheet-large-text",
            NeedlerDevice.Phone,
            notOwned().copy(requestSheet = RequestSheetState.forAlbum(SampleLibrary.blackClassicalMusic)),
            fontScale = 2f,
        )
    }

    // ---- the transport ------------------------------------------------------

    /**
     * The album you are listening to, with the control that stops it.
     *
     * The state these two PNGs cover is the one the device audit found: a record playing, its own
     * screen open, and a primary button reading "Play" that restarted it. There was no screenshot
     * of this before because every album fixture in this file was rendered with nothing loaded, so
     * the only state the images could show was the one state that was right.
     */
    @Test
    fun `the album you are listening to shows Pause`() {
        capture("album-playing", NeedlerDevice.Phone, playing())
    }

    @Test
    fun `the album you are listening to, paused, offers Resume`() {
        capture(
            "album-paused",
            NeedlerDevice.Phone,
            playing().copy(transport = AlbumTransport.RESUME),
        )
    }

    /**
     * Pause and "Shuffle again" at 200% text.
     *
     * Both words are longer than the ones they replace, and the action block is a `FlowRow` four
     * controls wide. REQUIREMENTS.md "Accessibility" asks for 200% without clipping, so the longer
     * labels are rendered at it rather than assumed to fit.
     */
    @Test
    fun `the transport controls at 200 percent text size`() {
        capture("album-playing-large-text", NeedlerDevice.Phone, playing(), fontScale = 2f)
    }

    @Test
    fun `the album you are listening to, on a tablet`() {
        val file = captureNeedlerScreen("album-playing", NeedlerDevice.Tablet) {
            TabletFrame { Screen(playing(), WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    // ---- the crate ----------------------------------------------------------

    /**
     * The action row with its fourth control: Play, Shuffle, Pull local, the crate menu.
     *
     * The placement decision in one image. The pack draws three controls here and no way to
     * queue anything; the fourth is an icon button rather than two more labelled buttons,
     * because five across would stop Play being visibly the primary action. Rendering it is
     * how that claim is checked rather than asserted.
     *
     * The menu is drawn **last**, which it was not: it used to sit third, between Shuffle and
     * Pull local, interrupting the run of named actions and leaving a real one out past the
     * dots. `AlbumActions` records why under "Where the overflow sits", and
     * `AlbumActionOrderTest` is what holds the order, because this image and an image of the
     * old order are both 780 by 1688 pixels and [assertRendered] cannot tell them apart.
     */
    @Test
    fun `the action row carries the crate menu`() {
        capture("album-crate-control", NeedlerDevice.Phone, owned())
    }

    /**
     * The same four controls at 200% text, where the row wraps.
     *
     * The wrap point is what moving the menu changes: this is the image that says where it
     * falls now. `album-crate-control-large-text-phone.png`, and `album-large-text-phone.png`
     * for the same row in the same state at the same size.
     */
    @Test
    fun `the action row with the crate menu at 200 percent text size`() {
        capture("album-crate-control-large-text", NeedlerDevice.Phone, owned(), fontScale = 2f)
    }

    /**
     * What a user sees after adding: the sentence, and the crate's own count and duration.
     *
     * Adding to a queue with no visible change is indistinguishable from a tap that did not
     * register, so this line is the whole feedback for the action and has a baseline of its
     * own.
     */
    @Test
    fun `the added-to-the-crate line, with the count and the duration`() {
        capture(
            "album-crate-added",
            NeedlerDevice.Phone,
            owned().copy(
                notice = AlbumNotice.AddedToCrate(trackCount = 8),
                crateTrackCount = 10,
                crateDurationMs = 2_120_000L,
            ),
        )
    }

    /** Play next, which says where the tracks went rather than only that they went. */
    @Test
    fun `the play-next line`() {
        capture(
            "album-crate-play-next",
            NeedlerDevice.Phone,
            owned().copy(
                notice = AlbumNotice.AddedToCrate(trackCount = 8, playNext = true),
                crateTrackCount = 10,
                crateDurationMs = 2_120_000L,
            ),
        )
    }

    /**
     * Adding with nothing loaded, which starts playback and says so.
     *
     * `PlaybackController.enqueue` never starts sound, so without this branch the add would
     * leave a loaded crate and silence - the exact "did my tap register" the notice exists to
     * answer.
     */
    @Test
    fun `the line for an add that had to start the crate`() {
        capture(
            "album-crate-started",
            NeedlerDevice.Phone,
            owned().copy(
                notice = AlbumNotice.AddedToCrate(trackCount = 8, started = true),
                crateTrackCount = 8,
                crateDurationMs = 1_681_000L,
            ),
        )
    }

    // ---- nothing the server sent is guaranteed to be there ------------------

    /**
     * An album with no title, no artist and no cover.
     *
     * Every fixture in this file supplied all three, which is exactly why a screen that
     * concatenated `album.title` into six accessibility labels shipped: nothing rendered
     * here could show it. `ReleaseItemDto.title` is nullable and the catalogue mapper maps
     * it with `.orEmpty()`, so this is a real state and not a hypothetical.
     */
    @Test
    fun `an album the catalogue named with nothing`() {
        capture("album-untitled", NeedlerDevice.Phone, untitled())
    }

    @Test
    fun `an album the catalogue named with nothing, at 200 percent text size`() {
        capture("album-untitled-large-text", NeedlerDevice.Phone, untitled(), fontScale = 2f)
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
            onPlayPause = {},
            onShuffle = {},
            onPlayTrack = {},
            onAddToCrate = {},
            onAddTrackToCrate = { _, _ -> },
            onPull = {},
            onCancelPull = {},
            onRetryPull = {},
            onDownloadToDevice = {},
            onRemoveFromDevice = {},
            onRetryTrack = {},
            onOpenArtist = {},
            onDismissNotice = {},
            onToggleFavourite = {},
            onToggleTrackFavourite = {},
            onMonitorArtistChange = {},
            onConfirmRequest = {},
            onDismissRequestSheet = {},
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

    /**
     * This album as the loaded crate, playing.
     *
     * [AlbumUiState.transport] rather than a playback fixture, because the composable is given the
     * resolved value: deciding that the crate is this record is `AlbumViewModel`'s job and is
     * tested there, against a real `PlayQueue`.
     */
    private fun playing(): AlbumUiState = owned().copy(transport = AlbumTransport.PAUSE)

    private fun starred(): AlbumUiState = owned().let { state ->
        state.copy(
            album = state.album?.copy(isFavourite = true),
            tracks = state.tracks.mapIndexed { index, row ->
                if (index == 0) row.copy(track = row.track.copy(isFavourite = true)) else row
            },
        )
    }

    private fun untitled(): AlbumUiState = AlbumUiState(
        loading = false,
        album = SampleLibrary.untitledAlbum,
        tracks = rows(SampleLibrary.untitledTracks, owned = false),
    )
}
