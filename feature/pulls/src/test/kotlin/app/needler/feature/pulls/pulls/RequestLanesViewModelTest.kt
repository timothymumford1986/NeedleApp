package app.needler.feature.pulls.pulls

import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pull
import app.needler.core.domain.model.RequestHistoryPage
import app.needler.core.domain.model.WantedList
import app.needler.core.domain.model.WantedWatchState
import app.needler.core.domain.repository.PullRepository
import app.needler.feature.pulls.FakePullRepository
import app.needler.feature.pulls.FakeSessions
import app.needler.feature.pulls.MainDispatcherRule
import app.needler.feature.pulls.NoLanesRepository
import app.needler.feature.pulls.SamplePulls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The two lanes that are not the queue, and the asymmetry between them.
 *
 * `PullsFormatTest` already covers every string these lanes render and this file deliberately does
 * not repeat it. What is tested here is the behaviour no formatter can be wrong about:
 *
 *  1. **nothing is fetched until a lane is opened**, which is the whole of the battery argument —
 *     REQUIREMENTS.md "Battery and data" allows no extra polling, and a lane fetched on screen
 *     creation would be two network calls per visit to a screen the user may never leave the first
 *     tab of;
 *  2. **history pages and wanted does not**, which is the one structural difference REQUIREMENTS.md
 *     states twice and the one a UI is most likely to flatten;
 *  3. **a lane with no server says so rather than looking empty**, which is the difference between
 *     "you have never asked for anything" and "nobody could ask";
 *  4. **rows survive a failed refresh**, because REQUIREMENTS.md makes offline a state rather than
 *     an error and a list that blanks itself to show an error is the opposite of that.
 *
 * Every test subscribes before asserting, for the reason `PullsViewModelTest` gives: `state` is
 * shared with `WhileSubscribed`, so with no collector the repository is never read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestLanesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sessions = FakeSessions()

    private fun viewModel(pulls: FakePullRepository) = PullsViewModel(
        pulls = pulls,
        session = sessions,
    )

    private fun TestScope.subscribe(viewModel: PullsViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    // ---- nothing is asked for until a tab is opened --------------------------

    @Test
    fun `opening the screen asks neither lane for anything`() = runTest {
        val repository = repository()
        val viewModel = viewModel(repository)
        subscribe(viewModel)
        advanceUntilIdle()

        assertEquals("the queue is the first tab", PullsLane.QUEUE, viewModel.state.value.lane)
        assertEquals("history was fetched", emptyList<Int>(), repository.historyRequests)
        assertEquals("wanted was fetched", 0, repository.wantedCalls)
        assertEquals(LaneStatus.UNASKED, viewModel.state.value.history.status)
        assertEquals(LaneStatus.UNASKED, viewModel.state.value.wanted.status)
    }

    @Test
    fun `selecting the queue again fetches nothing, because it is already collected`() = runTest {
        val repository = repository()
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.QUEUE)
        advanceUntilIdle()

        assertEquals(emptyList<Int>(), repository.historyRequests)
        assertEquals(0, repository.wantedCalls)
    }

    /**
     * A tab is a cheap tap, so flicking between lanes must not be a request per tap.
     *
     * This is the battery rule expressed as the one thing a screen can get wrong about it. The 2 s
     * queue poll is `:core:data`'s and untouched; what this module chooses is how often it asks for
     * two lists that change on the scale of days.
     */
    @Test
    fun `a lane is fetched once however many times it is reopened`() = runTest {
        val repository = repository()
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        viewModel.onSelectLane(PullsLane.WANTED)
        advanceUntilIdle()
        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        viewModel.onSelectLane(PullsLane.QUEUE)
        advanceUntilIdle()
        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()

        assertEquals("history asked more than once", listOf(1), repository.historyRequests)
        assertEquals("wanted asked more than once", 1, repository.wantedCalls)
    }

    @Test
    fun `history is asked for page one at the endpoint's own page size`() = runTest {
        val repository = repository()
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()

        assertEquals(listOf(1), repository.historyRequests)
        assertEquals(
            "the page size is the endpoint's documented default",
            listOf(PullRepository.HISTORY_PAGE_SIZE),
            repository.historyPageSizes,
        )
    }

    // ---- history pages, and knows where the end is --------------------------

    @Test
    fun `history with more than one page offers the next one by number`() = runTest {
        val firstPage = SamplePulls.historyPage(
            entries = SamplePulls.historyEveryOutcome,
            page = 1,
            totalPages = 7,
            total = 46,
        )
        val repository = repository(history = { Outcome.Success(firstPage) })
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()

        val lane = viewModel.state.value.history
        assertEquals(LaneStatus.LOADED, lane.status)
        assertEquals(SamplePulls.historyEveryOutcome.size, lane.entries.size)
        assertTrue("the server said there are seven pages", lane.hasMore)
        assertEquals("Load page 2 of 7", lane.moreRow?.label)
        assertTrue(lane.moreRow?.enabled == true)

        // The server's own total, not the count on screen. This is the lane that has one.
        assertEquals("46 requests", viewModel.state.value.laneHeaderLine)
    }

    @Test
    fun `loading more appends the next page and keeps the rows already drawn`() = runTest {
        val first = SamplePulls.historyEveryOutcome.take(4)
        val second = SamplePulls.historyEveryOutcome.drop(4)
        val repository = repository(
            history = { page ->
                Outcome.Success(
                    SamplePulls.historyPage(
                        entries = if (page == 1) first else second,
                        page = page,
                        totalPages = 2,
                        total = SamplePulls.historyEveryOutcome.size,
                    ),
                )
            },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        viewModel.onLoadMoreHistory()
        advanceUntilIdle()

        val lane = viewModel.state.value.history
        assertEquals(listOf(1, 2), repository.historyRequests)
        assertEquals(first + second, lane.entries)
        assertEquals("page two is the one it is on", 2, lane.paging.page)
    }

    /**
     * The end of the list, said out loud.
     *
     * The one thing this lane can do that `GET /api/v1/downloads` cannot. REQUIREMENTS.md rules out
     * a page control on the download queue because it reports neither `total` nor `total_pages`;
     * both are reported here, so the list can state its own end instead of simply stopping.
     */
    @Test
    fun `history on its last page states the total and offers nothing more`() = runTest {
        val repository = repository(
            history = { page ->
                Outcome.Success(
                    SamplePulls.historyPage(
                        entries = SamplePulls.historyEveryOutcome,
                        page = page,
                        totalPages = 1,
                        total = SamplePulls.historyEveryOutcome.size,
                    ),
                )
            },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()

        val lane = viewModel.state.value.history
        assertFalse("there is no second page", lane.hasMore)
        assertEquals("That is all 10 requests.", lane.moreRow?.label)
        assertFalse("a statement is not an offer", lane.moreRow?.enabled == true)

        // And tapping it does nothing, so a row that reads as inert is inert.
        viewModel.onLoadMoreHistory()
        advanceUntilIdle()
        assertEquals(listOf(1), repository.historyRequests)
    }

    @Test
    fun `a page that fails to arrive says why at the foot and can be tapped again`() = runTest {
        var attempt = 0
        val repository = repository(
            history = { page ->
                attempt++
                when {
                    page == 1 -> Outcome.Success(
                        SamplePulls.historyPage(
                            entries = SamplePulls.historyEveryOutcome,
                            page = 1,
                            totalPages = 3,
                        ),
                    )
                    // The first attempt at page two fails; the second succeeds.
                    attempt == 2 -> Outcome.Failure(NeedlerError.ServerError(503, "unavailable"))
                    else -> Outcome.Success(
                        SamplePulls.historyPage(
                            entries = listOf(SamplePulls.historyArrived),
                            page = 2,
                            totalPages = 3,
                        ),
                    )
                }
            },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        viewModel.onLoadMoreHistory()
        advanceUntilIdle()

        val failed = viewModel.state.value.history
        assertEquals("the rows already drawn are kept", LaneStatus.LOADED, failed.status)
        assertEquals(SamplePulls.historyEveryOutcome.size, failed.entries.size)
        assertTrue("the reason is at the foot", failed.moreRow?.isProblem == true)
        assertTrue("503" in failed.moreRow?.label.orEmpty())
        assertTrue("and it is tappable again", failed.moreRow?.enabled == true)

        viewModel.onLoadMoreHistory()
        advanceUntilIdle()

        val recovered = viewModel.state.value.history
        assertEquals("the same page was retried", listOf(1, 2, 2), repository.historyRequests)
        assertNull(recovered.problem)
        assertEquals(SamplePulls.historyEveryOutcome.size + 1, recovered.entries.size)
    }

    @Test
    fun `refreshing history starts again from page one and replaces the list`() = runTest {
        val repository = repository(
            history = { page ->
                Outcome.Success(
                    SamplePulls.historyPage(
                        entries = listOf(SamplePulls.historyArrived),
                        page = page,
                        totalPages = 4,
                    ),
                )
            },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        viewModel.onLoadMoreHistory()
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.history.entries.size)

        viewModel.onRefreshLane()
        advanceUntilIdle()

        assertEquals(listOf(1, 2, 1), repository.historyRequests)
        assertEquals(
            "a refresh replaces rather than appends, because the pages below may have moved",
            1,
            viewModel.state.value.history.entries.size,
        )
    }

    // ---- wanted does not page ----------------------------------------------

    /**
     * The whole list in one call, because that is all the endpoint offers.
     *
     * REQUIREMENTS.md: `requests/active` and `requests/wanted` "have no paging at all and return
     * the whole list". There is no page to assert on here and that is the assertion: everything the
     * server has arrives in one answer, and `WantedLaneState` has no field a second request could
     * be built from, so no screen on it can ask for one.
     */
    @Test
    fun `the wanted lane arrives whole in one call and offers no second page`() = runTest {
        val repository = repository(wanted = { Outcome.Success(SamplePulls.wantedList) })
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.WANTED)
        advanceUntilIdle()

        val lane = viewModel.state.value.wanted
        assertEquals("one call, not a walk", 1, repository.wantedCalls)
        assertEquals(LaneStatus.LOADED, lane.status)
        assertEquals(SamplePulls.everyWatchState, lane.watches)
        assertEquals(SamplePulls.retrying, lane.retrying)

        // Both figures, because the response's own `count` covers the watches only.
        assertEquals("8 watched · 2 retrying", viewModel.state.value.laneHeaderLine)

        // Paging history while the wanted lane is showing asks nothing of either server lane:
        // the control does not exist on this tab and the intent is a no-op without one.
        viewModel.onLoadMoreHistory()
        advanceUntilIdle()
        assertEquals(1, repository.wantedCalls)
        assertEquals(emptyList<Int>(), repository.historyRequests)
    }

    @Test
    fun `every watch state arrives intact, including the ones this client does not model`() =
        runTest {
            val repository = repository(wanted = { Outcome.Success(SamplePulls.wantedList) })
            val viewModel = viewModel(repository)
            subscribe(viewModel)

            viewModel.onSelectLane(PullsLane.WANTED)
            advanceUntilIdle()

            assertEquals(
                "a fixture set that misses a state would let a bad mapping through",
                WantedWatchState.entries.toSet(),
                viewModel.state.value.wanted.watches.map { it.state }.toSet(),
            )
        }

    @Test
    fun `refreshing the wanted lane asks the one call again`() = runTest {
        val repository = repository(wanted = { Outcome.Success(SamplePulls.wantedList) })
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.WANTED)
        advanceUntilIdle()
        viewModel.onRefreshLane()
        advanceUntilIdle()

        assertEquals(2, repository.wantedCalls)
    }

    // ---- empty, and the four empties that are not the same -----------------

    @Test
    fun `a lane the server answered with nothing is empty, not unavailable`() = runTest {
        val repository = repository(
            history = { Outcome.Success(RequestHistoryPage.Empty) },
            wanted = { Outcome.Success(WantedList.Empty) },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.history.showEmptyState)
        assertNull(viewModel.state.value.history.problem)
        assertNull("an empty list has no page control", viewModel.state.value.history.moreRow)
        assertEquals("", viewModel.state.value.laneHeaderLine)

        viewModel.onSelectLane(PullsLane.WANTED)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.wanted.showEmptyState)
        assertNull(viewModel.state.value.wanted.problem)
    }

    @Test
    fun `an unasked lane is not an empty one`() = runTest {
        val repository = repository(
            history = { Outcome.Success(RequestHistoryPage.Empty) },
            wanted = { Outcome.Success(WantedList.Empty) },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)
        advanceUntilIdle()

        // The distinction that stops the screen telling a user they have never asked for anything
        // when in fact nobody has looked yet.
        assertFalse(viewModel.state.value.history.showEmptyState)
        assertFalse(viewModel.state.value.wanted.showEmptyState)
    }

    @Test
    fun `the queue's empty state is unaffected by the other two lanes`() = runTest {
        val repository = repository(
            history = { Outcome.Success(RequestHistoryPage.Empty) },
            wanted = { Outcome.Success(WantedList.Empty) },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        viewModel.onSelectLane(PullsLane.QUEUE)
        advanceUntilIdle()

        assertTrue("the mirror has answered and is empty", viewModel.state.value.showEmptyState)
        assertFalse("and it is still a Room read, not a fetch", viewModel.state.value.laneFetching)
    }

    // ---- no network --------------------------------------------------------

    /**
     * Offline, the two read-through lanes explain themselves rather than drawing nothing.
     *
     * REQUIREMENTS.md: "Offline is a first-class state, not an error." These lanes have no mirror —
     * `PullRepository.requestHistory`' KDoc records that "Local persistence" gives the `pull` table
     * no history sibling — so the honest offline answer is a sentence, and the sentence has to say
     * what is still true: the server keeps its record, keeps looking, and the queue still works.
     */
    @Test
    fun `each read-through lane with no network says where its list lives`() = runTest {
        sessions.connectivityFlow.value = ConnectivityState.Offline
        val offline = NeedlerError.Offline()
        val repository = repository(
            history = { Outcome.Failure(offline) },
            wanted = { Outcome.Failure(offline) },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        val history = viewModel.state.value.history
        assertEquals(LaneStatus.UNAVAILABLE, history.status)
        assertFalse("not an empty state: nobody said there was nothing", history.showEmptyState)
        assertTrue("Offline" in history.problem.orEmpty())
        assertTrue("it names which list", "request history" in history.problem.orEmpty())
        assertTrue(
            "a read cannot be queued for replay, and must not claim to be",
            "queued" !in history.problem.orEmpty(),
        )

        viewModel.onSelectLane(PullsLane.WANTED)
        advanceUntilIdle()
        val wanted = viewModel.state.value.wanted
        assertEquals(LaneStatus.UNAVAILABLE, wanted.status)
        assertTrue("wanted list" in wanted.problem.orEmpty())
    }

    /** The queue is a Room query, so it is the one lane that still has rows with no network. */
    @Test
    fun `the queue still draws its rows while the other two cannot be read`() = runTest {
        sessions.connectivityFlow.value = ConnectivityState.Offline
        val repository = repository(
            pulls = SamplePulls.pack,
            history = { Outcome.Failure(NeedlerError.Offline()) },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        assertEquals(LaneStatus.UNAVAILABLE, viewModel.state.value.history.status)

        viewModel.onSelectLane(PullsLane.QUEUE)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.offline)
        assertEquals(SamplePulls.pack.size, viewModel.state.value.pulls.size)
        assertFalse(viewModel.state.value.showEmptyState)
    }

    /**
     * A refresh that fails over rows already on screen keeps them.
     *
     * The alternative — blanking the list to show the error — is what makes offline read as a
     * failure of the app. The rows were true when they were fetched and nothing has disproved them,
     * so they stay, the screen says they may be out of date, and the failure is reported as a
     * notice rather than as the lane's whole content.
     */
    @Test
    fun `a failed refresh keeps the rows and reports the failure as a notice`() = runTest {
        var answered = false
        val repository = repository(
            history = {
                if (answered) {
                    Outcome.Failure(NeedlerError.Offline())
                } else {
                    answered = true
                    Outcome.Success(
                        SamplePulls.historyPage(
                            entries = SamplePulls.historyEveryOutcome,
                            totalPages = 2,
                        ),
                    )
                }
            },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        viewModel.onRefreshLane()
        advanceUntilIdle()

        val lane = viewModel.state.value.history
        assertEquals("the rows are kept", LaneStatus.LOADED, lane.status)
        assertEquals(SamplePulls.historyEveryOutcome.size, lane.entries.size)
        assertNull("so the page control stays a page control", lane.problem)
        assertEquals("Load page 2 of 2", lane.moreRow?.label)

        val notice = viewModel.state.value.notice
        assertTrue("the failure is still reported", notice is PullsNotice.Problem)
        assertTrue(notice?.isProblem == true)
    }

    /**
     * The interface's own default answer: a repository with no server to ask.
     *
     * `PullRepository.requestHistory` and `wantedList` are the only two members with bodies, and the
     * body is a `CapabilityUnavailable` failure rather than an empty answer, precisely so a screen
     * cannot mistake "nobody implemented this" for "you have never asked for anything". This is the
     * test that the screen does not make that mistake.
     */
    @Test
    fun `a repository that cannot answer at all is unavailable, not empty`() = runTest {
        val viewModel = PullsViewModel(pulls = NoLanesRepository(), session = sessions)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()

        val lane = viewModel.state.value.history
        assertEquals(LaneStatus.UNAVAILABLE, lane.status)
        assertFalse(lane.showEmptyState)
        assertTrue("This build cannot read" in lane.problem.orEmpty())
    }

    // ---- retrying a request ------------------------------------------------

    @Test
    fun `retrying a failed request uses the request lane and says what happens next`() = runTest {
        val repository = repository(
            history = { Outcome.Success(SamplePulls.historyPage(SamplePulls.historyEveryOutcome)) },
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onSelectLane(PullsLane.HISTORY)
        advanceUntilIdle()
        viewModel.onRetryRequest(SamplePulls.historyFailed)
        advanceUntilIdle()

        assertEquals(
            "the request lane, not the download lane: a history entry has no task id",
            listOf(SamplePulls.historyFailed.releaseGroupMbid),
            repository.retriedRequests,
        )
        assertTrue(repository.retriedTasks.isEmpty())
        assertEquals(PullsNotice.RetryPlaced, viewModel.state.value.notice)
    }

    /**
     * A declined request is not re-asked, and the guard is in the ViewModel as well as the row.
     *
     * `RequestOutcome.isRetryable` excludes `REJECTED` because retrying "re-asks an administrator
     * who has already said no" and the endpoint answers `200` with `success=false` for it anyway.
     * The row draws no Retry, but a screen is not the only caller of an intent, and the one place
     * this must hold is the one that would make the call.
     */
    @Test
    fun `a request an administrator declined is not retried`() = runTest {
        val repository = repository()
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onRetryRequest(SamplePulls.historyDeclined)
        advanceUntilIdle()

        assertTrue(repository.retriedRequests.isEmpty())
        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun `a request the server refused to retry becomes a problem carrying its reason`() = runTest {
        val repository = repository()
        repository.retryRequestOutcome = Outcome.Failure(
            NeedlerError.Rejected(statusCode = 200, message = "Already queued."),
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)

        viewModel.onRetryRequest(SamplePulls.historyFailed)
        advanceUntilIdle()

        assertEquals(PullsNotice.Problem("Already queued."), viewModel.state.value.notice)
    }

    // ---- plumbing ----------------------------------------------------------

    private fun repository(
        pulls: List<Pull> = emptyList(),
        history: ((Int) -> Outcome<RequestHistoryPage>)? = null,
        wanted: (() -> Outcome<WantedList>)? = null,
    ): FakePullRepository = FakePullRepository(pulls = pulls).also { fake ->
        if (history != null) fake.historyAnswer = history
        if (wanted != null) fake.wantedAnswer = wanted
    }
}
