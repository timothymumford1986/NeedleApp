package app.needler.feature.pulls.screenshot

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.RequestHistoryEntry
import app.needler.feature.pulls.SamplePulls
import app.needler.feature.pulls.common.HISTORY_LANE_SUBJECT
import app.needler.feature.pulls.common.WANTED_LANE_SUBJECT
import app.needler.feature.pulls.common.laneProblemMessage
import app.needler.feature.pulls.pulls.HistoryLaneState
import app.needler.feature.pulls.pulls.LaneStatus
import app.needler.feature.pulls.pulls.PullsLane
import app.needler.feature.pulls.pulls.PullsNotice
import app.needler.feature.pulls.pulls.PullsScreen
import app.needler.feature.pulls.pulls.PullsUiState
import app.needler.feature.pulls.pulls.WantedLaneState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Pulls screen in every state it has.
 *
 * `application = Application::class` keeps Hilt out of it: these render the
 * stateless `PullsScreen` from a literal [PullsUiState], so nothing here needs a
 * dependency graph, a repository or a server.
 *
 * The data is the design pack's own — the same five pulls, the same "2 in
 * progress", the same 62% — so the phone PNG can be put beside
 * `design/html/06-Pulls.html` and compared line for line. The states the pack
 * does not happen to draw are rendered too, because they are the ones
 * REQUIREMENTS.md requires and the ones nobody would otherwise look at: a
 * request parked for an administrator, a pull parked for a manual source pick,
 * a part-delivered album, and the held-for-review notice.
 *
 * ## The queue's images have baselines; the lanes' are being recorded now
 *
 * This file was written on a machine with no Android SDK, so for a while none of
 * it had ever run and no golden PNG existed. The queue's images have since been
 * recorded and are committed under `screenshots/`. The history and wanted
 * images below are new and have no baseline yet, which is what record mode is
 * for — the first run writes them and they become the goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class PullsScreenshotTest {

    // ---- phone --------------------------------------------------------------

    @Test
    fun `the pack's own queue`() {
        capture("pulls-queue", NeedlerDevice.Phone, PACK)
    }

    /**
     * All nine states on one list, which is also where the failed bucket's three
     * badges can be compared.
     *
     * Punch-list item 30: `Failed`, `Partly delivered` and `Cancelled` now sit
     * in the same trailing column as `Ready`, `Pulling` and `Searching`. The
     * image is the check that three end states no longer look like one row with
     * a Retry pill and nothing above it.
     */
    @Test
    fun `every state a pull can be in`() {
        capture(
            "pulls-every-state",
            NeedlerDevice.Phone,
            PACK.copy(pulls = SamplePulls.everyState),
        )
    }

    /**
     * The queue a real device drew: mostly pulls the `album` mirror could not
     * name.
     *
     * A screenshot suite built only from well-formed data proves nothing, and this
     * one was. Every fixture had a title, so the render that shipped looked
     * correct while the live screen showed 34 blank title lines and 34 **Cancel**
     * buttons described as "Cancel the pull of ". This image is where that is now
     * visible: the fallback fills the title slot, the one named row is kept beside
     * the unnamed ones so the difference can be seen, and a row with neither title
     * nor artist is included because it is the floor of the layout.
     */
    @Test
    fun `pulls the server could not name`() {
        capture(
            "pulls-missing-titles",
            NeedlerDevice.Phone,
            PACK.copy(pulls = SamplePulls.withMissingTitles, heldCount = 0),
        )
    }

    /** The same rows at 200% text, where a fallback title has the least room. */
    @Test
    fun `pulls the server could not name, at 200 percent text size`() {
        capture(
            "pulls-missing-titles-large-text",
            NeedlerDevice.Phone,
            PACK.copy(pulls = SamplePulls.withMissingTitles, heldCount = 0),
            fontScale = 2f,
        )
    }

    @Test
    fun `nothing in flight, so the header says so`() {
        capture(
            "pulls-nothing-active",
            NeedlerDevice.Phone,
            PACK.copy(
                pulls = listOf(
                    SamplePulls.landedToday,
                    SamplePulls.landedYesterday,
                    SamplePulls.failed,
                ),
                heldCount = 0,
            ),
        )
    }

    @Test
    fun `held items and no connection`() {
        capture(
            "pulls-held-offline",
            NeedlerDevice.Phone,
            PACK.copy(heldCount = 3, offline = true),
        )
    }

    @Test
    fun `an action the server refused`() {
        capture(
            "pulls-problem",
            NeedlerDevice.Phone,
            PACK.copy(
                busy = false,
                notice = PullsNotice.Problem(
                    "The server no longer has that task. It has either finished or been " +
                        "cleared already.",
                ),
            ),
        )
    }

    @Test
    fun `loading, before the mirror has answered`() {
        capture("pulls-loading", NeedlerDevice.Phone, PullsUiState(loading = true))
    }

    @Test
    fun `nothing has ever been pulled`() {
        capture("pulls-empty", NeedlerDevice.Phone, PullsUiState(loading = false))
    }

    @Test
    fun `nothing pulled and no connection to pull with`() {
        capture(
            "pulls-empty-offline",
            NeedlerDevice.Phone,
            PullsUiState(loading = false, offline = true),
        )
    }

    @Test
    fun `at 200 percent text size`() {
        capture("pulls-large-text", NeedlerDevice.Phone, PACK, fontScale = 2f)
    }

    // ---- tablet -------------------------------------------------------------

    @Test
    fun `two panes on a tablet, in the pack's content pane`() {
        val file = captureNeedlerScreen("pulls-queue", NeedlerDevice.Tablet) {
            TabletFrame { Screen(PACK, WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `a tablet pane with nothing in it keeps its heading`() {
        val file = captureNeedlerScreen("pulls-nothing-active", NeedlerDevice.Tablet) {
            TabletFrame(badgeCount = 0) {
                Screen(
                    PACK.copy(
                        pulls = listOf(SamplePulls.landedToday, SamplePulls.failed),
                        heldCount = 0,
                        badgeCount = 0,
                    ),
                    WindowWidthSizeClass.Expanded,
                )
            }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `an empty queue on a tablet`() {
        val file = captureNeedlerScreen("pulls-empty", NeedlerDevice.Tablet) {
            TabletFrame(badgeCount = 0) {
                Screen(PullsUiState(loading = false), WindowWidthSizeClass.Expanded)
            }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    // ---- the queue a device actually had ------------------------------------

    /**
     * Twelve pulls, all parked for a manual source pick, which is what a real install looked like.
     *
     * Three device defects were invisible to this suite until this fixture existed, because every
     * other one mixes states and a fault that needs *every* row to be the same state reads as one
     * odd row rather than as a screen:
     *
     *  * every row drew a progress ring at zero over its cover, for a state that has no progress;
     *  * every row's subtitle repeated the badge beside it, truncated mid-word — "Paul Kelly · a
     *    source needs picking on th…" under a badge already reading "Needs attention on the server";
     *  * and nothing in the suite put a row at the bottom edge of the viewport, where the chrome is.
     */
    @Test
    fun `a queue of pulls all waiting on the same thing`() {
        capture(
            "pulls-all-awaiting-review",
            NeedlerDevice.Phone,
            PACK.copy(pulls = SamplePulls.manyAwaitingReview, heldCount = 0, badgeCount = 12),
        )
    }

    /** The same rows at 200% text, where a repeated state had the least room to repeat itself. */
    @Test
    fun `a queue of pulls all waiting on the same thing, at 200 percent text size`() {
        capture(
            "pulls-all-awaiting-review-large-text",
            NeedlerDevice.Phone,
            PACK.copy(pulls = SamplePulls.manyAwaitingReview, heldCount = 0, badgeCount = 12),
            fontScale = 2f,
        )
    }

    /**
     * The queue inside the chrome the app really puts under it.
     *
     * A device report said the last row was clipped behind the mini player with its **Cancel**
     * unreachable, and no image in this suite could have shown that: every one rendered the screen
     * alone on a bare canvas. [PhoneFrame] reproduces `NeedlerNavigationScaffold`'s phone `Column`,
     * so this is the viewport the list really gets, with the mini player and the bottom bar below
     * it taking their space from it rather than covering it.
     */
    @Test
    fun `the queue under the mini player and the bottom bar`() {
        val file = captureNeedlerScreen("pulls-in-chrome", NeedlerDevice.Phone) {
            PhoneFrame(badgeCount = 12) {
                Screen(
                    PACK.copy(
                        pulls = SamplePulls.manyAwaitingReview,
                        heldCount = 0,
                        badgeCount = 12,
                    ),
                    WindowWidthSizeClass.Compact,
                )
            }
        }
        assertRendered(file, NeedlerDevice.Phone)
    }

    /** The same, with no mini player, which is what the bar looks like before anything plays. */
    @Test
    fun `the queue under the bottom bar alone`() {
        val file = captureNeedlerScreen("pulls-in-chrome-no-player", NeedlerDevice.Phone) {
            PhoneFrame(badgeCount = 12, miniPlayer = false) {
                Screen(
                    PACK.copy(
                        pulls = SamplePulls.manyAwaitingReview,
                        heldCount = 0,
                        badgeCount = 12,
                    ),
                    WindowWidthSizeClass.Compact,
                )
            }
        }
        assertRendered(file, NeedlerDevice.Phone)
    }

    // ---- the history lane ---------------------------------------------------
    //
    // The design pack draws none of this: `design/html/06-Pulls.html` is one list with no tabs, so
    // there is nothing to compare these against line for line. What they are for instead is the
    // states REQUIREMENTS.md requires and nobody would otherwise look at — a page control that
    // names where the end is, a lane with nothing behind it offline, and the same at 200% text,
    // where three tabs and a Refresh pill have the least room.

    @Test
    fun `the history lane, with more pages to come`() {
        capture("pulls-history", NeedlerDevice.Phone, HISTORY)
    }

    /**
     * The page control itself, over a list short enough that the foot is on screen.
     *
     * [HISTORY] renders ten rows, which fills a 390x844 phone and pushes the one row this lane is
     * most worth photographing below the fold. These three images therefore use a short page: a
     * screenshot of a control nobody can see proves nothing about it.
     */
    @Test
    fun `the history lane names the page it would fetch next`() {
        capture("pulls-history-page-control", NeedlerDevice.Phone, MID_PAGE)
    }

    @Test
    fun `the history lane while a page is arriving`() {
        capture(
            "pulls-history-loading-more",
            NeedlerDevice.Phone,
            laneState(PullsLane.HISTORY, history = MID_PAGE.history.copy(appending = true)),
        )
    }

    /**
     * The last page, where the foot of the list stops offering and starts stating.
     *
     * This is the image that shows the thing `GET /api/v1/downloads` cannot do. REQUIREMENTS.md
     * rules out a page count there because it reports no totals; this endpoint reports both, so the
     * list can say "That is all 46 requests." rather than simply stopping and leaving the user to
     * wonder whether it stopped or broke.
     */
    @Test
    fun `the history lane on its last page says so`() {
        capture("pulls-history-last-page", NeedlerDevice.Phone, ONE_PAGE)
    }

    @Test
    fun `nothing has ever been asked for`() {
        capture(
            "pulls-history-empty",
            NeedlerDevice.Phone,
            laneState(PullsLane.HISTORY, history = HistoryLaneState(status = LaneStatus.LOADED)),
        )
    }

    /** Before the server has answered. This lane is a network call, so this is really on screen. */
    @Test
    fun `the history lane before the server has answered`() {
        capture(
            "pulls-history-loading",
            NeedlerDevice.Phone,
            laneState(PullsLane.HISTORY, history = HistoryLaneState(status = LaneStatus.LOADING)),
        )
    }

    /**
     * The history lane with no server, and nothing mirrored to fall back on.
     *
     * REQUIREMENTS.md makes offline "a first-class state, not an error", and this lane has no
     * mirror — `PullRepository.requestHistory`' KDoc explains that "Local persistence" gives the
     * `pull` table no history sibling. So the honest answer is neither a blank pane nor an error
     * dialog: it is a sentence saying where the list lives, that nothing has been lost, and which
     * tab still works without a connection.
     */
    @Test
    fun `the history lane with no connection`() {
        capture(
            "pulls-history-offline",
            NeedlerDevice.Phone,
            laneState(
                PullsLane.HISTORY,
                offline = true,
                history = HistoryLaneState(
                    status = LaneStatus.UNAVAILABLE,
                    problem = laneProblemMessage(
                        NeedlerError.Offline(),
                        HISTORY_LANE_SUBJECT,
                    ),
                ),
            ),
        )
    }

    /** Rows fetched while connected, read offline: they are kept, and the note says how old. */
    @Test
    fun `the history lane keeps its rows when the connection goes`() {
        capture(
            "pulls-history-stale",
            NeedlerDevice.Phone,
            laneState(PullsLane.HISTORY, offline = true, history = HISTORY.history),
        )
    }

    @Test
    fun `the history lane at 200 percent text size`() {
        capture("pulls-history-large-text", NeedlerDevice.Phone, HISTORY, fontScale = 2f)
    }

    // ---- the wanted lane ----------------------------------------------------

    /**
     * Every `WantedWatchState`, both refinements of the live one, and both retrying shapes.
     *
     * The wording is `PullsFormat.wantedState`'s and is not this work's to change: `WATCHING` with
     * `MISSING` reads "not found yet", with `PARTIAL` "only part of it found", and so on. The image
     * is where it can be seen that eight different watches read as eight different sentences rather
     * than as one repeated.
     */
    @Test
    fun `the wanted lane, in every watch state`() {
        capture("pulls-wanted", NeedlerDevice.Phone, WANTED)
    }

    /**
     * Watches with nothing being retried, which is the common case.
     *
     * Worth its own image because it is the one that proves there is no page control: the list
     * simply ends. `GET /api/v1/requests/wanted` has no paging at all, so there is nothing to offer
     * at the foot and `WantedLaneState` cannot express one.
     */
    @Test
    fun `the wanted lane with nothing being retried`() {
        capture(
            "pulls-wanted-watches-only",
            NeedlerDevice.Phone,
            laneState(
                PullsLane.WANTED,
                wanted = WantedLaneState(
                    status = LaneStatus.LOADED,
                    watches = SamplePulls.everyWatchState,
                ),
            ),
        )
    }

    @Test
    fun `the server is looking for nothing`() {
        capture(
            "pulls-wanted-empty",
            NeedlerDevice.Phone,
            laneState(PullsLane.WANTED, wanted = WantedLaneState(status = LaneStatus.LOADED)),
        )
    }

    @Test
    fun `the wanted lane with no connection`() {
        capture(
            "pulls-wanted-offline",
            NeedlerDevice.Phone,
            laneState(
                PullsLane.WANTED,
                offline = true,
                wanted = WantedLaneState(
                    status = LaneStatus.UNAVAILABLE,
                    problem = laneProblemMessage(
                        NeedlerError.Offline(),
                        WANTED_LANE_SUBJECT,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the wanted lane at 200 percent text size`() {
        capture("pulls-wanted-large-text", NeedlerDevice.Phone, WANTED, fontScale = 2f)
    }

    // ---- the lanes on a tablet ----------------------------------------------

    /**
     * The history lane in the pack's content pane.
     *
     * One column, not two. The queue splits because the pack draws two blocks over it and they are
     * comparable halves; a newest-first log has no two halves, and cutting it down the middle would
     * put row 1 beside row 24 and imply a relationship between them.
     */
    @Test
    fun `the history lane on a tablet`() {
        val file = captureNeedlerScreen("pulls-history", NeedlerDevice.Tablet) {
            TabletFrame { Screen(HISTORY, WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    @Test
    fun `the wanted lane on a tablet`() {
        val file = captureNeedlerScreen("pulls-wanted", NeedlerDevice.Tablet) {
            TabletFrame { Screen(WANTED, WindowWidthSizeClass.Expanded) }
        }
        assertRendered(file, NeedlerDevice.Tablet)
    }

    // ---- plumbing -----------------------------------------------------------

    /**
     * The pack's queue with one of the other lanes selected on top of it.
     *
     * Built from [PACK] rather than from an empty state so that every lane render also shows the
     * tab row with a real badge count behind it, and so that switching back to the queue in the
     * image would show rows — a lane screenshot taken over an empty queue would not prove the tabs
     * are a view onto one screen.
     */
    private fun laneState(
        lane: PullsLane,
        history: HistoryLaneState = HistoryLaneState(),
        wanted: WantedLaneState = WantedLaneState(),
        offline: Boolean = false,
    ): PullsUiState = PACK.copy(
        lane = lane,
        history = history,
        wanted = wanted,
        offline = offline,
        // The held notice is the queue's; carrying it onto another lane would only prove it is
        // drawn where it should not be.
        heldCount = 0,
    )

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: PullsUiState,
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
    private fun Screen(state: PullsUiState, widthSizeClass: WindowWidthSizeClass) {
        PullsScreen(
            state = state,
            widthSizeClass = widthSizeClass,
            onClearDone = {},
            onOpenAlbum = {},
            onPlayAlbum = {},
            onCancel = {},
            onRetry = {},
            onDismissNotice = {},
            onSelectLane = {},
            onRefreshLane = {},
            onLoadMoreHistory = {},
            onRetryRequest = {},
            onOpenSearch = {},
        )
    }

    private companion object {
        /** The pack's own queue, at the pack's own instant. */
        val PACK = PullsUiState(
            loading = false,
            pulls = SamplePulls.pack,
            heldCount = SamplePulls.summary.heldCount,
            badgeCount = 3,
            renderedAt = SamplePulls.renderedAt,
        )

        /** Page one of seven, so the foot of the list is an offer that names where the end is. */
        val HISTORY: PullsUiState = PACK.copy(
            lane = PullsLane.HISTORY,
            heldCount = 0,
            history = HistoryLaneState(
                status = LaneStatus.LOADED,
                entries = SamplePulls.historyEveryOutcome,
                paging = SamplePulls.historyPage(entries = SamplePulls.historyEveryOutcome),
            ),
        )

        val WANTED: PullsUiState = PACK.copy(
            lane = PullsLane.WANTED,
            heldCount = 0,
            wanted = WantedLaneState.loaded(SamplePulls.wantedList),
        )

        /** The first three entries, which is all these two fit on screen. */
        private val SHORT: List<RequestHistoryEntry> =
            SamplePulls.historyEveryOutcome.take(3)

        /**
         * Page one of seven, short enough that the page control is on screen.
         *
         * [HISTORY] renders ten rows, which fills a 390x844 phone and pushes the one row this lane
         * is most worth photographing below the fold. The totals here are self-consistent — three
         * per page, seven pages, nineteen requests — because a baseline with arithmetic a reader
         * can see is wrong is worse than no baseline.
         */
        val MID_PAGE: PullsUiState = PACK.copy(
            lane = PullsLane.HISTORY,
            heldCount = 0,
            history = HistoryLaneState(
                status = LaneStatus.LOADED,
                entries = SHORT,
                paging = SamplePulls.historyPage(
                    entries = SHORT,
                    page = 1,
                    pageSize = 3,
                    totalPages = 7,
                    total = 19,
                ),
            ),
        )

        /** A user with three requests and one page of them, so the list really has ended. */
        val ONE_PAGE: PullsUiState = PACK.copy(
            lane = PullsLane.HISTORY,
            heldCount = 0,
            history = HistoryLaneState(
                status = LaneStatus.LOADED,
                entries = SHORT,
                paging = SamplePulls.historyPage(
                    entries = SHORT,
                    page = 1,
                    totalPages = 1,
                    total = SHORT.size,
                ),
            ),
        )
    }
}
