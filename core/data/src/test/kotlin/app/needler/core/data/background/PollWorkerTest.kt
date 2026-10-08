package app.needler.core.data.background

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.CapturingSlot
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the two poll workers return, which is what decides whether the ladder carries on.
 *
 * ## Why this file exists
 *
 * `PullPollerTest` covers [PullPoller] - what a poll does. Nothing covered the two `CoroutineWorker`s
 * around it, which is where the `WorkManager` contract is: a `Result` is not a value the app reads,
 * it is an instruction to the platform, and returning the wrong one means the ladder either stops
 * when it should carry on or runs for ever when it should stop.
 *
 * It is the same gap `WorkManagerSchedulerTest` was written for, one file along. There the untested
 * half built a request `WorkManager` rejected outright and crashed the app on every pull; here the
 * untested half decides whether a user waiting on a record ever hears about it.
 *
 * Robolectric, because `TestListenableWorkerBuilder` needs a real `Context` to make
 * `WorkerParameters`. The workers are `@HiltWorker`, so the factory below supplies the one
 * dependency by hand rather than standing up a component for a class with a single collaborator.
 */
@RunWith(RobolectricTestRunner::class)
class PollWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ------------------------------------------------------------------ the periodic poller

    @Test
    fun `a poll that succeeded is success`() = runTest {
        val poller = poller(result(activeCount = 0))

        assertEquals(ListenableWorker.Result.success(), periodicWorker(poller).doWork())
    }

    /**
     * Offline is a retry, not a failure.
     *
     * The worker's own comment is the rule: "A poll that could not reach the server is not a failure
     * worth surfacing: the app is simply offline, which is a first-class state." `Result.failure()`
     * would take the row out of `WorkManager`'s hands permanently, so a phone that polled once in a
     * tunnel would stop polling until something re-enqueued it.
     */
    @Test
    fun `a poll that failed and can be retried is a retry, so going offline does not end the ladder`() =
        runTest {
            val poller = poller(result(failed = true, retryable = true))

            assertEquals(ListenableWorker.Result.retry(), periodicWorker(poller).doWork())
        }

    /** A failure the server will give again is success: retrying it would spend battery on nothing. */
    @Test
    fun `a poll that failed and cannot be retried is success, not failure`() = runTest {
        val poller = poller(result(failed = true, retryable = false))

        assertEquals(ListenableWorker.Result.success(), periodicWorker(poller).doWork())
    }

    /** Every cadence the enum offers reaches the poller intact. */
    @Test
    fun `the cadence travels through the input data`() = runTest {
        for (cadence in PollCadence.entries) {
            val poller = poller(result())

            periodicWorker(poller, cadence.name).doWork()

            assertEquals(cadence, poller.cadence())
        }
    }

    /**
     * A missing or unrecognised cadence falls back rather than throwing.
     *
     * `inputData` is a persisted `Data` blob: a row written by an older build, or one whose enum
     * constant has since been renamed, arrives here with a string that matches nothing. An
     * exception in `doWork` is a failed job, and the fallback is what keeps the poller alive across
     * that upgrade.
     */
    @Test
    fun `an unknown cadence falls back to idle instead of throwing`() = runTest {
        val poller = poller(result())

        periodicWorker(poller, cadence = "A_CADENCE_THIS_BUILD_DOES_NOT_HAVE").doWork()

        assertEquals(PollCadence.IDLE, poller.cadence())
    }

    @Test
    fun `no cadence at all falls back to idle`() = runTest {
        val poller = poller(result())

        TestListenableWorkerBuilder<PullPollWorker>(context)
            .setWorkerFactory(factory(poller))
            .build()
            .doWork()

        assertEquals(PollCadence.IDLE, poller.cadence())
    }

    // ----------------------------------------------------------------- the after-a-pull poller

    /** The one it was placed for: a pull still running means check again. */
    @Test
    fun `the after-a-pull poll retries while a pull is still active`() = runTest {
        val poller = poller(result(activeCount = 1))

        assertEquals(
            ListenableWorker.Result.retry(),
            expeditedWorker(poller, runAttemptCount = 0).doWork(),
        )
        assertEquals(PollCadence.ACTIVE_PULLS, poller.cadence())
    }

    /** And stops as soon as nothing is in flight, rather than running the ladder out. */
    @Test
    fun `the after-a-pull poll stops once nothing is active`() = runTest {
        val poller = poller(result(activeCount = 0))

        assertEquals(
            ListenableWorker.Result.success(),
            expeditedWorker(poller, runAttemptCount = 0).doWork(),
        )
    }

    /**
     * The ladder has a ceiling, and reaching it is success rather than failure.
     *
     * `PollSchedule.shouldKeepExpediting` is where the ceiling lives and `PollScheduleTest` asserts
     * the arithmetic; this asserts the worker obeys it. Past the ceiling the fifteen-minute periodic
     * poller takes over, so the correct answer is to stop cleanly - a `Result.failure()` here would
     * be reported as a failed job for a ladder that ended exactly as designed.
     */
    @Test
    fun `the after-a-pull poll gives up at the ceiling even with a pull still active`() = runTest {
        val poller = poller(result(activeCount = 3))
        var attempt = 0
        var result: ListenableWorker.Result =
            expeditedWorker(poller, runAttemptCount = attempt).doWork()

        while (result == ListenableWorker.Result.retry() && attempt < CEILING_SEARCH_LIMIT) {
            attempt++
            result = expeditedWorker(poller, runAttemptCount = attempt).doWork()
        }

        assertEquals(
            "the ladder never stopped in " + CEILING_SEARCH_LIMIT + " attempts, so it runs for ever",
            ListenableWorker.Result.success(),
            result,
        )
    }

    @Test
    fun `an after-a-pull poll that failed and can be retried is a retry`() = runTest {
        val poller = poller(result(failed = true, retryable = true))

        assertEquals(
            ListenableWorker.Result.retry(),
            expeditedWorker(poller, runAttemptCount = 0).doWork(),
        )
    }

    @Test
    fun `an after-a-pull poll that failed for good is success, not failure`() = runTest {
        val poller = poller(result(failed = true, retryable = false))

        assertEquals(
            ListenableWorker.Result.success(),
            expeditedWorker(poller, runAttemptCount = 0).doWork(),
        )
    }

    // ---------------------------------------------------------------------------- plumbing

    private fun periodicWorker(
        poller: CapturingPoller,
        cadence: String = PollCadence.ACTIVE_PULLS.name,
    ): PullPollWorker = TestListenableWorkerBuilder<PullPollWorker>(context)
        .setInputData(workDataOf(PullPollWorker.KEY_CADENCE to cadence))
        .setWorkerFactory(factory(poller))
        .build()

    private fun expeditedWorker(
        poller: CapturingPoller,
        runAttemptCount: Int,
    ): ExpeditedPollWorker = TestListenableWorkerBuilder<ExpeditedPollWorker>(context)
        .setRunAttemptCount(runAttemptCount)
        .setWorkerFactory(factory(poller))
        .build()

    /** Supplies the one injected collaborator, which is cheaper than a Hilt component for it. */
    private fun factory(poller: CapturingPoller): WorkerFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = when (workerClassName) {
            PullPollWorker::class.java.name ->
                PullPollWorker(appContext, workerParameters, poller.mock)
            ExpeditedPollWorker::class.java.name ->
                ExpeditedPollWorker(appContext, workerParameters, poller.mock)
            else -> error("no worker for " + workerClassName)
        }
    }

    /**
     * A [PullPoller] that answers with [answer] and remembers what it was asked for.
     *
     * `PullPoller` is a final class with five collaborators of its own, so it is mocked rather than
     * constructed: what is under test here is the worker's mapping from a result to a `Result`, and
     * building a real poller would make these tests fail for reasons that belong to `PullPollerTest`.
     */
    private fun poller(answer: PollRunResult): CapturingPoller {
        val mock: PullPoller = mockk()
        val asked = slot<PollCadence>()
        coEvery { mock.poll(capture(asked)) } returns answer
        return CapturingPoller(mock, asked)
    }

    private class CapturingPoller(
        val mock: PullPoller,
        private val asked: CapturingSlot<PollCadence>,
    ) {
        fun cadence(): PollCadence = asked.captured
    }

    /** A result with the fields these tests do not care about filled in. */
    private fun result(
        activeCount: Int = 0,
        failed: Boolean = false,
        retryable: Boolean = false,
    ): PollRunResult = PollRunResult(
        polled = !failed,
        revisionUnchanged = false,
        activeCount = activeCount,
        notificationsPosted = 0,
        syncRan = false,
        failed = failed,
        retryable = retryable,
    )

    private companion object {
        /** Enough attempts that a ladder which does stop will have stopped. */
        const val CEILING_SEARCH_LIMIT: Int = 64
    }
}
