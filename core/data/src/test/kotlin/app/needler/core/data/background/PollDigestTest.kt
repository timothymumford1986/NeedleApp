package app.needler.core.data.background

import app.needler.core.data.settings.NotificationSettings
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.ReleaseGroupMbid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a poll concludes, and - more importantly - what it refuses to conclude twice.
 *
 * Three failures live here and none of them would be visible on a device until a user complained.
 * Announcing the same landed album on every poll; announcing an old failure for ever because the
 * count is non-zero rather than rising; and doing a full task-list refresh on a revision that never
 * moved, which is the one thing that makes a fifteen-minute poller affordable.
 */
public class PollDigestTest {

    private val allOn = NotificationSettings()

    private fun summary(
        revision: Long,
        landed: List<String> = emptyList(),
        failed: Int = 0,
        active: Int = 0,
    ) = PullActivitySummary(
        revision = revision,
        activeCount = active,
        heldCount = 0,
        failedCount = failed,
        landedReleaseGroupMbids = landed.map(::ReleaseGroupMbid),
    )

    @Test
    public fun `an unchanged revision ends the run and touches nothing`() {
        val previous = PollMemory(lastRevision = 7L, lastFailedCount = 3)

        val result = PollDigest.digest(
            previous = previous,
            summary = summary(revision = 7L, landed = listOf("rg-1"), failed = 9),
            settings = allOn,
        )

        assertTrue(result.revisionUnchanged)
        assertTrue("no notification may be built from a revision that did not move", result.notifications.isEmpty())
        assertTrue(result.landedMbids.isEmpty())
        assertEquals("the memory must not move either", previous, result.memory)
    }

    @Test
    public fun `the first ever poll records the backlog without announcing it`() {
        // A fresh sign-in has a whole queue of history behind it. Twelve notifications about music
        // that arrived last week is not news, it is an apology.
        val result = PollDigest.digest(
            previous = PollMemory.Empty,
            summary = summary(revision = 4L, landed = listOf("rg-1", "rg-2"), failed = 5),
            settings = allOn,
        )

        assertFalse(result.revisionUnchanged)
        assertTrue("a first poll is silent", result.notifications.isEmpty())
        assertEquals(setOf("rg-1", "rg-2"), result.memory.announcedLandedMbids)
        assertEquals(5, result.memory.lastFailedCount)
    }

    @Test
    public fun `a landed album is announced once and never again`() {
        val first = PollDigest.digest(
            previous = PollMemory(lastRevision = 1L),
            summary = summary(revision = 2L, landed = listOf("rg-1")),
            settings = allOn,
            albumLookup = { AlbumHeadline(title = "Spiderland", artistName = "Slint") },
        )

        assertEquals(1, first.notifications.size)
        val announced = first.notifications.single() as NeedlerNotification.PullFinished
        assertEquals("rg-1", announced.releaseGroupMbid)
        assertEquals("Spiderland", announced.albumTitle)

        // The server keeps reporting a landed album for a while, so the same list comes back on the
        // next poll with a new revision. It must not produce a second notification.
        val second = PollDigest.digest(
            previous = first.memory,
            summary = summary(revision = 3L, landed = listOf("rg-1")),
            settings = allOn,
        )

        assertTrue(second.notifications.isEmpty())
    }

    @Test
    public fun `a failure notification fires on the count rising, not on it being non-zero`() {
        val unchanged = PollDigest.digest(
            previous = PollMemory(lastRevision = 1L, lastFailedCount = 2),
            summary = summary(revision = 2L, failed = 2),
            settings = allOn,
        )
        assertTrue(
            "one old failure in the queue must not be announced every six hours for ever",
            unchanged.notifications.isEmpty(),
        )

        val risen = PollDigest.digest(
            previous = PollMemory(lastRevision = 1L, lastFailedCount = 2),
            summary = summary(revision = 2L, failed = 3),
            settings = allOn,
        )
        assertEquals(
            NeedlerNotification.PullFailed(failedCount = 3),
            risen.notifications.single(),
        )
    }

