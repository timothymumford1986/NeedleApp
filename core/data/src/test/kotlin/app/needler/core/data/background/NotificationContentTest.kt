package app.needler.core.data.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each notification says, and where tapping it goes.
 *
 * REQUIREMENTS.md pins the destinations in a table - pull finished opens the album, pull failed
 * opens Pulls, a new release opens the artist - and a notification that opens the wrong screen is a
 * small betrayal that no test on a device would catch quickly.
 */
public class NotificationContentTest {

    @Test
    public fun `a finished pull opens the album it is about`() {
        val notification = NeedlerNotification.PullFinished(
            releaseGroupMbid = "rg-1",
            albumTitle = "Spiderland",
            artistName = "Slint",
        )

        assertEquals(NotificationDestination.Album("rg-1"), notification.destination)
        assertEquals(NotificationChannelId.PULL_FINISHED, notification.channel)
        assertEquals(
            NotificationText(title = "Spiderland", body = "Slint - ready to play"),
            NotificationComposer.text(notification),
        )
    }

    @Test
    public fun `a failed pull opens the task list because the summary cannot name one`() {
        val notification = NeedlerNotification.PullFailed(failedCount = 1)

        assertEquals(NotificationDestination.Pulls, notification.destination)
        assertEquals("A pull failed", NotificationComposer.text(notification).title)

        val several = NotificationComposer.text(NeedlerNotification.PullFailed(failedCount = 3))
        assertEquals("Pulls failed", several.title)
        assertTrue(several.body.contains("3"))
    }

    @Test
    public fun `an unknown artist name degrades rather than printing null`() {
        val text = NotificationComposer.text(
            NeedlerNotification.PullFinished(
                releaseGroupMbid = "rg-1",
                albumTitle = "Spiderland",
                artistName = "",
            ),
        )

        assertEquals("Ready to play", text.body)
    }

    @Test
    public fun `notification tags are derived from content so a repeat replaces rather than stacks`() {
        val first = NeedlerNotification.PullFinished("rg-1", "Spiderland", "Slint")
        val same = NeedlerNotification.PullFinished("rg-1", "Spiderland", "Slint")
        val other = NeedlerNotification.PullFinished("rg-2", "Tweez", "Slint")

        assertEquals(first.tag, same.tag)
        assertNotEquals(first.tag, other.tag)
        assertNotEquals(first.tag, NeedlerNotification.PullFailed(1).tag)
    }

    @Test
    public fun `every destination survives the trip through intent extras`() {
        val destinations = listOf(
            NotificationDestination.Album("rg-1"),
            NotificationDestination.Artist("ar-1"),
            NotificationDestination.Pulls,
            NotificationDestination.Library,
        )

        destinations.forEach { destination ->
            val (kind, id) = NotificationDestination.encode(destination)
            assertEquals(destination, NotificationDestination.decode(kind, id))
        }
    }

    @Test
    public fun `an ordinary launch carries no destination`() {
        assertNull(
            "a launch intent that did not come from a notification must open the app normally",
            NotificationDestination.decode(kind = null, id = null),
        )
        assertNull(NotificationDestination.decode(kind = "album", id = ""))
        assertNull(NotificationDestination.decode(kind = "nonsense", id = "rg-1"))
    }

    @Test
    public fun `every notification has a channel, because a missing one is dropped silently`() {
        val channels = NotificationChannelId.entries.map { it.id }

        assertEquals("channel ids must be unique", channels.size, channels.toSet().size)
        listOf(
            NeedlerNotification.PullFinished("rg-1", "t", "a"),
            NeedlerNotification.PullFailed(1),
            NeedlerNotification.NewRelease(1),
        ).forEach { notification ->
            assertTrue(channels.contains(notification.channel.id))
        }
    }

    @Test
    public fun `download progress says tracks rather than a percentage`() {
        assertEquals(
            NotificationText(title = "Spiderland", body = "4 of 12 tracks"),
            NotificationComposer.downloadProgressText("Spiderland", 4, 12),
        )
        assertEquals(
            "a total of zero is a start, not a division by zero",
            "Starting",
            NotificationComposer.downloadProgressText("Spiderland", 0, 0).body,
        )
    }
}
