package app.needler.feature.library.album

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.AlbumState
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
 * The order of the album screen's action row, measured.
 *
 * ## Why this file exists beside the screenshot test
 *
 * The row used to read Play, Shuffle, the crate menu, Pull local — an overflow sitting between two
 * named actions, with the named one that downloads a whole record pushed out past the dots. It now
 * reads Play, Shuffle, Pull local, the crate menu. The reasoning is on `AlbumActions` under "Where
 * the overflow sits"; this is what stops the next edit undoing it.
 *
 * `album-crate-control-phone.png` cannot. A render with the dots in the middle is a PNG of exactly
 * the right size, and `assertRendered` asserts the size — so the whole of this change is invisible
 * to the screenshot suite unless a human opens the image. These assertions read the laid-out bounds
 * out of the semantics tree instead, which is both what the eye follows and what the platform's
 * accessibility delegate sorts by.
 *
 * ## Why bounds rather than a traversal index
 *
 * Nothing in the row sets a `traversalIndex`, deliberately: composition order is the drawn order
 * and the spoken order at once, so there is one fact rather than two that can drift. The thing to
 * assert is therefore the geometry, because that is what both consumers of the order actually use.
 * REQUIREMENTS.md "Accessibility" requires the spoken traversal to match the drawn sequence, and
 * `reading order` below is the same top-to-bottom, left-to-right sort TalkBack applies.
 *
 * ## The two text sizes
 *
 * At 100% the four controls are one line, so the test is a left-to-right comparison. At 200% the
 * `FlowRow` wraps and the controls land on more than one line, which is the case an index-based fix
 * would have got wrong and the case where "last" has to still mean last. REQUIREMENTS.md
 * "Accessibility" asks for 200% without clipping; this asks the further question of whether the
 * order survived the wrap.
 *
 * `application = Application::class` keeps Hilt out of it, as in the screenshot tests: the screen
 * renders from a literal [AlbumUiState] with no view model and no player.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class AlbumActionOrderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the overflow is last, after Pull local`() {
        showAlbum()

        assertEquals(
            listOf(PLAY, SHUFFLE, PULL_LOCAL, CRATE_MENU),
            readingOrder(listOf(CRATE_MENU, PULL_LOCAL, SHUFFLE, PLAY)),
        )
    }

    /** The same order once the row has wrapped onto more than one line. */
    @Test
    fun `the overflow is still last at 200 percent text size`() {
        showAlbum(fontScale = 2f)

        assertEquals(
            listOf(PLAY, SHUFFLE, PULL_LOCAL, CRATE_MENU),
            readingOrder(listOf(CRATE_MENU, PULL_LOCAL, SHUFFLE, PLAY)),
        )
    }

    /**
     * With downloads forbidden there is no Pull local, and the menu is still the last thing.
     *
     * The control is inside `if (state.downloadAllowed)`, so the order has to hold with it absent
     * as well as present — REQUIREMENTS.md requires every pin affordance hidden when the
     * administrator has turned library download off.
     */
    @Test
    fun `the overflow is last when the administrator forbids downloads`() {
        showAlbum(downloadAllowed = false)

        compose.onNodeWithContentDescription(PULL_LOCAL).assertDoesNotExist()
        assertEquals(
            listOf(PLAY, SHUFFLE, CRATE_MENU),
            readingOrder(listOf(CRATE_MENU, SHUFFLE, PLAY)),
        )
    }

    /**
     * The move did not change what the menu says.
     *
     * `NeedlerCrateControl`'s description is the only way a TalkBack user learns that this is where
     * the crate lives, so it is asserted here rather than left to the control's own module: the
     * edit that reorders the row is exactly the edit that could rewrite it in passing.
     */
    @Test
    fun `the menu still says what it leads to`() {
        showAlbum()

        compose.onNodeWithContentDescription(CRATE_MENU).assertExists()
    }

    // ---- plumbing -----------------------------------------------------------

    /**
     * Sorts [descriptions] the way a reader and the accessibility delegate both do: by line first,
     * then left to right within a line.
     *
     * Two controls are on the same line when their vertical extents overlap, which is the test that
     * survives a `FlowRow` giving a 44 dp icon button and a taller labelled button different
     * heights in the same row. Comparing the top edge alone would call them two lines.
     */
    private fun readingOrder(descriptions: List<String>): List<String> {
        val bounds: Map<String, Rect> = descriptions.associateWith { description ->
            compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        }
        return descriptions.sortedWith { left, right ->
            val a: Rect = bounds.getValue(left)
            val b: Rect = bounds.getValue(right)
            when {
                a.bottom <= b.top -> -1
                b.bottom <= a.top -> 1
                else -> a.left.compareTo(b.left)
            }
        }
    }

    private fun showAlbum(fontScale: Float = 1f, downloadAllowed: Boolean = true) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale),
                ) {
                    Box(
                        modifier = Modifier
                            .requiredWidth(PHONE_WIDTH)
                            .requiredHeight(PHONE_HEIGHT),
                    ) {
                        AlbumScreen(
                            state = owned(downloadAllowed),
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
                }
            }
        }
    }

    /**
     * The `PLAY` row, which is the only state that has four controls in it.
     *
     * `AlbumState.Owned` rather than `Pinned`, so the download control reads "Pull local" — the
     * named action the overflow used to sit in front of.
     */
    private fun owned(downloadAllowed: Boolean): AlbumUiState = AlbumUiState(
        loading = false,
        album = SampleLibrary.submarine.copy(state = AlbumState.Owned),
        tracks = SampleLibrary.submarineTracks.mapIndexed { index, track ->
            AlbumTrack(
                position = track.key.trackNumber.takeIf { it > 0 } ?: (index + 1),
                track = track,
                available = true,
            )
        },
        downloadAllowed = downloadAllowed,
    )

    private companion object {
        /** `design/html/04-AlbumOwned.html`, the same artboard the screenshots render at. */
        val PHONE_WIDTH = 390.dp
        val PHONE_HEIGHT = 844.dp

        const val PLAY = "Play Submarine"
        const val SHUFFLE = "Shuffle Submarine"
        const val PULL_LOCAL = "Pull local. Download Submarine to this device"
        const val CRATE_MENU = "Crate actions for Submarine. Add to the crate, or play next."
    }
}
