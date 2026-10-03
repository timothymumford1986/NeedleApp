package app.needler.feature.search.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.RequestStatus
import app.needler.core.domain.model.UnifiedSearchResults
import app.needler.feature.search.SampleSearch
import app.needler.feature.search.search.SearchNotice
import app.needler.feature.search.search.SearchScreen
import app.needler.feature.search.search.SearchUiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Search screen in every state it has.
 *
 * `application = Application::class` keeps Hilt out of it: these render the
 * stateless [SearchScreen] from a literal [SearchUiState], so nothing here needs
 * a dependency graph, a repository or a server. That is the whole reason the
 * route is split from the screen — several of the states below (an expired
 * session, a degraded MusicBrainz) are ones a real device would have to be
 * broken in a specific way to reach.
 *
 * The data is the design pack's own — the same "khruangbin" result on the phone
 * and the same "yussef dayes" result on the tablet — so each PNG can be put
 * beside `design/png/03-Search.png` and `10-TabletSearch.png` and compared line
 * for line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class SearchScreenshotTest {

    // ---- phone --------------------------------------------------------------

    @Test
    fun `the pack's own results on a phone`() {
        capture("search-results", NeedlerDevice.Phone, RESULTS)
    }

    /**
     * The order the device report said was backwards: owned artist first, the
     * library's albums and songs above everything un-owned, and the un-owned tail
     * capped with a row that reveals the rest.
     */
    @Test
    fun `owned results lead, with both blocks capped`() {
        capture(
            "search-owned-first",
            NeedlerDevice.Phone,
            SearchUiState(query = "wonder", results = SampleSearch.wonderResults),
        )
    }

    @Test
    fun `nothing typed yet, with a search history`() {
        capture(
            "search-recent",
            NeedlerDevice.Phone,
            SearchUiState(recentQueries = SampleSearch.recentQueries),
        )
    }

    @Test
    fun `nothing typed yet, on a first run with no history`() {
        capture("search-empty", NeedlerDevice.Phone, SearchUiState())
    }

    @Test
    fun `typed, with neither lane back yet`() {
        capture(
            "search-loading",
            NeedlerDevice.Phone,
            SearchUiState(
                query = "khruangbin",
                results = UnifiedSearchResults(
                    query = "khruangbin",
                    catalogue = CatalogueLaneState.Loading,
                ),
            ),
        )
    }

    /**
     * The library lane has answered and the catalogue lane has not.
     *
     * This is the state REQUIREMENTS.md rule 1 and rule 2 produce together
     * between the first keystroke and the 300 ms mark, and it is the one that
     * proves the two lanes are independent: results are on screen while the
     * slower half is still in flight.
     */
    @Test
    fun `library results while the catalogue is still searching`() {
        capture(
            "search-catalogue-loading",
            NeedlerDevice.Phone,
            RESULTS.copy(
                results = SampleSearch.khruangbinResults.copy(
                    // Only the two the mirror knows; the catalogue has not
                    // contributed anything yet.
                    albums = listOf(SampleSearch.mordechai, SampleSearch.flyte),
                    catalogue = CatalogueLaneState.Loading,
                ),
            ),
        )
    }

    /** Rule 4, offline: library results only, and a line saying why. */
    @Test
    fun `offline, with the library half intact`() {
        capture(
            "search-offline",
            NeedlerDevice.Phone,
            RESULTS.copy(
                offline = true,
                results = SampleSearch.khruangbinResults.copy(
                    albums = listOf(SampleSearch.mordechai, SampleSearch.flyte),
                    catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
                ),
            ),
        )
    }

    /**
     * Offline with the un-owned half of the mirror in it, which is the screen
     * the device actually drew and the one no image covered.
     *
     * Every other offline capture here holds owned albums only, so the "Albums
     * to pull" block — and the caption over it — was never rendered offline. On
     * the device that caption read "in your library" over three rows subtitled
     * "Not in your library yet". It now names the source the rows came from.
     */
    @Test
    fun `offline, with cached catalogue rows under the library half`() {
        capture(
            "search-offline-cached-catalogue",
            NeedlerDevice.Phone,
            RESULTS.copy(
                offline = true,
                results = SampleSearch.khruangbinResults.copy(
                    catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
                ),
            ),
        )
    }

    /**
     * The pull sheet with no connection: the button says which outcome the tap
     * buys, and the caption says what becomes of the request.
     *
     * REQUIREMENTS.md "Failure handling" makes a queued pull a supported
     * outcome, not a failure, which is why this image is the ordinary sheet with
     * two lines changed rather than a warning.
     */
    @Test
    fun `the request sheet with no connection`() {
        capture(
            "search-pull-sheet-offline",
            NeedlerDevice.Phone,
            RESULTS.copy(
                offline = true,
                results = SampleSearch.khruangbinResults.copy(
                    catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
                ),
                pullSheetAlbum = SampleSearch.buzz,
            ),
            autoFocus = false,
        )
    }

    /** Rule 4, stale session: the same shape, a different way out. */
    @Test
    fun `an expired session leaves the library searchable`() {
        capture(
            "search-session-expired",
            NeedlerDevice.Phone,
            RESULTS.copy(
                results = SampleSearch.khruangbinResults.copy(
                    albums = listOf(SampleSearch.mordechai, SampleSearch.flyte),
                    catalogue = CatalogueLaneState.Unavailable(NeedlerError.SessionExpired),
                ),
            ),
        )
    }

    /** `service_status`: a quiet inline note, never a dialog. */
    @Test
    fun `a degraded MusicBrainz is a note, not an error`() {
        capture(
            "search-degraded",
            NeedlerDevice.Phone,
            RESULTS.copy(
                results = SampleSearch.khruangbinResults.copy(
                    catalogue = CatalogueLaneState.Ready(SampleSearch.degraded),
                ),
            ),
        )
    }

    @Test
    fun `both lanes answered and neither had anything`() {
        capture(
            "search-no-results",
            NeedlerDevice.Phone,
            SearchUiState(
                query = "khruangbim",
                results = UnifiedSearchResults(
                    query = "khruangbim",
                    catalogue = CatalogueLaneState.Ready(),
                ),
                suggestions = SampleSearch.suggestions,
            ),
        )
    }

    /**
     * The state the device was in and the pack had no picture of: nothing
     * matched **and** the catalogue was never asked.
     *
     * Every other offline image here is a results screen, which is why the
     * sentence under "Nothing found" could claim a MusicBrainz lookup for a
     * build's whole life without a baseline disagreeing. `offline` is left false
     * on purpose — the device had connectivity reading online while the lane had
     * already recorded that it never ran, and the image has to prove the copy
     * reads the lane.
     */
    @Test
    fun `nothing matched and the catalogue was never asked`() {
        capture(
            "search-no-results-offline",
            NeedlerDevice.Phone,
            SearchUiState(
                query = "beastiedido",
                results = UnifiedSearchResults(
                    query = "beastiedido",
                    catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
                ),
            ),
        )
    }

    /**
     * The request sheet a **Pull** opens, with the `monitor_artist` toggle
     * REQUIREMENTS.md "Placing a request" asks for. The sheet is `:core:design`'s,
     * shared with the library screens.
     */
    @Test
    fun `the request sheet over a search result`() {
        capture(
            "search-pull-sheet",
            NeedlerDevice.Phone,
            RESULTS.copy(pullSheetAlbum = SampleSearch.buzz, monitorArtist = true),
            // The sheet is what this image is about, and a field focused behind
            // it would draw an accent border through the scrim.
            autoFocus = false,
        )
    }

    @Test
    fun `a pull has been accepted`() {
        capture(
            "search-pull-accepted",
            NeedlerDevice.Phone,
            RESULTS.copy(
                notice = SearchNotice.forRequest(RequestStatus.ACCEPTED),
            ),
        )
    }

    /** REQUIREMENTS.md "Accessibility": "Text must scale to 200% without clipping". */
    @Test
    fun `at 200 percent text size`() {
        capture("search-large-text", NeedlerDevice.Phone, RESULTS, fontScale = 2f)
    }

    // ---- the crate ----------------------------------------------------------

    /**
     * The crate control on the song rows and on the albums this server already has.
     *
     * Two placements and one deliberate absence, all in one image: the song rows carry it
     * beside their duration and claim the long press as well, an owned album row carries it
     * beside its badge, and an un-owned row does not - a catalogue result's tracks exist in
     * MusicBrainz and nowhere else, so its action is Pull and queueing it would add nothing.
     */
    @Test
    fun `song rows and owned album rows offer the crate`() {
        capture("search-crate-control", NeedlerDevice.Phone, RESULTS)
    }

    @Test
    fun `the crate controls at 200 percent text size`() {
        capture("search-crate-control-large-text", NeedlerDevice.Phone, RESULTS, fontScale = 2f)
    }

    /**
     * The line after an add, with the crate's count and total duration under it.
     *
     * Adding to a queue with no visible change is indistinguishable from a tap that did not
     * register, so this line is the whole feedback for the action.
     */
    @Test
    fun `the added-to-the-crate line, with the count and the duration`() {
        capture(
            "search-crate-added",
            NeedlerDevice.Phone,
            RESULTS.copy(
                notice = SearchNotice.addedToCrate(
                    trackCount = 1,
                    playNext = false,
                    started = false,
                ),
                crateTrackCount = 12,
                crateDurationMs = 2_480_000L,
            ),
        )
    }

    // ---- tablet -------------------------------------------------------------

    @Test
    fun `the two-column album grid on a tablet, in the pack's content pane`() {
        val file = captureNeedlerScreen("search-results", NeedlerDevice.Tablet) {
            TabletFrame { Screen(TABLET_RESULTS, WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `offline on a tablet`() {
        val file = captureNeedlerScreen("search-offline", NeedlerDevice.Tablet) {
            TabletFrame {
                Screen(
                    TABLET_RESULTS.copy(
                        offline = true,
                        results = SampleSearch.yussefResults.copy(
                            albums = listOf(SampleSearch.twoStar, SampleSearch.flyteTablet),
                            catalogue = CatalogueLaneState.Unavailable(NeedlerError.Offline()),
                        ),
                    ),
                    WindowWidthSizeClass.Expanded,
                )
            }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `nothing typed yet on a tablet`() {
        val file = captureNeedlerScreen("search-recent", NeedlerDevice.Tablet) {
            TabletFrame {
                Screen(
                    SearchUiState(recentQueries = SampleSearch.recentQueries),
                    WindowWidthSizeClass.Expanded,
                )
            }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    // ---- plumbing -----------------------------------------------------------

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: SearchUiState,
        fontScale: Float = 1f,
        autoFocus: Boolean = true,
    ) {
        val file = captureNeedlerScreen(name, device, fontScale) {
            Screen(
                state = state,
                widthSizeClass = when (device) {
                    NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                    NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
                },
                autoFocus = autoFocus,
            )
        }
        assertRendered(file, device)
    }

    @Composable
    private fun Screen(
        state: SearchUiState,
        widthSizeClass: WindowWidthSizeClass,
        autoFocus: Boolean = true,
    ) {
        SearchScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            autoFocus = autoFocus,
            onQueryChange = {},
            onClearQuery = {},
            onSubmitQuery = {},
            onCancel = {},
            onRecentQuerySelect = {},
            onClearRecentQueries = {},
            onSuggestionSelect = {},
            onArtistClick = {},
            onAlbumClick = {},
            onPull = {},
            onPlayTrack = {},
            onAddTrackToCrate = { _, _ -> },
            onAddAlbumToCrate = { _, _ -> },
            onShowAll = {},
            onLoadMore = {},
            onMonitorArtistChange = {},
            onConfirmPull = {},
            onCancelPull = {},
            onDismissNotice = {},
        )
    }

    private companion object {
        /** Screen 03: "khruangbin", four albums in four states. */
        val RESULTS = SearchUiState(
            query = "khruangbin",
            results = SampleSearch.khruangbinResults,
        )

        /** Screen 10: "yussef dayes", the four-card grid. */
        val TABLET_RESULTS = SearchUiState(
            query = "yussef dayes",
            results = SampleSearch.yussefResults,
        )
    }
}