    @Test
    public fun `each switch silences only its own notification`() {
        val onlyFinished = NotificationSettings(pullFinished = true, pullFailed = false)

        val result = PollDigest.digest(
            previous = PollMemory(lastRevision = 1L, lastFailedCount = 0),
            summary = summary(revision = 2L, landed = listOf("rg-1"), failed = 4),
            settings = onlyFinished,
        )

        assertEquals(1, result.notifications.size)
        assertTrue(result.notifications.single() is NeedlerNotification.PullFinished)
        assertEquals(
            "the failure count is still recorded, or turning the switch back on would announce history",
            4,
            result.memory.lastFailedCount,
        )
    }

    @Test
    public fun `a missing album row degrades to a notification with no title rather than none at all`() {
        val result = PollDigest.digest(
            previous = PollMemory(lastRevision = 1L),
            summary = summary(revision = 2L, landed = listOf("rg-unknown")),
            settings = allOn,
            albumLookup = { null },
        )

        val announced = result.notifications.single() as NeedlerNotification.PullFinished
        assertEquals("", announced.albumTitle)
        assertEquals(
            "the wording has to survive an empty title",
            "Your pull has arrived",
            NotificationComposer.text(announced).title,
        )
    }

    @Test
    public fun `the announced set is capped so it cannot grow for ever`() {
        var memory = PollMemory(lastRevision = 0L)
        repeat(30) { round ->
            memory = PollDigest.digest(
                previous = memory,
                summary = summary(
                    revision = (round + 1).toLong(),
                    landed = List(20) { index -> "rg-" + round + "-" + index },
                ),
                settings = allOn,
            ).memory
        }

        assertTrue(
            "600 landed albums must not all be written back to disk on every poll",
            memory.announcedLandedMbids.size <= 200,
        )
    }

    @Test
    public fun `the new-release notification fires on a rising unseen count`() {
        val (none, memoryAfterNone) = PollDigest.newReleaseNotification(
            previous = PollMemory(lastUnseenNewReleaseCount = 3),
            unseenCount = 3,
            settings = allOn,
        )
        assertNull("a badge the user has not cleared must not re-announce itself", none)
        assertEquals(3, memoryAfterNone.lastUnseenNewReleaseCount)

        val (fired, _) = PollDigest.newReleaseNotification(
            previous = PollMemory(lastUnseenNewReleaseCount = 3),
            unseenCount = 4,
            settings = allOn,
        )
        assertEquals(4, (fired as NeedlerNotification.NewRelease).unseenCount)
    }

    @Test
    public fun `a single unseen release opens its artist and several do not`() {
        val (single, _) = PollDigest.newReleaseNotification(
            previous = PollMemory.Empty,
            unseenCount = 1,
            settings = allOn,
            artistMbid = "ar-1",
            artistName = "Slint",
            albumTitle = "Spiderland",
        )
        assertEquals(
            NotificationDestination.Artist("ar-1"),
            requireNotNull(single).destination,
        )

        val (several, _) = PollDigest.newReleaseNotification(
            previous = PollMemory.Empty,
            unseenCount = 4,
            settings = allOn,
            artistMbid = "ar-1",
            artistName = "Slint",
        )
        assertEquals(
            "with four unseen there is no single artist to open",
            NotificationDestination.Library,
            requireNotNull(several).destination,
        )
    }

    @Test
    public fun `the new-release switch being off produces nothing but still records the count`() {
        val (notification, memory) = PollDigest.newReleaseNotification(
            previous = PollMemory(lastUnseenNewReleaseCount = 0),
            unseenCount = 9,
            settings = NotificationSettings(newReleaseFromFollowedArtist = false),
        )

        assertNull(notification)
        assertEquals(
            "or turning the switch on later would announce a backlog",
            9,
            memory.lastUnseenNewReleaseCount,
        )
    }
}
