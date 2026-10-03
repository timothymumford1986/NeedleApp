package app.needler.feature.library.artist

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The order of the artist screen's action row, measured.
 *
 * The same defect, the same fix and the same reasoning as `AlbumActionOrderTest`, one screen later.
 * The album row used to read Play, Shuffle, the crate menu, Pull local; this one read Play, Shuffle,
 * the crate menu, Pull all, Try again - an overflow third of up to five, with the one filled green
 * button on the screen pushed out past the dots. Both now put the overflow last.
 *
 * ## Why this file exists beside the screenshot test
 *
 * `artist-crate-control-phone.png` cannot catch a regression here. A render with the dots in the
 * middle is a PNG of exactly the right size, and `assertRendered` asserts the size, so the whole of
 * this change is invisible to the screenshot suite unless a human opens the image. These assertions
 * read laid-out bounds out of the semantics tree, which is both what the eye follows and what the
 * platform's accessibility delegate sorts by.
 *
 * ## Why bounds rather than a traversal index
 *
 * Nothing in the row sets a `traversalIndex`, deliberately: composition order is the drawn order and
 * the spoken order at once, so there is one fact rather than two that can drift. The thing to assert
 * is therefore the geometry, because that is what both consumers of the order actually use.
 * REQUIREMENTS.md "Accessibility" requires the spoken traversal to match the drawn sequence, and
 * [readingOrder] is the same top-to-bottom, left-to-right sort TalkBack applies.
 *
 * ## Why this row needs more cases than the album's
 *
 * It can hold five controls where the album holds four, and three of the five are conditional on
 * facts that vary independently - something to play, something to pull, and a catalogue lane that
 * either can be retried, cannot be reached at all, or is fine. So "last" has to be checked with the
 * row full, with it wrapped at 200% text, with nothing owned (where the crate control is absent
 * because there is nothing to queue), and in the name-derived case, which is the only state that
 * draws **Search the catalogue**.
 *
 * `application = Application::class` keeps Hilt out of it, as in the screenshot tests: the screen
 * renders from a literal [ArtistUiState] with no view model and no player.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class ArtistActionOrderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the overflow is last, after Pull all`() {
        show(loaded())

        assertEquals(
            listOf(PLAY, SHUFFLE, PULL_ALL, CRATE_MENU),
            readingOrder(listOf(CRATE_MENU, PULL_ALL, SHUFFLE, PLAY)),
        )
    }

    /**
     * The same order once the row has wrapped onto more than one line.
     *
     * The case an index-based fix would have got wrong, and the case where "last" has to still mean
     * last. REQUIREMENTS.md "Accessibility" asks for 200% without clipping; this asks the further
     * question of whether the order survived the wrap.
     */
    @Test
    fun `the overflow is still last at 200 percent text size`() {
        show(loaded(), fontScale = 2f)

        assertEquals(
            listOf(PLAY, SHUFFLE, PULL_ALL, CRATE_MENU),
            readingOrder(listOf(CRATE_MENU, PULL_ALL, SHUFFLE, PLAY)),
        )
    }

    /**
     * An artist nobody owns has no crate control at all.
     *
     * The one way this row differs from the album's. `AlbumActions` needs no equivalent guard
     * because an album always has something to queue; an artist with no owned records does not, and
     * a menu whose two items would both add nothing is worse than no menu. Pull all is then the only
     * control, and it is first because it is the only one.
     */
    @Test
    fun `an artist with nothing owned has no overflow to put last`() {
        show(catalogueOnly())

        compose.onNodeWithContentDescription(CRATE_MENU).assertDoesNotExist()
        compose.onNodeWithContentDescription(PLAY).assertDoesNotExist()
        assertEquals(listOf(PULL_ALL), readingOrder(listOf(PULL_ALL)))
    }

    /**
     * Try again sits before the overflow, as every named action does.
     *
     * This is the state the discography fix added the control to: owned records on screen and no
     * catalogue half, where the retry used to be hidden because `hasNothing` was false.
     */
    @Test
    fun `a retry sits before the overflow`() {
        show(loaded().copy(catalogueAlbums = emptyList(), discographySettled = true))

        compose.onNodeWithContentDescription(PULL_ALL).assertDoesNotExist()
        assertEquals(
            listOf(PLAY, SHUFFLE, TRY_AGAIN, CRATE_MENU),
            readingOrder(listOf(CRATE_MENU, TRY_AGAIN, SHUFFLE, PLAY)),
        )
    }

    /**
     * The name-derived artist's own control sits before the overflow too.
     *
     * It is mutually exclusive with Try again by construction - `canRetryDiscography` excludes
     * `artistNotInCatalogue` and `canFindInCatalogue` requires it - so this is the fifth label the
     * row can draw and never the fifth control on it.
     */
    @Test
    fun `search the catalogue sits before the overflow`() {
        show(nameDerived())

        compose.onNodeWithContentDescription(TRY_AGAIN).assertDoesNotExist()
        assertEquals(
            listOf(PLAY, SHUFFLE, FIND_IN_CATALOGUE, CRATE_MENU),
            readingOrder(listOf(CRATE_MENU, FIND_IN_CATALOGUE, SHUFFLE, PLAY)),
        )
    }

    /**
     * The move did not change what the menu says.
     *
     * `NeedlerCrateControl`'s description is the only way a TalkBack user learns that this is where
     * the crate lives, so it is asserted here rather than left to the control's own module: the edit
     * that reorders the row is exactly the edit that could rewrite it in passing.
     */
    @Test
    fun `the menu still says what it leads to`() {
        show(loaded())

        compose.onNodeWithContentDescription(CRATE_MENU).assertExists()
    }

    /**
     * Where the row wraps at 200% text, and that the named actions stay together.
     *
     * Measured: `Play | Shuffle | Pull all` on the first line, the overflow alone on the second.
     * That is the point of the move rather than a side effect of it. With the dots third the first
     * line was `Play | Shuffle | the dots` and **Pull all** - the one filled green button on the
     * screen - was what got pushed onto the second line, which is the album screen's old defect
     * exactly. The thing that should be pushed by a wrap is the control with no name on it.
     *
     * Asserted as "same line" / "different line" rather than as coordinates, so a change to a
     * button's padding does not fail a test about ordering.
     */
    @Test
    fun `at 200 percent the named actions share a line and the overflow does not`() {
        show(loaded(), fontScale = 2f)

        assertTrue("Shuffle left the first line", sameLine(PLAY, SHUFFLE))
        assertTrue("Pull all left the first line", sameLine(SHUFFLE, PULL_ALL))
        assertFalse("the overflow stayed on the first line", sameLine(PULL_ALL, CRATE_MENU))
    }

    // ---- plumbing -----------------------------------------------------------

    /**
     * Sorts [descriptions] the way a reader and the accessibility delegate both do: by line first,
     * then left to right within a line.
     *
     * Two controls are on the same line when their vertical extents overlap, which is the test that
     * survives a `FlowRow` giving a 44 dp icon button and a taller labelled button different heights
     * in the same row. Comparing the top edge alone would call them two lines.
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


    /** True when two controls' vertical extents overlap, i.e. a reader sees them on one line. */
    private fun sameLine(left: String, right: String): Boolean {
        val a: Rect = compose.onNodeWithContentDescription(left).fetchSemanticsNode().boundsInRoot
        val b: Rect = compose.onNodeWithContentDescription(right).fetchSemanticsNode().boundsInRoot
        return a.bottom > b.top && b.bottom > a.top
    }

    private fun show(state: ArtistUiState, fontScale: Float = 1f) {
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
                        ArtistScreen(
                            state = state,
                            widthSizeClass = WindowWidthSizeClass.Compact,
                            onBack = {},
                            onAlbumClick = {},
                            onPull = {},
                            onPullArtist = {},
                            onRetryDiscography = {},
                            onFindInCatalogue = {},
                            onOpenArtist = {},
                            onPlay = {},
                            onShuffle = {},
                            onPlayAlbum = {},
                            onAddToCrate = {},
                            onAddAlbumToCrate = { _, _ -> },
                            onToggleFavourite = {},
                            onMonitorArtistChange = {},
                            onConfirmRequest = {},
                            onDismissRequestSheet = {},
                        )
                    }
                }
            }
        }
    }

    /** Owned records to play, one un-owned record to pull: the fullest the row gets. */
    private fun loaded(): ArtistUiState = ArtistUiState(
        loading = false,
        artist = ARTIST,
        mbid = ARTIST.mbid,
        ownedAlbums = listOf(SampleLibrary.submarine),
        catalogueAlbums = listOf(UNOWNED),
        playableTracks = SampleLibrary.submarineTracks,
        discographySettled = true,
    )

    /** An artist reached from catalogue search: nothing owned, so nothing to queue. */
    private fun catalogueOnly(): ArtistUiState = ArtistUiState(
        loading = false,
        artist = null,
        mbid = ARTIST.mbid,
        knownName = ARTIST.name,
        catalogueAlbums = listOf(UNOWNED),
        discographySettled = true,
    )

    /** The reported case: owned records, and an id that can never reach the catalogue. */
    private fun nameDerived(): ArtistUiState = ArtistUiState(
        loading = false,
        artist = ARTIST,
        mbid = ARTIST.mbid,
        ownedAlbums = listOf(SampleLibrary.submarine),
        playableTracks = SampleLibrary.submarineTracks,
        artistNotInCatalogue = true,
        discographySettled = true,
    )

    private companion object {
        /** `design/html/04-AlbumOwned.html`, the same artboard the screenshots render at. */
        val PHONE_WIDTH = 390.dp
        val PHONE_HEIGHT = 844.dp

        val ARTIST = SampleLibrary.artists.first()

        val UNOWNED = SampleLibrary.album(
            slug = "superclean",
            title = "Superclean Vol. II",
            artistName = "The Marías",
            artistSlug = "marias",
            year = 2018,
            state = AlbumState.NotOwned,
            format = null,
        )

        const val NAME = "The Marías"

        const val PLAY = "Play everything by " + NAME + " in your library"
        const val SHUFFLE = "Shuffle everything by " + NAME + " in your library"
        const val PULL_ALL = "Pull all 1 albums by " + NAME + " that you do not own"
        const val TRY_AGAIN = "Look up " + NAME + " in the catalogue again"
        const val FIND_IN_CATALOGUE =
            "Search the MusicBrainz catalogue for artists named " + NAME
        const val CRATE_MENU =
            "Crate actions for everything by " + NAME + ". Add to the crate, or play next."
    }
}
