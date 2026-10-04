package app.needler.feature.library.album

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.screenshot.NeedlerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Stop, and the fact that it replaces Remove rather than sitting beside it.
 *
 * ## The defect
 *
 * A device audit started an album download, watched the badge read "Pulling to device, 31 percent,
 * playing now", and found the only action the screen offered beside it was "Device. Remove ... from
 * this device" - which deletes. There was no way to stop a download in progress and keep what had
 * arrived, and the one action on offer threw it away.
 *
 * REQUIREMENTS.md "The download in flight is a badge, not a banner": "stopping leaves what has landed
 * as a part-downloaded pin, which plays, while removing deletes the bytes and reports what it freed.
 * The album offers whichever one can still apply."
 *
 * ## Why these assertions and not a screenshot
 *
 * `album-pinned-downloading-phone.png` cannot see this. A render with the wrong button in the third
 * slot is a PNG of exactly the right size, and `assertRendered` asserts the size - so the whole
 * difference between a control that stops a download and one that deletes an album is invisible to
 * the screenshot suite unless a human opens the image. The content description is also the thing a
 * TalkBack user acts on, and it is the only part of this control that says which of the two it is.
 *
 * The negative assertions carry as much weight as the positive ones. Adding a Stop beside Remove
 * would satisfy every "Stop exists" test and would still leave a delete under the same badge, a
 * thumb's width from the stop - and it would put the screen at odds with
 * `AlbumState.Pinned.offeredActions`, which holds `CANCEL` while the download is in flight and
 * `REMOVE_FROM_DEVICE` once it rests, never both.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class AlbumStopControlTest {

    @get:Rule
    val compose = createComposeRule()

    private val stops: MutableList<Unit> = mutableListOf()
    private val removals: MutableList<Unit> = mutableListOf()

    // ---- while the bytes are still arriving ---------------------------------

    @Test
    fun `a download in flight offers Stop and not Remove`() {
        showAlbum(pinned(OfflineDownloadState.Downloading(tracksComplete = 4, tracksTotal = 12)))

        compose.onNodeWithContentDescription(STOP).assertExists()
        compose.onNodeWithContentDescription(REMOVE).assertDoesNotExist()
        compose.onNodeWithContentDescription(PULL_LOCAL).assertDoesNotExist()
    }

    /** A queued download has fetched nothing yet, and is just as stoppable. */
    @Test
    fun `a queued download offers Stop`() {
        showAlbum(pinned(OfflineDownloadState.Queued))

        compose.onNodeWithContentDescription(STOP).assertExists()
        compose.onNodeWithContentDescription(REMOVE).assertDoesNotExist()
    }

    /**
     * A download held for Wi-Fi offers Stop too.
     *
     * REQUIREMENTS.md "A hold is not progress, so it gets its own word" keeps this state out of the
     * device's colour, but it is still a download the user started and therefore still one they can
     * end - `isInFlight` includes it for exactly that reason.
     */
    @Test
    fun `a download held for Wi-Fi offers Stop`() {
        showAlbum(pinned(OfflineDownloadState.WaitingForUnmeteredNetwork))

        compose.onNodeWithContentDescription(STOP).assertExists()
        compose.onNodeWithContentDescription(REMOVE).assertDoesNotExist()
    }

    @Test
    fun `the tap stops the download and does not remove the album`() {
        showAlbum(pinned(OfflineDownloadState.Downloading(tracksComplete = 4, tracksTotal = 12)))

        compose.onNodeWithContentDescription(STOP).performClick()

        assertEquals(1, stops.size)
        assertEquals("the stop must not reach the removal", 0, removals.size)
    }

    /** REQUIREMENTS.md "Accessibility" puts the floor for any control at 48dp. */
    @Test
    fun `Stop is a legal target`() {
        showAlbum(pinned(OfflineDownloadState.Downloading(tracksComplete = 4, tracksTotal = 12)))

        compose.onNodeWithContentDescription(STOP).assertHeightIsAtLeast(48.dp)
    }

    // ---- once the download has stopped --------------------------------------

    /**
     * A stopped download rests at `Partial`, and that is where Stop has to go away.
     *
     * This is the state `PinRepository.stopPinnedDownload` writes, so this assertion is the UI half
     * of that choice: nothing further is coming, so the only thing left to do with the part of the
     * record that is here is keep it or delete it.
     */
    @Test
    fun `a stopped download offers Remove again, and no Stop`() {
        showAlbum(pinned(OfflineDownloadState.Partial(tracksComplete = 4, tracksTotal = 12)))

        compose.onNodeWithContentDescription(STOP).assertDoesNotExist()
        compose.onNodeWithContentDescription(REMOVE).assertExists()
    }

    @Test
    fun `a finished download offers Remove, and no Stop`() {
        showAlbum(pinned(OfflineDownloadState.Complete))

        compose.onNodeWithContentDescription(STOP).assertDoesNotExist()
        compose.onNodeWithContentDescription(REMOVE).assertExists()
    }

    @Test
    fun `an album on the server offers Pull to device and neither of the other two`() {
        showAlbum(AlbumState.Owned)

        compose.onNodeWithContentDescription(PULL_LOCAL).assertExists()
        compose.onNodeWithContentDescription(STOP).assertDoesNotExist()
        compose.onNodeWithContentDescription(REMOVE).assertDoesNotExist()
    }

    /**
     * With library download forbidden there is no Stop either.
     *
     * The control is inside the same `if (state.downloadAllowed)` the other two faces of this slot
     * are, because REQUIREMENTS.md requires *every* pin and download affordance hidden when an
     * administrator has turned library download off - and a Stop is an affordance over a download.
     */
    @Test
    fun `an administrator who forbids downloads hides Stop as well`() {
        showAlbum(
            pinned(OfflineDownloadState.Downloading(tracksComplete = 4, tracksTotal = 12)),
            downloadAllowed = false,
        )

        compose.onNodeWithContentDescription(STOP).assertDoesNotExist()
        compose.onNodeWithContentDescription(REMOVE).assertDoesNotExist()
    }

    // ---- plumbing -----------------------------------------------------------

    private fun pinned(download: OfflineDownloadState): AlbumState =
        AlbumState.Pinned(download = download)

    private fun showAlbum(state: AlbumState, downloadAllowed: Boolean = true) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                Box(
                    modifier = Modifier
                        .requiredWidth(PHONE_WIDTH)
                        .requiredHeight(PHONE_HEIGHT),
                ) {
                    AlbumScreen(
                        state = AlbumUiState(
                            loading = false,
                            album = SampleLibrary.submarine.copy(state = state),
                            tracks = SampleLibrary.submarineTracks.mapIndexed { index, track ->
                                AlbumTrack(
                                    position = index + 1,
                                    track = track,
                                    available = true,
                                )
                            },
                            downloadAllowed = downloadAllowed,
                        ),
                        widthSizeClass = WindowWidthSizeClass.Compact,
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
                        onRemoveFromDevice = { removals.add(Unit) },
                        onStopDownload = { stops.add(Unit) },
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
            }
        }
    }

    private companion object {
        /** `design/html/04-AlbumOwned.html`, the artboard the screenshots render at. */
        val PHONE_WIDTH = 390.dp
        val PHONE_HEIGHT = 844.dp

        const val STOP = "Stop downloading Submarine to this device"
        const val REMOVE = "Device. Remove Submarine from this device"
        const val PULL_LOCAL = "Pull to device. Download Submarine to this device"
    }
}
