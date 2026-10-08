package app.needler.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.screenshot.NeedlerScreenshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The library's control row: segmented tabs, a sort control and a grid/list toggle.
 *
 * ## Why this file exists beside the screenshot test
 *
 * The row is the one place on this screen where three controls compete for a phone's width, and the
 * device reported them reading as three unrelated controls. Two of the three were private
 * composables beside a pack component; they are now `NeedlerToolbarPill` and its icon-only twin from
 * `:core:design`. A render records what that looks like. It cannot record what a screen reader
 * hears, nor what a finger can hit: `library-grid-phone.png` is a PNG of exactly the right size
 * whether or not the sort control still says "Sort: recently added", and whether its target is 48dp
 * or 36dp.
 *
 * These assertions read both out of the semantics tree, which is what TalkBack and the touch
 * dispatcher actually use.
 *
 * ## What is being protected
 *
 * REQUIREMENTS.md "Accessibility": "Every control carries a content description and transport
 * controls are at least 48 dp", and "Text must scale to 200% without clipping". The spoken strings
 * are the ones [LibrarySort] carries beside the visible labels, and the one-word visible label is
 * exactly why the spoken one has to be longer.
 *
 * `application = Application::class` keeps Hilt out of it, as the screenshot tests do: the screen
 * renders from a literal [LibraryUiState] with no view model and no repository.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class LibraryControlsTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The screen's state, held so one composition can be driven through several of them.
     *
     * `setContent` may be called once per test, and three of these tests need to see the row in
     * more than one state.
     */
    private val state: MutableState<LibraryUiState> = mutableStateOf(LOADED)

    /** Captured inside the composition, because the font scale is applied there. */
    private var density: Density = Density(density = 1f)

    // ---- the spoken labels --------------------------------------------------

    @Test
    fun `the sort control says what it sorts by, not just Recent`() {
        showLibrary()

        compose.onNodeWithContentDescription("Sort: recently added").assertExists()
    }

    /**
     * Every option's spoken label reaches the control, not only the default one.
     *
     * The pair lives on the enum so neither string can be edited without seeing the other; this is
     * what checks the control reads the second one rather than the visible label twice.
     */
    @Test
    fun `each sort option carries its own spoken label onto the control`() {
        showLibrary()

        LibrarySort.entries.forEach { sort ->
            show(LOADED.copy(sort = sort))
            compose.onNodeWithContentDescription("Sort: " + sort.spokenLabel).assertExists()
            assertTrue(
                "the spoken label for " + sort.name + " is only the visible one",
                sort.spokenLabel != sort.label,
            )
        }
    }

    /** The toggle names the layout a tap produces, which the pack's "Switch view" does not. */
    @Test
    fun `the toggle names the view it is about to switch to`() {
        showLibrary()
        compose.onNodeWithContentDescription("Switch to list view").assertExists()

        show(LOADED.copy(viewMode = LibraryViewMode.LIST))
        compose.onNodeWithContentDescription("Switch to grid view").assertExists()
    }

    /** The tab group keeps the name a screen reader announces before the three options. */
    @Test
    fun `the tabs are still a named group`() {
        showLibrary()

        compose.onNodeWithContentDescription(TABS).assertExists()
    }

    // ---- the menu the control opens -----------------------------------------

    /**
     * The menu offers four orders, each announced by what it sorts by.
     *
     * A sort control is judged by the list it produces, so the menu is asserted here rather than
     * left to the pill's own test. The order is the enum's, which is the order it is drawn in.
     */
    @Test
    fun `the menu offers every sort the mirror can actually produce`() {
        showLibrary()
        compose.onNodeWithContentDescription(SORT_RECENT).performClick()
        // The menu is a popup in a window of its own, composed on the frame after the tap.
        compose.waitForIdle()

        assertEquals(
            listOf("Recent", "Title", "Artist", "Starred"),
            LibrarySort.entries.map { it.label },
        )
        LibrarySort.entries.forEach { sort ->
            compose.onNodeWithContentDescription("Sort by " + sort.spokenLabel).assertExists()
        }
    }

    /**
     * "Played" is gone, and so is any option that would silently serve recently-added.
     *
     * It mapped to `FREQUENT`, which `DefaultLibraryRepository` falls through to recently-added for
     * both albums and tracks, so picking it produced the order already on screen under a control
     * that then read "Played". REQUIREMENTS.md: "'Played' cannot be honoured, because play counts
     * are computed server-side and the mirror holds no column reproducing them."
     */
    @Test
    fun `the menu does not offer a sort it cannot perform`() {
        showLibrary()
        compose.onNodeWithContentDescription(SORT_RECENT).performClick()
        // The menu is a popup in a window of its own, composed on the frame after the tap.
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Sort by most played").assertDoesNotExist()
        compose.onNodeWithText("Played").assertDoesNotExist()
    }

    /**
     * The chosen order is reported, not only tinted.
     *
     * The menu draws the current sort in the accent blue, and REQUIREMENTS.md "Accessibility" does
     * not let colour be the only carrier of a state.
     */
    @Test
    fun `the menu reports which order is in force`() {
        showLibrary()
        compose.onNodeWithContentDescription(SORT_RECENT).performClick()
        // The menu is a popup in a window of its own, composed on the frame after the tap.
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Sort by recently added").assertIsSelected()
        compose.onNodeWithContentDescription("Sort by title, A to Z").assertIsNotSelected()
    }

    // ---- 48dp targets -------------------------------------------------------

    /**
     * Both pills are drawn at the pack's 36dp and touched at 48dp.
     *
     * The toggle is the one that used to fail: it was drawn 44dp wide inside a box that raised only
     * the height, so it was a 44x48 target. `minimumInteractiveComponentSize` raises both.
     */
    @Test
    fun `the sort control and the toggle are both 48dp targets`() {
        showLibrary()

        assertAtLeast48dp(SORT_RECENT)
        assertAtLeast48dp(TOGGLE_TO_LIST)
    }

    /** Still true once the text has doubled, where the pills grow rather than the targets shrink. */
    @Test
    fun `the targets survive 200 percent text`() {
        showLibrary(fontScale = 2f)

        assertAtLeast48dp(SORT_RECENT)
        assertAtLeast48dp(TOGGLE_TO_LIST)
    }

    // ---- the row at phone width ---------------------------------------------

    /**
     * All three on one line at 390dp, in the pack's order.
     *
     * REQUIREMENTS.md "Tablet layout" describes the row as "segmented tabs, a sort control and a
     * grid/list toggle", in that order, and screen 02 draws it on a single line on a 390dp phone.
     * Two controls that overlap vertically are on the same line, which is the comparison that
     * survives them having different heights.
     */
    @Test
    fun `the three controls share one line at phone width`() {
        showLibrary()

        val tabs: Rect = boundsOf(TABS)
        val sort: Rect = boundsOf(SORT_RECENT)
        val toggle: Rect = boundsOf(TOGGLE_TO_LIST)

        assertTrue("the sort control wrapped off the tabs' line", overlapsVertically(tabs, sort))
        assertTrue("the toggle wrapped off the tabs' line", overlapsVertically(tabs, toggle))
        assertEquals(
            listOf(TABS, SORT_RECENT, TOGGLE_TO_LIST),
            listOf(TABS to tabs, SORT_RECENT to sort, TOGGLE_TO_LIST to toggle)
                .sortedBy { it.second.left }
                .map { it.first },
        )
    }

    /**
     * At 200% the row wraps rather than clipping, and the toggle stays on the tabs' line.
     *
     * Wrapping is the deliberate behaviour — a `FlowRow`, because three controls at double text do
     * not fit across 390dp — and the toggle is top-aligned so it lands beside the tabs instead of
     * floating in the gap between the two wrapped lines, level with neither.
     */
    @Test
    fun `at 200 percent text the sort control wraps but the toggle stays beside the tabs`() {
        showLibrary(fontScale = 2f)

        val tabs: Rect = boundsOf(TABS)
        val sort: Rect = boundsOf(SORT_RECENT)
        val toggle: Rect = boundsOf(TOGGLE_TO_LIST)

        assertTrue("the row did not wrap, so something is clipped", sort.top >= tabs.bottom)
        assertTrue("the toggle floated off the tabs' line", overlapsVertically(tabs, toggle))
    }

    /** The toggle is for albums only; artists and songs are always lists. */
    @Test
    fun `the toggle is absent on the artists tab`() {
        showLibrary()

        show(LOADED.copy(tab = LibraryTab.ARTISTS))
        compose.onNodeWithContentDescription(TOGGLE_TO_LIST).assertDoesNotExist()
    }

    /**
     * The sort control goes too, because on Artists it reported an order it could not apply.
     *
     * `LibraryViewModel` calls `LibraryRepository.observeArtists()` for that tab with no ordering -
     * the repository takes none, and `ArtistDao.observeArtists` is a single
     * `ORDER BY sort_name_normalised ASC` - so the list is alphabetical whatever the pill says.
     * With Recent selected the pill read "Recent" over a list sorted by name, which is the fault
     * [LibrarySort]'s own notes give as the reason "Played" was removed: "a sort that reports an
     * order it did not apply".
     *
     * The tab it is hidden on is also the tab that gains the alphabet jump, and the two are the same
     * decision: an index over a list whose order is fixed is meaningful, and over one the user can
     * re-sort it is not.
     */
    @Test
    fun `the sort control is absent on the artists tab, where it could not change the order`() {
        showLibrary()
        compose.onNodeWithContentDescription(SORT_RECENT).assertExists()

        show(LOADED.copy(tab = LibraryTab.ARTISTS))
        compose.onNodeWithContentDescription(SORT_RECENT).assertDoesNotExist()
    }

    /** It is back on Songs, which does read the sort - through `LibrarySort.trackKind`. */
    @Test
    fun `the sort control stays on the songs tab`() {
        showLibrary()

        show(LOADED.copy(tab = LibraryTab.SONGS))
        compose.onNodeWithContentDescription(SORT_RECENT).assertExists()
    }

    // ---- the browse destinations --------------------------------------------

    /**
     * Playlists and Genres are reachable, and announced as destinations.
     *
     * The entry point is the thing worth asserting: `NeedlerNavHost` registered both routes and
     * nothing navigated to either, so four finished screens shipped unreachable and every test
     * exercised the screens rather than the way in. The spoken label is the action rather than the
     * noun, so a TalkBack user hears that this opens something instead of hearing the same word
     * the tabs use.
     */
    @Test
    fun `the browse destinations are reachable from the library`() {
        showLibrary()

        compose.onNodeWithContentDescription(PLAYLISTS).assertExists()
        compose.onNodeWithContentDescription(GENRES).assertExists()
    }

    /** Both are 48dp targets, like every other control on this screen. */
    @Test
    fun `the browse destinations are 48dp targets`() {
        showLibrary()

        assertAtLeast48dp(PLAYLISTS)
        assertAtLeast48dp(GENRES)
    }

    // ---- nothing live over nothing ------------------------------------------

    /**
     * An empty tab keeps its tabs and loses the five controls that could not act on it.
     *
     * `screenshots/library-empty-phone.png` drew seven live controls over a library with nothing
     * in it. The tabs are the exception and the reason is `LibraryViewModel`: it fills only the
     * selected tab's list, so an empty Songs tab says nothing about whether there are albums, and
     * a screen that hid the tabs would strand the user on the empty one.
     */
    @Test
    fun `an empty tab drops the sort, the toggle and the browse destinations`() {
        showLibrary()

        show(LibraryUiState(loading = false))
        compose.onNodeWithContentDescription(TABS).assertExists()
        compose.onNodeWithContentDescription(SORT_RECENT).assertDoesNotExist()
        compose.onNodeWithContentDescription(TOGGLE_TO_LIST).assertDoesNotExist()
        compose.onNodeWithContentDescription(PLAYLISTS).assertDoesNotExist()
        compose.onNodeWithContentDescription(GENRES).assertDoesNotExist()
    }

    // ---- plumbing -----------------------------------------------------------

    private fun assertAtLeast48dp(description: String) {
        val touch: Rect = compose.onNodeWithContentDescription(description)
            .fetchSemanticsNode()
            .touchBoundsInRoot
        val width: Dp = with(density) { touch.width.toDp() }
        val height: Dp = with(density) { touch.height.toDp() }
        assertTrue(description + " is only " + width + " wide", width >= MIN_TARGET)
        assertTrue(description + " is only " + height + " tall", height >= MIN_TARGET)
    }

    private fun boundsOf(description: String): Rect =
        compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private fun overlapsVertically(a: Rect, b: Rect): Boolean = a.bottom > b.top && b.bottom > a.top

    private fun show(next: LibraryUiState) {
        state.value = next
        compose.waitForIdle()
    }

    private fun showLibrary(fontScale: Float = 1f) {
        compose.setContent {
            NeedlerTheme(reducedMotion = true) {
                val scaled = Density(LocalDensity.current.density, fontScale)
                density = scaled
                CompositionLocalProvider(LocalDensity provides scaled) {
                    Box(
                        modifier = Modifier
                            .requiredWidth(PHONE_WIDTH)
                            .requiredHeight(PHONE_HEIGHT),
                    ) {
                        LibraryScreen(
                            state = state.value,
                            widthSizeClass = WindowWidthSizeClass.Compact,
                            onTabSelect = {},
                            onSortSelect = {},
                            onViewModeToggle = {},
                            onSearchClick = {},
                            onAlbumClick = {},
                            onAlbumPlay = {},
                            onArtistClick = {},
                            onSongPlay = {},
                            onOpenPlaylists = {},
                            onOpenGenres = {},
                            onAlbumAddToCrate = { _, _ -> },
                            onSongAddToCrate = { _, _ -> },
                            onDismissNotice = {},
                            onSyncNow = {},
                        )
                    }
                }
            }
        }
    }

    private companion object {
        /** Screen 02's artboard. */
        val PHONE_WIDTH: Dp = 390.dp
        val PHONE_HEIGHT: Dp = 844.dp

        /** REQUIREMENTS.md "Accessibility", and `NeedlerSizes.minTouchTarget`. */
        val MIN_TARGET: Dp = 48.dp

        const val TABS: String = "Browse by"
        const val SORT_RECENT: String = "Sort: recently added"
        const val TOGGLE_TO_LIST: String = "Switch to list view"

        const val PLAYLISTS: String = "Open playlists"
        const val GENRES: String = "Open genres"

        /**
         * A library with something in every tab.
         *
         * The songs were missing, which mattered once the row started hiding the controls that
         * cannot act on an empty tab: "the sort control stays on the songs tab" was passing
         * against a Songs tab that had no songs in it, so it was asserting the control survives a
         * state the control is now deliberately absent from.
         */
        val LOADED = LibraryUiState(
            loading = false,
            stats = SampleLibrary.stats,
            albums = SampleLibrary.albums,
            artists = SampleLibrary.artists,
            songs = SampleLibrary.submarineTracks,
            renderedAt = SampleLibrary.renderedAt,
        )
    }
}
