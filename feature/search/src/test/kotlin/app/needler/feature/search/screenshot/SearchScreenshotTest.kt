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
    private fun Screen(state: SearchUiState, widthSizeClass: WindowWidthSizeClass) {
        SearchScreen(
            state = state,
            widthSizeClass = widthSizeClass,
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
