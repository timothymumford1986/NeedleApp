package app.needler.feature.library.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
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
 * The alphabet jump on the Artists tab, as a finger and a screen reader meet it.
 *
 * The device audit: "The Artists tab lists every artist alphabetically with no index, no fast-scroll
 * and no section headers. With a library of 289 albums, reaching D means scrolling the whole way."
 * REQUIREMENTS.md "Library browse" had asked for it all along - Artists is `getArtists`,
 * "Alphabetical, with index jump" - and the mirror was built for it: `ArtistDao.observeArtists`
 * documents the letter as "derivable from `sort_name_normalised`" and `SortKeys.indexLetter` was
 * written and never called.
 *
 * ## Why these assertions and not a screenshot
 *
 * `library-artists-phone.png` is a PNG of exactly the right size whether or not tapping a letter
 * moves the list, whether the empty letters are reachable, and whether a one-character chip tells a
 * screen reader anything at all. Each of those is read out of the semantics tree here, which is what
 * TalkBack and the touch dispatcher actually use. `ArtistIndexTest` asserts the bucketing decision;
 * this asserts that the control reads it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [NeedlerScreenshots.SDK],
    application = Application::class,
    // The pack's phone artboard as the *device*, not as a box drawn on a smaller one. A
    // `requiredWidth` inside the test runtime's default 320dp screen overflows it and is
    // centred, so the strip's far chips are clipped out of the root and report zero touch
    // bounds - which is a property of the harness, not of the control.
    qualifiers = "w390dp-h844dp-xhdpi",
)
class ArtistIndexStripTest {

    @get:Rule
    val compose = createComposeRule()

    private val state: MutableState<LibraryUiState> = mutableStateOf(artistsTab(LIBRARY))

    private var density: Density = Density(density = 1f)

    // ---- the jump -----------------------------------------------------------

    /**
     * Item 2 of the audit, in one assertion: a letter deep in the alphabet is reachable by one tap.
     *
     * "Tanya Tagaq" is the last of the fixture and is nowhere near a phone's first screenful, so the
     * `assertIsNotDisplayed` before the tap is what makes the one after it mean something.
     */
    @Test
    fun `tapping a letter scrolls to the first artist in that bucket`() {
        showLibrary()

        assertOffScreen("Tanya Tagaq")

        tap("Jump to T")

        row("Tanya Tagaq").assertIsDisplayed()
    }

    /** The *first* artist in the bucket, not merely one of them. */
    @Test
    fun `the jump lands on the first of the letter, not the middle`() {
        showLibrary()

        tap("Jump to B")

        row("Beach House").assertIsDisplayed()
        // "Alvvays" is the row immediately before it, so a jump that landed anywhere later in B -
        // or that merely brought some B row into view - would leave this one on screen.
        assertOffScreen("Alvvays")
    }

    /** And it goes back up again, so the strip is not a one-way trip to the bottom. */
    @Test
    fun `a jump back up the alphabet works as well`() {
        showLibrary()

        tap("Jump to T")
        tap("Jump to A")

        row("Above & Beyond").assertIsDisplayed()
    }

    // ---- a letter with nothing under it -------------------------------------

    /**
     * An empty letter is drawn, focusable, disabled, and says why.
     *
     * The fixture deliberately has no Q and no X. Disabled rather than absent, because a letter
     * missing from the middle of an alphabet shifts every letter after it - so the strip a user has
     * learned rearranges itself the first time a sync adds the library's first Q. [ArtistIndex]
     * carries that reasoning.
     */
    @Test
    fun `an empty letter is present, disabled, and says it is empty`() {
        showLibrary()

        letter("No artists under Q").assertIsNotEnabled()
        letter("No artists under X").assertIsNotEnabled()
        letter("Jump to A").assertIsEnabled()
    }

    /** Tapping it does nothing - in particular it does not scroll somewhere arbitrary. */
    @Test
    fun `tapping an empty letter leaves the list where it was`() {
        showLibrary()

        tap("Jump to T")
        tap("No artists under X")

        row("Tanya Tagaq").assertIsDisplayed()
    }

    // ---- names that are not letters -----------------------------------------

