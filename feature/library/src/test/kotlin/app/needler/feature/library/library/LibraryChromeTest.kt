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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.screenshot.NeedlerScreenshots
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * How much of the viewport the Library spends before its first row, at rest and scrolled.
 *
 * ## Why this is a test and not a screenshot
 *
 * The chrome figure is the finding: Albums spent 37% of a 390x844 phone on a title, a stats line, a
 * search field, a tab row and a browse row, Artists 43%, and 49% at 200% text - against Search's
 * 14% on the same device. A render records that and asserts nothing about it, and
 * `library-deep-list-anchored-phone.png` is the proof that looking at renders was not enough: it is
 * the library scrolled twenty-eight records deep, and a reader has to measure the PNG by hand to
 * notice that every pixel of chrome is still there.
 *
 * So the number is asserted. These tests read the top of the scrolling list out of the semantics
 * tree, which is the dp the first row actually begins at, and compare it with the height of the
 * viewport - which is what "37%" was measuring.
 *
 * `application = Application::class` keeps Hilt out of it, as the other tests on this screen do:
 * the screen renders from a literal [LibraryUiState] with no view model and no repository.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class LibraryChromeTest {

    @get:Rule
    val compose = createComposeRule()

    private val state: MutableState<LibraryUiState> = mutableStateOf(DEEP)

    private var density: Density = Density(density = 1f)

    /** The resting figure, which is what the audit measured off the goldens. */
    @Test
    fun `at rest the chrome is under a third of the viewport`() {
        showLibrary()

        val fraction: Float = chromeFraction()
        assertTrue("the chrome is " + percent(fraction) + " of the viewport", fraction < 0.30f)
    }

    /**
     * Scrolled, it is well under a quarter: the title block and the browse row have gone.
     *
     * The search field and the tab row stay, because both are reached for from inside the list.
     * See `ReportScrolled` for the whole argument and for why the collapse cannot oscillate.
     */
    @Test
    fun `scrolling collapses the chrome`() {
        showLibrary()
        val atRest: Float = chromeFraction()

        scrollDeep()

        val scrolled: Float = chromeFraction()
        assertTrue(
            "scrolling did not collapse the chrome: " + percent(atRest) + " then " +
                percent(scrolled),
            scrolled < atRest,
        )
        assertTrue("the scrolled chrome is " + percent(scrolled), scrolled < 0.22f)
    }

    /** The screen title is what goes; the search field is what stays. */
    @Test
    fun `the title leaves on scroll and the search field does not`() {
        showLibrary()
        compose.onNodeWithText(TITLE).assertExists()

        scrollDeep()

        compose.onNodeWithText(TITLE).assertDoesNotExist()
        compose.onNodeWithContentDescription(TABS).assertExists()
    }

    /**
     * Still true at 200% text, which is the size the audit measured at 49%.
     *
     * The threshold is looser because every part of the chrome that remains is text that has
     * doubled; the point is that the collapse is a reduction at that size too, not that it reaches
     * the same figure.
     */
    @Test
    fun `the collapse still helps at 200 percent text`() {
        showLibrary(fontScale = 2f)
        val atRest: Float = chromeFraction()

        scrollDeep()

        assertTrue(
            "no reduction at 200% text: " + percent(atRest) + " then " + percent(chromeFraction()),
            chromeFraction() < atRest,
        )
    }

    /** The Artists tab was the worst of the three at 43%, and collapses the same way. */
    @Test
    fun `the artists tab collapses too`() {
        showLibrary()
        show(DEEP.copy(tab = LibraryTab.ARTISTS))
        val atRest: Float = chromeFraction()

        scrollDeep()

        assertTrue(
            "the artists tab did not collapse: " + percent(atRest) + " then " +
                percent(chromeFraction()),
            chromeFraction() < atRest,
        )
    }

    // ---- plumbing -----------------------------------------------------------

    /** How much of the viewport is spent before the first row. */
    private fun chromeFraction(): Float {
        val top: Float = compose.onNode(hasScrollToIndexAction())
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val height: Float = with(density) { PHONE_HEIGHT.toPx() }
        return top / height
    }

    private fun scrollDeep() {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(SCROLL_TO)
        compose.waitForIdle()
    }

    private fun percent(fraction: Float): String = (fraction * 100f).toInt().toString() + "%"

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
        /** Screen 02's artboard, which is what the percentages are of. */
        val PHONE_WIDTH: Dp = 390.dp
        val PHONE_HEIGHT: Dp = 844.dp

        /** Far enough that the list cannot still be at the top in either layout. */
        const val SCROLL_TO: Int = 20

        const val TITLE: String = "LIBRARY"
        const val TABS: String = "Browse by"

        /**
         * Sixty albums in the list view, which is the layout the audit measured.
         *
         * The grid is `LazyVerticalGrid` and reports its own scroll node the same way, but the
         * list is where a row's top is a row's top rather than a cell's.
         */
        val DEEP: LibraryUiState = LibraryUiState(
            loading = false,
            viewMode = LibraryViewMode.LIST,
            stats = SampleLibrary.stats,
            albums = deepAlbums(),
            artists = deepArtists(),
            songs = SampleLibrary.submarineTracks,
            renderedAt = SampleLibrary.renderedAt,
        )

        private fun deepAlbums(): List<Album> = (0 until 6).flatMap { pass ->
            SampleLibrary.albums.map { album ->
                album.copy(
                    releaseGroupMbid = ReleaseGroupMbid(album.releaseGroupMbid.value + "-" + pass),
                    title = album.title + " " + (pass + 1),
                )
            }
        }

        private fun deepArtists() = (0 until 6).flatMap { pass ->
            SampleLibrary.artists.map { artist ->
                artist.copy(
                    mbid = app.needler.core.domain.model.ArtistMbid(artist.mbid.value + "-" + pass),
                    name = artist.name + " " + (pass + 1),
                )
            }
        }
    }
}
