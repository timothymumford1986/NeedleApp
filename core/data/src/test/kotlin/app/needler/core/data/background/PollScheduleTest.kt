package app.needler.core.data.background

import app.needler.core.data.settings.NotificationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The battery rules, which are requirements rather than tuning.
 *
 * REQUIREMENTS.md is explicit that there must be no polling when there are no active pulls and
 * notifications are off, beyond the six-hourly sync - and that fifteen minutes is `WorkManager`'s
 * floor, which nothing here pretends to beat. Both are the sort of thing that is easy to break
 * while making something else work and impossible to notice on a device: a poller that woke every
 * fifteen minutes for ever would look completely normal for a week and then show up as a battery
 * complaint.
 */
public class PollScheduleTest {

    @Test
    public fun `active pulls poll at WorkManager's floor and no faster`() {
        assertEquals(PollCadence.ACTIVE_PULLS, PollSchedule.cadenceFor(hasActivePulls = true))
        assertEquals(15.minutes, PollCadence.ACTIVE_PULLS.interval)
        assertEquals(
            "the periodic cadence must not claim to beat WorkManager's own minimum",
            PollSchedule.MINIMUM_PERIODIC_INTERVAL,
            PollCadence.ACTIVE_PULLS.interval,
        )
    }

    @Test
    public fun `nothing active falls back to the six-hourly wake`() {
        assertEquals(PollCadence.IDLE, PollSchedule.cadenceFor(hasActivePulls = false))
        assertEquals(6.hours, PollCadence.IDLE.interval)
    }

    @Test
    public fun `no active pulls and every notification off means no network at all`() {
        val allOff = NotificationSettings(
            pullFinished = false,
            pullFailed = false,
            newReleaseFromFollowedArtist = false,
        )

        assertFalse(
            "there is nothing a poll could report, so it must not happen",
            PollSchedule.shouldPollActivity(hasActivePulls = false, notifications = allOff),
        )
    }

    @Test
    public fun `an active pull is polled even with every notification off`() {
        val allOff = NotificationSettings(
            pullFinished = false,
            pullFailed = false,
            newReleaseFromFollowedArtist = false,
        )

        // The Pulls badge is fed by this poll and is the channel REQUIREMENTS.md calls reliable, so
        // switching notifications off must not stop the app knowing what its own queue is doing.
        assertTrue(PollSchedule.shouldPollActivity(hasActivePulls = true, notifications = allOff))
    }

    @Test
    public fun `one notification left on is enough to keep polling`() {
        val onlyFailures = NotificationSettings(
            pullFinished = false,
            pullFailed = true,
            newReleaseFromFollowedArtist = false,
        )

        assertTrue(
            PollSchedule.shouldPollActivity(hasActivePulls = false, notifications = onlyFailures),
        )
    }

    @Test
    public fun `only the six-hourly cadence drives the metadata sync`() {
        assertTrue(PollSchedule.shouldSync(PollCadence.IDLE))
        assertFalse(
            "syncing every fifteen minutes while a pull runs is four getIndexes calls an hour",
            PollSchedule.shouldSync(PollCadence.ACTIVE_PULLS),
        )
    }

    @Test
    public fun `the post-pull check backs off from one minute to fifteen`() {
        assertEquals(1.minutes, PollSchedule.expeditedDelay(attempt = 0))
        assertEquals(2.minutes, PollSchedule.expeditedDelay(attempt = 1))
        assertEquals(4.minutes, PollSchedule.expeditedDelay(attempt = 2))
        assertEquals(8.minutes, PollSchedule.expeditedDelay(attempt = 3))
        // Doubling again would give sixteen, which is past the point where the periodic poller is
        // the right owner, so it clamps rather than overshooting.
        assertEquals(15.minutes, PollSchedule.expeditedDelay(attempt = 4))
        assertEquals(15.minutes, PollSchedule.expeditedDelay(attempt = 99))
    }

    @Test
    public fun `the post-pull check stops as soon as nothing is active`() {
        assertFalse(PollSchedule.shouldKeepExpediting(attempt = 0, stillActive = false))
    }

    @Test
    public fun `the post-pull check hands over to the periodic poller rather than running for ever`() {
        assertTrue(PollSchedule.shouldKeepExpediting(attempt = 0, stillActive = true))
        assertTrue(
            PollSchedule.shouldKeepExpediting(
                attempt = PollSchedule.EXPEDITED_MAX_ATTEMPTS - 2,
                stillActive = true,
            ),
        )
        assertFalse(
            "two pollers is one too many",
            PollSchedule.shouldKeepExpediting(
                attempt = PollSchedule.EXPEDITED_MAX_ATTEMPTS - 1,
                stillActive = true,
            ),
        )
    }

    @Test
    public fun `an unchanged revision is recognised and a first poll is not`() {
        assertTrue(PollSchedule.revisionUnchanged(previous = 12L, current = 12L))
        assertFalse(PollSchedule.revisionUnchanged(previous = 12L, current = 13L))
        assertFalse(
            "no previous revision is a first sighting, never a no-op",
            PollSchedule.revisionUnchanged(previous = null, current = 0L),
        )
    }
}