    /**
     * Item 2's third question: digits, punctuation and other scripts have a defined home.
     *
     * `#` leads the strip because the list leads with those names - the mirror orders on
     * `sort_name_normalised`, an ordinary string comparison, so "!!!" sorts before "a". The chip is
     * left of A for that reason and not by convention.
     */
    @Test
    fun `the hash chip leads the strip and reaches the names that are not letters`() {
        showLibrary()

        tap("Jump to T")
        assertOffScreen("!!!")

        tap(OTHER_CHIP)

        row("!!!").assertIsDisplayed()
        assertTrue(
            "the hash chip is not left of A",
            boundsOf(OTHER_CHIP).left < boundsOf("Jump to A").left,
        )
    }

    /**
     * A non-Latin name is findable: it is in `#`, and it is in the list in a place the strip reaches.
     *
     * This fixture holds no leading-punctuation artist, so `#` resolves to the non-Latin group
     * directly. [ArtistIndex] records what happens in a library holding both - one `#` bucket spanning
     * two ends of the list - and why a chip per script was rejected.
     */
    @Test
    fun `a non-Latin name is reachable from the hash chip`() {
        showLibrary(LATIN_AND_OTHER)

        tap(OTHER_CHIP)

        row("東京事変").assertIsDisplayed()
    }

    // ---- the strip as a control ----------------------------------------------

    /** A strip of single characters needs a name, as the three tabs beside it have one. */
    @Test
    fun `the strip is a named group`() {
        showLibrary()

        strip().assertExists()
    }

    /** Every chip is a 48dp target, drawn at the pack's 36dp. */
    @Test
    fun `the letters are 48dp targets`() {
        showLibrary()

        assertAtLeast48dp("Jump to A")
        assertAtLeast48dp("No artists under Q")
    }

    /**
     * Still true at 200% text, which is the size a vertical A-Z rail could not have survived.
     *
     * REQUIREMENTS.md "Accessibility": "Text must scale to 200% without clipping." The strip keeps
     * its targets by growing sideways and scrolling, which is why it is horizontal; twenty-seven
     * targets down an 844dp phone is 31dp each before the text doubles. The far chips move off
     * screen, so Z is scrolled to rather than tapped where it was.
     */
    @Test
    fun `the targets survive 200 percent text`() {
        showLibrary(fontScale = 2f)

        assertAtLeast48dp("Jump to A")
        // The far end of the alphabet, which this fixture has nobody under - so it is the disabled
        // chip's target as well as the furthest one, and both have to hold at double text.
        assertAtLeast48dp("No artists under Z")
    }

    // ---- the tab it belongs to ----------------------------------------------

    /** The strip is for Artists. The Albums tab has a sort control instead and no alphabet. */
    @Test
    fun `the strip is absent on the other tabs`() {
        showLibrary()
        strip().assertExists()

        show(
            artistsTab(LIBRARY).copy(tab = LibraryTab.ALBUMS, albums = SampleLibrary.albums),
        )
        strip().assertDoesNotExist()
    }

    /** And an empty library draws its empty state rather than an alphabet of dead letters. */
    @Test
    fun `an empty library draws no strip`() {
        showLibrary()

        show(artistsTab(emptyList()))
        strip().assertDoesNotExist()
    }

    // ---- plumbing -----------------------------------------------------------

    private fun row(name: String) = compose.onNodeWithContentDescription(name + ",", substring = true)

    /**
     * Asserts a row is not on screen, whether or not the lazy list has composed it.
     *
     * `assertIsNotDisplayed` needs a node to exist, and a `LazyColumn` has not composed a row
     * twenty places below the viewport - so the plain form of this assertion fails with "could not
     * find any" on exactly the cases it is meant to confirm, and would still "pass" as a failure if
     * the jump stopped working. Absent and present-but-off-screen are both "not on screen", and the
     * distinction between them is the lazy list's business rather than this test's.
     */
    private fun assertOffScreen(name: String) {
        val found: Int = compose
            .onAllNodesWithContentDescription(name + ",", substring = true)
            .fetchSemanticsNodes()
            .size
        if (found == 0) return
        row(name).assertIsNotDisplayed()
    }

    private fun letter(spoken: String) = compose.onNodeWithContentDescription(spoken)

