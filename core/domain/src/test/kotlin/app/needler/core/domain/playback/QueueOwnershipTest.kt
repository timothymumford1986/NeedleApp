package app.needler.core.domain.playback

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.repository.PlaybackSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who owns the crate, pinned as a test because the answer is a decision rather than a consequence.
 *
 * [PlaybackController] owns the **live** queue: it is the session's queue, and the session is what
 * is actually playing. [PlaybackSettingsRepository] owns the **persisted** queue, which exists so
 * the crate survives a restart. Both existed independently before this was settled, each with its
 * own `enqueue`, `moveQueueItem` and `removeQueueItem`, and two owners of one queue is not a tidiness
 * problem - it is a race whose symptom is the crate rearranging itself under the user's finger while
 * a reorder saved in one place is overwritten by the other a tick later.
 *
 * So the rule is: every mutation goes through the controller, and the persisted copy is written by
 * the player service alone, as a whole queue. These tests fail if either half grows the other's
 * members back.
 */
public class QueueOwnershipTest {

    private fun methodNames(type: Class<*>): Set<String> = type.methods.map { it.name }.toSet()

    @Test
    public fun `the repository has no queue-mutating members`() {
        val members: Set<String> = methodNames(PlaybackSettingsRepository::class.java)

        listOf("enqueue", "moveQueueItem", "removeQueueItem", "clearQueue", "skipToQueueItem")
            .forEach { name ->
                assertTrue(
                    "PlaybackSettingsRepository must not own " + name +
                        ": editing the crate is the live session's business",
                    name !in members,
                )
            }
    }

    @Test
    public fun `the repository persists whole queues and nothing finer`() {
        val members: Set<String> = methodNames(PlaybackSettingsRepository::class.java)

        // Named "persisted" deliberately. A plain observeQueue/saveQueue pair reads as the queue
        // itself, which is how the duplication happened in the first place.
        assertTrue("observePersistedQueue" in members)
        assertTrue("restorePersistedQueue" in members)
        assertTrue("savePersistedQueue" in members)
        assertTrue("observeQueue" !in members)
        assertTrue("saveQueue" !in members)
    }

    @Test
    public fun `the controller owns every edit to the live crate`() {
        val members: Set<String> = methodNames(PlaybackController::class.java)

        listOf(
            "observeQueue",
            "enqueue",
            "moveQueueItem",
            "removeQueueItem",
            "clearQueue",
            "skipToQueueItem",
        ).forEach { name -> assertTrue("PlaybackController must own " + name, name in members) }
    }

    /**
     * The shape the ownership split takes at runtime: edits are applied to the live crate with the
     * domain's own transforms, and the result is handed to the repository as a whole queue.
     *
     * The assertion that matters is the last one - the persisted copy is never a *different* queue
     * from the live one, only an older one. That is what makes a restart resume where the user left
     * off instead of somewhere they have never been.
     */
    @Test
    public fun `edits flow through the live crate and are persisted whole`() {
        var live: PlayQueue = PlayQueue(items = listOf(item("a"), item("b"), item("c")), currentIndex = 1)
        val saves: MutableList<PlayQueue> = mutableListOf()
        val persist: (PlayQueue) -> Unit = { queue -> saves.add(queue) }

        // A reorder, a removal and an append, each applied live and then persisted once.
        live = live.withItemMoved(fromIndex = 2, toIndex = 0).also(persist)
        live = live.withItemRemoved("a").also(persist)
        live = live.withItemsInserted(listOf(item("d"))).also(persist)

        assertEquals(listOf("c", "b", "d"), live.items.map { it.id })
        // The playing row never changed hands, through all three edits.
        assertEquals("b", live.currentItem?.id)
        assertEquals(3, saves.size)
        assertEquals(live, saves.last())
    }

    private fun item(id: String): QueueItem = QueueItem(
        id = id,
        track = Track(
            key = TrackKey(ReleaseGroupMbid("rg-1"), discNumber = 1, trackNumber = id.first().code),
            title = "Track " + id,
            artistName = "Artist",
            fetch = TrackFetchHandle(
                fileId = FileId("file-" + id),
                sizeBytes = null,
                durationMs = null,
                format = AudioFormat.FLAC,
                bitrateKbps = null,
            ),
        ),
    )
}
