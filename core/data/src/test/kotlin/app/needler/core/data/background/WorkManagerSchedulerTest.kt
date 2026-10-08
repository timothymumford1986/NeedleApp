package app.needler.core.data.background

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every `WorkRequest` this app enqueues, actually built.
 *
 * ## The defect this exists for
 *
 * `schedulePollAfterPull` set `setInitialDelay` and `setExpedited` on one request.
 * `WorkRequest.Builder.build()` refuses that pair - `IllegalArgumentException: Expedited jobs cannot
 * be delayed` - and `DefaultPullRepository` calls it the moment a pull reaches the server, so
 * **every pull placed from the UI crashed the app**. Found by using the app, not by the suite.
 *
 * ## Why 2422 tests did not find it
 *
 * Because none of them built a `WorkRequest`. Every test reached [BackgroundWorkScheduler] through
 * `RecordingWorkScheduler`, whose whole implementation of the broken method is `expeditedPolls += 1`
 * - so the suite proved a poll was *requested* and never that one could be *constructed*.
 * [WorkManagerScheduler] was the only implementation that talks to `WorkManager`, and it had no test
 * of any kind.
 *
 * A fake that cannot fail the way the real thing fails is not coverage of the real thing. These
 * assertions call the real builders, so a request that `WorkManager` would reject fails here first.
 */
class WorkManagerSchedulerTest {

    /** The regression itself. `build()` throws before any assertion runs if the pair comes back. */
    @Test
    fun `the poll placed after a pull is delayed, so it must not be expedited`() {
        val request = pollAfterPullRequest()

        assertEquals(
            PollSchedule.EXPEDITED_FIRST_DELAY.inWholeMilliseconds,
            request.workSpec.initialDelay,
        )
        assertFalse(
            "a delayed request cannot be expedited; `build()` throws on the pair",
            request.workSpec.expedited,
        )
    }

    /**
     * And the one request that *is* expedited carries no delay, which is the legal combination.
     *
     * An album download is the job REQUIREMENTS.md asks to be expedited. The pairing rule is the
     * same one; this is the other side of it.
     */
    @Test
    fun `the album download is expedited and carries no delay`() {
        val request = albumDownloadRequest("f1b2c3d4", wifiOnly = false)

        assertTrue(request.workSpec.expedited)
        assertEquals(0L, request.workSpec.initialDelay)
    }

    @Test
    fun `wifi-only downloads ask for an unmetered network, and the default asks for any`() {
        assertEquals(
            NetworkType.UNMETERED,
            albumDownloadRequest("f1b2c3d4", wifiOnly = true).workSpec.constraints.requiredNetworkType,
        )
        assertEquals(
            NetworkType.CONNECTED,
            albumDownloadRequest("f1b2c3d4", wifiOnly = false).workSpec.constraints.requiredNetworkType,
        )
    }

    /**
     * The remaining two build at all, which is the assertion that was missing everywhere.
     *
     * `PeriodicWorkRequest` rejects expedited outright and rejects an interval under fifteen
     * minutes; neither is true today, and this is what says so if either changes.
     */
    @Test
    fun `every cadence produces a periodic request WorkManager accepts`() {
        for (cadence in PollCadence.entries) {
            val request = periodicPollRequest(cadence)
            assertFalse("periodic work cannot be expedited", request.workSpec.expedited)
            assertEquals(
                cadence.interval.inWholeMilliseconds,
                request.workSpec.intervalDuration,
            )
        }
    }

    @Test
    fun `every sync trigger produces a request WorkManager accepts`() {
        for (trigger in SyncTrigger.entries.filter { it != SyncTrigger.NONE }) {
            val request = syncRequest(trigger)
            assertEquals(
                trigger.name,
                request.workSpec.input.getString(LibrarySyncWorker.KEY_TRIGGER),
            )
        }
    }
}