    private fun strip() = compose.onNodeWithContentDescription(ARTIST_INDEX)

    /**
     * Brings a chip into the strip's viewport, then taps it.
     *
     * Twenty-seven 48dp targets is 1,350dp of strip and a phone is 390dp wide, so about eight chips
     * are on screen at once and the rest are reached by scrolling sideways. That is inherent to the
     * form rather than a defect in it - the targets cannot be made smaller without failing
     * REQUIREMENTS.md "Accessibility" - and it is one swipe against the two hundred rows the strip
     * exists to skip. TalkBack needs no swipe at all: focusing a chip scrolls the strip to it, which
     * is what this helper stands in for.
     */
    private fun tap(spoken: String) {
        strip().performScrollToNode(hasContentDescription(spoken))
        compose.waitForIdle()
        letter(spoken).performClick()
        compose.waitForIdle()
    }

    private fun boundsOf(spoken: String): Rect =
        letter(spoken).fetchSemanticsNode().boundsInRoot

    private fun assertAtLeast48dp(spoken: String) {
        strip().performScrollToNode(hasContentDescription(spoken))
        compose.waitForIdle()
        val touch: Rect = letter(spoken).fetchSemanticsNode().touchBoundsInRoot
        val width: Dp = with(density) { touch.width.toDp() }
        val height: Dp = with(density) { touch.height.toDp() }
        assertTrue(spoken + " is only " + width + " wide", width >= MIN_TARGET)
        assertTrue(spoken + " is only " + height + " tall", height >= MIN_TARGET)
    }

    private fun show(next: LibraryUiState) {
        state.value = next
        compose.waitForIdle()
    }

    private fun showLibrary(artists: List<Artist> = LIBRARY, fontScale: Float = 1f) {
        state.value = artistsTab(artists)
        compose.setContent {
            // reducedMotion, so the jump is a `scrollToItem` rather than an animation the test would
            // have to wait out. REQUIREMENTS.md "Accessibility" asks the screen to honour the setting;
            // this is also the only way these assertions are not flaky.
            NeedlerTheme(reducedMotion = true) {
                val scaled = Density(LocalDensity.current.density, fontScale)
                density = scaled
                CompositionLocalProvider(LocalDensity provides scaled) {
                    Box(modifier = Modifier.fillMaxSize()) {
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
        val MIN_TARGET: Dp = 48.dp

        /** The strip's own name, as `LibraryScreen` sets it. */
        const val ARTIST_INDEX: String = "Jump to a letter"

        const val OTHER_CHIP: String =
            "Jump to artists whose name does not start with a letter"

        fun artist(name: String): Artist =
            Artist(mbid = ArtistMbid("ar-" + name.hashCode()), name = name, ownedAlbumCount = 2)

        fun artistsTab(artists: List<Artist>): LibraryUiState = LibraryUiState(
            tab = LibraryTab.ARTISTS,
            loading = false,
            artists = artists,
        )

        /**
         * The reference library's own shape: alphabetical, starting with punctuation, with holes.
         *
         * In the order `sort_name_normalised ASC` produces, because the screen is handed the list the
         * mirror produced and the strip's indices are positions in it. No Q and no X, which is what the
         * empty-letter assertions read, and long enough that the end of the alphabet is off a phone.
         */
        val LIBRARY: List<Artist> = listOf(
            "!!!",
            "Above & Beyond",
            "AFI",
            "Alan Walker",
            "Alice Keath",
            "Alkaline Trio",
            "Alvvays",
            "Beach House",
            "Big Thief",
            "Cleo Sol",
            "Daft Punk",
            "Everything Everything",
            "Flyte",
            "Grimes",
            "Hot Chip",
            "Interpol",
            "Jamie xx",
            "Khruangbin",
            "LCD Soundsystem",
            "Mk.gee",
            "NIKI",
            "Ólafur Arnalds",
            "Paul Kossoff",
            "Radiohead",
            "Sufjan Stevens",
            "Tanya Tagaq",
        ).map(::artist)

        /** A library with no leading-punctuation names, so `#` resolves to the non-Latin tail. */
        val LATIN_AND_OTHER: List<Artist> = LIBRARY.drop(1) + artist("東京事変")
    }
}
