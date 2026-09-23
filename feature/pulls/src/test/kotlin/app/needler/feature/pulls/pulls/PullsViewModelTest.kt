package app.needler.feature.pulls.pulls

import app.cash.turbine.test
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.PullStatus
import app.needler.feature.pulls.FakePullRepository
import app.needler.feature.pulls.FakeSessions
import app.needler.feature.pulls.MainDispatcherRule
import app.needler.feature.pulls.SamplePulls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the Pulls ViewModel is actually responsible for.
 *
 * Not "does it call the repository" — it plainly does — but the decisions that
 * are easy to get wrong and silent when they are:
 *
 *  1. bucketing an ordered list without losing the order;
 *  2. folding `GET /api/v1/requests/active` into the task list without
 *     duplicating an album that appears in both;
 *  3. choosing between the download lane and the request lane, which
 *     REQUIREMENTS.md keeps deliberately separate and which fail in opposite
 *     ways when confused;
 *  4. gating cancel on the derived state, because the server refuses it during
 *     `processing` and a client that tries anyway shows the user an error it
 *     caused;
 *  5. clearing the badge when the screen opens, which is what makes the badge
 *     "the reliable channel" rather than a number that only ever grows.
 *
 * ## Why several of these subscribe before asserting
 *
 * `state` is shared with `SharingStarted.WhileSubscribed`, so with no collector
 * its `value` is the initial [PullsUiState] and the repository is never read.
 * That is correct — a screen nobody is looking at should not hold Room queries
 * open — and it means a test asserting on `state.value` has to look like a
 * screen: [subscribe] is that, one collector on `backgroundScope` that
 * `runTest` tears down for us.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PullsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val sessions = FakeSessions()

    private fun viewModel(pulls: FakePullRepository) = PullsViewModel(
        pulls = pulls,
        session = sessions,
    )

    /** Keeps the shared state hot for the length of the test, as a screen would. */
    private fun TestScope.subscribe(viewModel: PullsViewModel) {
        backgroundScope.launch { viewModel.state.collect { } }
    }

    @Test
    fun `starts loading and then buckets the queue, newest first`() = runTest {
        val repository = FakePullRepository(
            pulls = SamplePulls.pack,
            summary = SamplePulls.summary,
            badgeCount = 3,
        )
        viewModel(repository).state.test {
            val first = awaitItem()
            assertTrue("the first state is the loading one", first.loading)

            val loaded = awaitItem()
            assertFalse(loaded.loading)
            assertEquals(2, loaded.active.size)
            assertEquals(2, loaded.completed.size)
            assertEquals(1, loaded.failed.size)

            // The pack's own order, preserved across the section boundary: the
            // two finished pulls first, then the older failure.
            assertEquals(
                listOf("Two Star & The Dream Police", "Heaven", "Back Street Crawler"),
                loaded.earlier.map { it.albumTitle },
            )
            assertEquals("2 in progress", loaded.headerLine)
            assertEquals(1, loaded.heldCount)
            assertEquals(3, loaded.badgeCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a parked approval with no task of its own is folded in as waiting`() = runTest {
        val repository = FakePullRepository(
            pulls = SamplePulls.pack,
            approvals = listOf(SamplePulls.pendingApproval),
        )
        viewModel(repository).state.test {
            awaitItem()
            val loaded = awaitItem()

            assertEquals(6, loaded.pulls.size)
            val waiting = loaded.pulls.first()
            assertEquals("Promises", waiting.albumTitle)
            assertEquals(PullState.PENDING_APPROVAL, waiting.state)
            // Not cancellable: the pack offers no way out of an approval queue,
            // and `PullState.isCancellable` agrees.
            assertFalse(waiting.canCancel)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an album in both lanes appears once, marked as waiting`() = runTest {
        // The same release group as a download task *and* as a parked request,
        // which is what the two endpoints return while an admin has not
        // answered yet. Concatenating would draw it twice.
        val asTask = SamplePulls.searching
        val asApproval = asTask.copy(taskId = null, awaitingApproval = true)
        val repository = FakePullRepository(
            pulls = listOf(SamplePulls.downloading, asTask),
            approvals = listOf(asApproval),
        )
        viewModel(repository).state.test {
            awaitItem()
            val loaded = awaitItem()

            assertEquals(2, loaded.pulls.size)
            val fever = loaded.pulls.single { it.albumTitle == "Fever" }
            assertEquals(PullState.PENDING_APPROVAL, fever.state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `opening the screen refreshes and marks the landed albums as seen`() = runTest {
        val repository = FakePullRepository(pulls = SamplePulls.pack)
        viewModel(repository)
        advanceUntilIdle()

        assertEquals(1, repository.refreshCount)
        assertEquals(1, repository.markedSeen.size)
        assertEquals(
            setOf(SamplePulls.mbid("two-star"), SamplePulls.mbid("heaven")),
            repository.markedSeen.single(),
        )
    }

    @Test
    fun `cancelling a task uses the download lane and an approval the request lane`() = runTest {
        val repository = FakePullRepository(pulls = SamplePulls.pack)
        val viewModel = viewModel(repository)
        subscribe(viewModel)
        advanceUntilIdle()

        viewModel.onCancel(SamplePulls.searching)
        advanceUntilIdle()
        assertEquals(listOf(SamplePulls.searching.taskId), repository.cancelledTasks)
        assertTrue(repository.cancelledRequests.isEmpty())
        assertEquals(PullsNotice.Cancelled, viewModel.state.value.notice)

        // A pull the server has not turned into a download task has no id to
        // cancel, so it goes out as a request cancellation instead.
        val unstarted = SamplePulls.pendingApproval.copy(awaitingApproval = false)
        viewModel.onCancel(unstarted)
        advanceUntilIdle()
        assertEquals(listOf(SamplePulls.mbid("promises")), repository.cancelledRequests)
    }

    @Test
    fun `a pull the server would refuse to cancel is not asked about`() = runTest {
        val repository = FakePullRepository(pulls = SamplePulls.pack)
        val viewModel = viewModel(repository)
        subscribe(viewModel)
        advanceUntilIdle()

        // `processing` is the state REQUIREMENTS.md says the server refuses,
        // because files are being moved. The call must not be made at all.
        val processing = SamplePulls.downloading.copy(status = PullStatus.PROCESSING)
        viewModel.onCancel(processing)
        advanceUntilIdle()

        assertTrue(repository.cancelledTasks.isEmpty())
        assertTrue(repository.cancelledRequests.isEmpty())
    }

    @Test
    fun `retrying a failed task uses the download lane`() = runTest {
        val repository = FakePullRepository(pulls = SamplePulls.pack)
        val viewModel = viewModel(repository)
        subscribe(viewModel)
        advanceUntilIdle()

        viewModel.onRetry(SamplePulls.failed)
        advanceUntilIdle()

        assertEquals(listOf(SamplePulls.failed.taskId), repository.retriedTasks)
        assertEquals(PullsNotice.RetryPlaced, viewModel.state.value.notice)
    }

    @Test
    fun `a refused retry becomes a problem notice carrying the server's reason`() = runTest {
        val repository = FakePullRepository(pulls = SamplePulls.pack)
        repository.retryTaskOutcome = Outcome.Failure(
            NeedlerError.Rejected(statusCode = 400, message = "This task cannot be retried."),
        )
        val viewModel = viewModel(repository)
        subscribe(viewModel)
        advanceUntilIdle()

        viewModel.onRetry(SamplePulls.failed)
        advanceUntilIdle()

        val notice: PullsNotice? = viewModel.state.value.notice
        assertTrue("expected a problem, got " + notice, notice is PullsNotice.Problem)
        assertEquals("This task cannot be retried.", notice?.message)
        assertTrue(notice?.isProblem == true)
    }

    @Test
    fun `clear done hides the finished rows and marks them seen`() = runTest {
        val repository = FakePullRepository(pulls = SamplePulls.pack)
        val viewModel = viewModel(repository)
        subscribe(viewModel)
        advanceUntilIdle()

        viewModel.onClearDone()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("finished pulls are gone from this list", state.completed.isEmpty())
        assertEquals("the failure stays: it is not done", 1, state.failed.size)
        assertEquals(2, state.active.size)
        // Once when the screen opened, once from the button.
        assertEquals(2, repository.markedSeen.size)
        assertEquals(PullsNotice.DoneCleared, state.notice)
    }

    @Test
    fun `offline is reported without emptying the list`() = runTest {
        val repository = FakePullRepository(pulls = SamplePulls.pack)
        sessions.connectivityFlow.value = ConnectivityState.Offline
        viewModel(repository).state.test {
            awaitItem()
            val loaded = awaitItem()
            assertTrue(loaded.offline)
            assertEquals(5, loaded.pulls.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty queue is an empty state only once the mirror has answered`() = runTest {
        val repository = FakePullRepository()
        viewModel(repository).state.test {
            val first = awaitItem()
            assertFalse("loading is not an empty state", first.showEmptyState)

            val loaded = awaitItem()
            assertTrue(loaded.showEmptyState)
            assertEquals("", loaded.headerLine)
            assertFalse(loaded.canClearDone)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
