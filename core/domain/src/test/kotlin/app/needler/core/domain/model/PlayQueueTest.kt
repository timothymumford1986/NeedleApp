package app.needler.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The crate's edit operations - the transforms every queue mutation goes through.
 *
 * They live on [PlayQueue] rather than inside the player service because the index arithmetic is
 * where reorder bugs come from, and a bug here is invisible in the worst way: the list looks right
 * and the wrong track plays. One implementation, tested once, is what stops the controller, the
 * persistence layer and any future surface from each doing their own version of it.
 */
public class PlayQueueTest {

    private fun track(number: Int): Track = Track(
        key = TrackKey(ReleaseGroupMbid("rg-1"), discNumber = 1, trackNumber = number),
        title = "Track " + number,
        artistName = "Artist",
        durationMs = 1_000L * number,
        fetch = TrackFetchHandle(
            fileId = FileId("file-" + number),
            sizeBytes = 1_000L,
            durationMs = 1_000L * number,
            format = AudioFormat.FLAC,
            bitrateKbps = null,
        ),
    )

    private fun item(id: String, number: Int = id.last().digitToInt()): QueueItem =
        QueueItem(id = id, track = track(number))

    private fun queue(currentIndex: Int?, vararg ids: String): PlayQueue =
        PlayQueue(items = ids.map { item(it) }, currentIndex = currentIndex)

    private val PlayQueue.ids: List<String> get() = items.map { it.id }

    // ---------------------------------------------------------------- inserting

    @Test
    public fun `appending leaves the playing row where it was`() {
        val crate: PlayQueue = queue(1, "a1", "a2", "a3")

        val updated: PlayQueue = crate.withItemsInserted(listOf(item("a4")))

        assertEquals(listOf("a1", "a2", "a3", "a4"), updated.ids)
        assertEquals("a2", updated.currentItem?.id)
    }

    @Test
    public fun `inserting above the playing row moves the index with the item`() {
        // "Play next" on a row before the current one, or a restore racing a skip. The index is a
        // position, so anything inserted above it has to push it down or the wrong row is playing.
        val crate: PlayQueue = queue(2, "a1", "a2", "a3")

        val updated: PlayQueue = crate.withItemsInserted(listOf(item("a4"), item("a5")), index = 0)

        assertEquals(listOf("a4", "a5", "a1", "a2", "a3"), updated.ids)
        assertEquals("a3", updated.currentItem?.id)
    }

    @Test
    public fun `play next inserts directly after the playing row and does not disturb it`() {
        val crate: PlayQueue = queue(0, "a1", "a2")

        val updated: PlayQueue = crate.withItemsInserted(listOf(item("a9")), index = 1)

        assertEquals(listOf("a1", "a9", "a2"), updated.ids)
        assertEquals("a1", updated.currentItem?.id)
    }

    @Test
    public fun `an out-of-range insert position is clamped rather than dropping the tracks`() {
        // The crate screen can issue an insert against a position a concurrent skip has moved past.
        // Losing the user's tracks, or throwing inside the player session, are both worse answers.
        val crate: PlayQueue = queue(0, "a1")

        assertEquals(listOf("a1", "a7"), crate.withItemsInserted(listOf(item("a7")), index = 99).ids)
        assertEquals(listOf("a7", "a1"), crate.withItemsInserted(listOf(item("a7")), index = -5).ids)
    }

    @Test
    public fun `inserting nothing returns the same crate`() {
        val crate: PlayQueue = queue(0, "a1")

        assertSame(crate, crate.withItemsInserted(emptyList()))
    }

    // ---------------------------------------------------------------- moving

    @Test
    public fun `dragging a row past the playing row does not change what is playing`() {
        // The classic reorder bug: recompute the current index from the indices and the drag
        // silently restarts playback on a different track. The index follows the *item*.
        val crate: PlayQueue = queue(1, "a1", "a2", "a3")

        val updated: PlayQueue = crate.withItemMoved(fromIndex = 2, toIndex = 0)

        assertEquals(listOf("a3", "a1", "a2"), updated.ids)
        assertEquals("a2", updated.currentItem?.id)
        assertEquals(2, updated.currentIndex)
    }

    @Test
    public fun `dragging the playing row itself carries the index along`() {
        val crate: PlayQueue = queue(0, "a1", "a2", "a3")

        val updated: PlayQueue = crate.withItemMoved(fromIndex = 0, toIndex = 2)

        assertEquals(listOf("a2", "a3", "a1"), updated.ids)
        assertEquals("a1", updated.currentItem?.id)
        assertEquals(2, updated.currentIndex)
    }

    @Test
    public fun `an out-of-range or no-op move returns the crate untouched`() {
        val crate: PlayQueue = queue(0, "a1", "a2")

        assertSame(crate, crate.withItemMoved(fromIndex = 0, toIndex = 0))
        assertSame(crate, crate.withItemMoved(fromIndex = 5, toIndex = 0))
        assertSame(crate, crate.withItemMoved(fromIndex = 0, toIndex = -1))
    }

    // ---------------------------------------------------------------- removing

    @Test
    public fun `removing a row above the playing one pulls the index up with it`() {
        val crate: PlayQueue = queue(2, "a1", "a2", "a3")

        val updated: PlayQueue = crate.withItemRemoved("a1")

        assertEquals(listOf("a2", "a3"), updated.ids)
        assertEquals("a3", updated.currentItem?.id)
    }

    @Test
    public fun `removing the playing row leaves the track that followed it current`() {
        val crate: PlayQueue = queue(1, "a1", "a2", "a3")

        val updated: PlayQueue = crate.withItemRemoved("a2")

        assertEquals(listOf("a1", "a3"), updated.ids)
        assertEquals("a3", updated.currentItem?.id)
    }

    @Test
    public fun `removing the last row while it plays leaves nothing current`() {
        // Nothing follows it, so the session stops rather than wrapping round to the top - which is
        // what an index left pointing past the end would eventually be read as.
        val crate: PlayQueue = queue(1, "a1", "a2")

        val updated: PlayQueue = crate.withItemRemoved("a2")

        assertEquals(listOf("a1"), updated.ids)
        assertNull(updated.currentIndex)
        assertNull(updated.currentItem)
    }

    @Test
    public fun `removing one of two rows for the same track removes only that row`() {
        // QueueItem.id is a queue-local row handle precisely so the same track can sit in the crate
        // twice; keying the removal on the track would empty both.
        val duplicated: QueueItem = item("a2").copy(id = "b2")
        val crate = PlayQueue(items = listOf(item("a1"), item("a2"), duplicated), currentIndex = 0)

        val updated: PlayQueue = crate.withItemRemoved("a2")

        assertEquals(listOf("a1", "b2"), updated.ids)
        assertEquals("a1", updated.currentItem?.id)
    }

    @Test
    public fun `removing an unknown row changes nothing`() {
        val crate: PlayQueue = queue(0, "a1")

        assertSame(crate, crate.withItemRemoved("nope"))
    }

    @Test
    public fun `clearing empties the crate and leaves nothing current`() {
        val cleared: PlayQueue = queue(1, "a1", "a2").cleared()

        assertEquals(emptyList<String>(), cleared.ids)
        assertNull(cleared.currentIndex)
        assertEquals(0L, cleared.totalDurationMs)
    }

    @Test
    public fun `up next is everything after the playing row`() {
        assertEquals(listOf("a2", "a3"), queue(0, "a1", "a2", "a3").upNext.map { it.id })
        assertEquals(emptyList<String>(), queue(2, "a1", "a2", "a3").upNext.map { it.id })
    }
}
