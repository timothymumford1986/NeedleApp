package app.needler.feature.library.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.library.AlbumScrollAnchor
import app.needler.feature.library.library.LibraryScreen
import app.needler.feature.library.library.LibrarySort
import app.needler.feature.library.library.LibraryTab
import app.needler.feature.library.library.LibraryUiState
import app.needler.feature.library.library.LibraryViewMode
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Library screen in every state it has.
 *
 * `application = Application::class` keeps Hilt out of it: these render the
 * stateless [LibraryScreen] from a literal [LibraryUiState], so nothing here
 * needs a dependency graph, a repository or a server.
 *
 * The data is the design pack's own — the same ten albums, the same
 * "176 albums · 42 GB · last scan 47m ago" — so each PNG can be put beside
 * `design/png/02-Library.png`, `13-LibraryList.png` and `09-TabletLibrary.png`
 * and compared line for line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class LibraryScreenshotTest {

    @Test
    fun `the album grid on a phone`() {
        capture("library-grid", NeedlerDevice.Phone, LOADED)
    }

    @Test
    fun `the list view with its format badges`() {
        capture("library-list", NeedlerDevice.Phone, LOADED.copy(viewMode = LibraryViewMode.LIST))
    }

    @Test
    fun `the artists tab`() {
        capture("library-artists", NeedlerDevice.Phone, LOADED.copy(tab = LibraryTab.ARTISTS))
    }

    /**
     * The Artists tab at 200% text, which is the variant the tab had none of.
     *
     * The index strip is a 27-chip control sized to single characters, down the right edge of a
     * 390dp phone, beside rows that are themselves growing: it is the one control on this screen
     * whose whole job is to stay narrow, and at twice the type size it either stays narrow or takes
     * the list's width. `ArtistIndexStripTest` reads the chips out of the semantics tree and can say
     * that each is reachable and labelled; it cannot say whether the strip and the rows still fit
     * beside one another, and nothing else in the set could either - `library-large-text-phone.png`
     * is the album grid.
     */
    @Test
    fun `the artists tab at 200 percent text size`() {
        capture(
            "library-artists-large-text",
            NeedlerDevice.Phone,
            LOADED.copy(tab = LibraryTab.ARTISTS),
            fontScale = 2f,
        )
    }

    /**
     * The Artists tab offline.
     *
     * Artists come from the mirror, so the list is intact and only the chrome changes - which is the
     * claim worth a baseline. An offline notice that pushed the index strip down the screen, or
     * reserved width from it, would show here and nowhere else.
     */
    @Test
    fun `the artists tab offline`() {
        capture(
            "library-artists-offline",
            NeedlerDevice.Phone,
            LOADED.copy(tab = LibraryTab.ARTISTS, offline = true),
        )
    }

    /**
     * The Artists tab of a library that has albums but no artist rows.
     *
     * Reachable, and not hypothetical: `ArtistDao` fills from a scan that writes album rows first,
     * so a part-finished first sync lands here. The strip has no letters to draw in this state, and
     * what it does instead - draw 27 dead chips, or nothing - is a decision no image recorded.
     */
    @Test
    fun `the artists tab with no artists`() {
        capture(
            "library-artists-empty",
            NeedlerDevice.Phone,
            LOADED.copy(tab = LibraryTab.ARTISTS, artists = emptyList()),
        )
    }

    @Test
    fun `the songs tab`() {
        capture(
            "library-songs",
            NeedlerDevice.Phone,
            LOADED.copy(tab = LibraryTab.SONGS, songs = SampleLibrary.submarineTracks),
        )
    }

    @Test
    fun `sorted by title`() {
        capture(
            "library-sorted-by-title",
            NeedlerDevice.Phone,
            LOADED.copy(
                sort = LibrarySort.TITLE,
                albums = SampleLibrary.albums.sortedBy { it.title },
            ),
        )
    }

    @Test
    fun `loading, before the mirror has answered`() {
        capture("library-loading", NeedlerDevice.Phone, LibraryUiState(loading = true))
    }

    @Test
    fun `an empty library asks for a sync`() {
        capture(
            "library-empty",
            NeedlerDevice.Phone,
            LibraryUiState(loading = false, stats = null),
        )
    }

    @Test
    fun `an empty library with no connection cannot sync and says so`() {
        capture(
            "library-empty-offline",
            NeedlerDevice.Phone,
            LibraryUiState(loading = false, offline = true),
        )
    }

    @Test
    fun `offline, with the library intact`() {
        capture("library-offline", NeedlerDevice.Phone, LOADED.copy(offline = true))
    }

    @Test
    fun `at 200 percent text size`() {
        capture("library-large-text", NeedlerDevice.Phone, LOADED, fontScale = 2f)
    }

    // ---- the crate ----------------------------------------------------------

    /**
     * The crate control on every album row, beside the Play that replaces the crate.
     *
     * The list view is where it is worth rendering: the row carries a format label, a Play and
     * now a third control, and whether those three still fit beside a two-line title is a
     * question only a render answers.
     */
    @Test
    fun `album rows offer the crate as well as Play`() {
        capture(
            "library-list-crate",
            NeedlerDevice.Phone,
            LOADED.copy(viewMode = LibraryViewMode.LIST),
        )
    }

    /** The same row at 200% text, which is where a third trailing control would clip. */
    @Test
    fun `album rows with the crate control at 200 percent text size`() {
        capture(
            "library-list-crate-large-text",
            NeedlerDevice.Phone,
            LOADED.copy(viewMode = LibraryViewMode.LIST),
            fontScale = 2f,
        )
    }

    /**
     * What the user sees after adding: the sentence and the crate's own two figures.
     *
     * Adding to a queue with no visible change is indistinguishable from a tap that did not
     * register, so this line is the whole feedback and is worth a baseline of its own.
     */
    @Test
    fun `the added-to-the-crate line, with the count and the duration`() {
        capture(
            "library-crate-added",
            NeedlerDevice.Phone,
            LOADED.copy(
                tab = LibraryTab.SONGS,
                songs = SampleLibrary.submarineTracks,
                notice = AlbumNotice.AddedToCrate(trackCount = 8),
                crateTrackCount = 10,
                crateDurationMs = 2_120_000L,
            ),
        )
    }

    /** The Songs tab, where every row now carries the crate control beside its duration. */
    @Test
    fun `song rows offer the crate`() {
        capture(
            "library-songs-crate",
            NeedlerDevice.Phone,
            LOADED.copy(
                tab = LibraryTab.SONGS,
                songs = SampleLibrary.submarineTracks,
                nowPlayingTrackKey = SampleLibrary.submarineTracks[1].key,
            ),
        )
    }

    // ---- the grid/list toggle keeps your place -------------------------------

    /**
     * The device audit's item 20b: scrolled into a 288-album library, "Switch to
     * list view" landed on the first album of the sort.
     *
     * The assertion is the point of these two. Rendering a scrolled library only
     * records what it looks like; comparing it with the same library unscrolled
     * is what fails if the layout ignores [AlbumScrollAnchor] — which is exactly
     * what it did before, and which no amount of looking at one PNG would catch.
     *
     * [DEEP] rather than [LOADED] because the pack's ten albums barely fill a
     * phone: a list of ten cannot be scrolled far enough for "back to the top"
     * to be distinguishable from "where I was".
     */
    @Test
    fun `the grid opens on the album the toggle left off at`() {
        val top: File = captureNeedlerScreen("library-deep-grid-top", NeedlerDevice.Phone) {
            Screen(DEEP, WindowWidthSizeClass.Compact)
        }
        val anchored: File =
            captureNeedlerScreen("library-deep-grid-anchored", NeedlerDevice.Phone) {
                Screen(DEEP, WindowWidthSizeClass.Compact, anchorDeepAt(ANCHOR_INDEX))
            }

        assertRendered(anchored, NeedlerDevice.Phone)
        assertDiffers(anchored, top, "the album grid ignored the scroll anchor")
    }

    @Test
    fun `the list opens on the same album the grid was showing`() {
        val asList: LibraryUiState = DEEP.copy(viewMode = LibraryViewMode.LIST)
        val top: File = captureNeedlerScreen("library-deep-list-top", NeedlerDevice.Phone) {
            Screen(asList, WindowWidthSizeClass.Compact)
        }
        val anchored: File =
            captureNeedlerScreen("library-deep-list-anchored", NeedlerDevice.Phone) {
                Screen(asList, WindowWidthSizeClass.Compact, anchorDeepAt(ANCHOR_INDEX))
            }

        assertRendered(anchored, NeedlerDevice.Phone)
        assertDiffers(anchored, top, "the album list ignored the scroll anchor")
    }

    // ---- tablet -------------------------------------------------------------

    @Test
    fun `four columns on a tablet, in the pack's content pane`() {
        val file = captureNeedlerScreen("library-grid", NeedlerDevice.Tablet) {
            TabletFrame { Screen(LOADED, WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `the list view on a tablet`() {
        val file = captureNeedlerScreen("library-list", NeedlerDevice.Tablet) {
            TabletFrame {
                Screen(LOADED.copy(viewMode = LibraryViewMode.LIST), WindowWidthSizeClass.Expanded)
            }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `offline on a tablet`() {
        val file = captureNeedlerScreen("library-offline", NeedlerDevice.Tablet) {
            TabletFrame { Screen(LOADED.copy(offline = true), WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    /**
     * The Artists tab in the tablet's 784dp content pane.
     *
     * The album grid gains a column at this width; a single-column list of names does not, so what
     * the extra 394dp is spent on is a layout decision the phone image cannot show. The index strip
     * is the part at risk: it is pinned to the right edge of whatever it is given, and a 784dp pane
     * can leave it a long way from the thumb that uses it.
     */
    @Test
    fun `the artists tab on a tablet`() {
        val file = captureNeedlerScreen("library-artists", NeedlerDevice.Tablet) {
            TabletFrame {
                Screen(LOADED.copy(tab = LibraryTab.ARTISTS), WindowWidthSizeClass.Expanded)
            }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: LibraryUiState,
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
    private fun Screen(
        state: LibraryUiState,
        widthSizeClass: WindowWidthSizeClass,
        scrollAnchor: AlbumScrollAnchor = AlbumScrollAnchor(),
    ) {
        LibraryScreen(
            state = state,
            widthSizeClass = widthSizeClass,
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
            scrollAnchor = scrollAnchor,
        )
    }

    /**
     * Fails when two renders came out byte-identical.
     *
     * Roborazzi writes the same bytes for the same bitmap, so equality here means
     * the two screens really did draw the same pixels — which, for a library
     * rendered at an anchor and the same library rendered at the top, means the
     * anchor was dropped on the floor.
     */
    private fun assertDiffers(anchored: File, top: File, message: String) {
        assertFalse(message, anchored.readBytes().contentEquals(top.readBytes()))
    }

    private companion object {
        /** The pack's own library, at the pack's own instant. */
        val LOADED = LibraryUiState(
            loading = false,
            stats = SampleLibrary.stats,
            albums = SampleLibrary.albums,
            artists = SampleLibrary.artists,
            renderedAt = SampleLibrary.renderedAt,
        )

        /**
         * The pack's ten records, six times over as sixty distinct records: enough for both
         * layouts to scroll properly on a 390dp phone.
         *
         * The device was carrying 288. Sixty is the smallest number at which "the
         * toggle kept my place" and "the toggle went back to the top" render as
         * visibly different images in both the grid and the list, which is the
         * only property these fixtures need.
         *
         * ## Why the titles change too
         *
         * The ids alone used to be suffixed, which left six records called `Submarine` carrying
         * six different release-group MBIDs - and `artworkPlaceholderTint` derives a tile's colour
         * from exactly that id, deliberately, so that one record is one colour everywhere. The
         * reader comparing goldens therefore saw Submarine dark green on `library-list-phone.png`
         * and brown-red on `library-deep-list-anchored-phone.png` and read it as an unstable
         * placeholder. The product code was right; the fixture was claiming six records were the
         * same record. Suffixing the title as well makes them what the ids already said they
         * were: sixty different albums, each with its own stable colour.
         */
        val DEEP_ALBUMS: List<Album> = (0 until 6).flatMap { pass ->
            SampleLibrary.albums.map { album ->
                album.copy(
                    releaseGroupMbid = ReleaseGroupMbid(
                        album.releaseGroupMbid.value + "-" + pass,
                    ),
                    title = if (pass == 0) album.title else album.title + " " + (pass + 1),
                )
            }
        }

        val DEEP: LibraryUiState = LOADED.copy(albums = DEEP_ALBUMS)

        /** Well clear of the first viewport in either layout, and of the last. */
        const val ANCHOR_INDEX: Int = 28

        fun anchorDeepAt(index: Int): AlbumScrollAnchor =
            AlbumScrollAnchor(DEEP_ALBUMS[index].releaseGroupMbid.value)
    }
}
