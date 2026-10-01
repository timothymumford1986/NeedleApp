package app.needler.player.service.media

import app.needler.core.domain.model.TrackKey
import java.util.concurrent.atomic.AtomicLong

/**
 * The crate row ids the session mints for items that arrive without one.
 *
 * An item added by the app's own controller already carries a row id, because `QueueBuilder` in
 * `:player:service`'s controller half minted one when it built the request. An item added by Android Auto,
 * by Wear, by a voice command or by the browse tree does not: it carries a browse id or a bare track id, and
 * one browse selection can expand into a whole record. Those need row ids, or the crate holds several rows
 * that answer to the same id and `removeQueueItem` takes away the wrong one - the exact fault
 * [MediaId.forQueueRow] exists to prevent.
 *
 * ## Why the sequences are negative
 *
 * There are two counters in one session and they cannot see each other: `QueueBuilder` runs in the app's
 * process and hands out 1, 2, 3 as the user builds a queue, and this one runs inside the service. Both
 * starting at 1 would collide the moment the same track was added from both sides - row `1@<key>` twice, which
 * is precisely the duplicate this id scheme is designed to make impossible. Counting **down** from -1 gives the
 * session its own half of the number line, with no shared state, no coordination and no chance of overlap.
 * [MediaId.rowSequenceOf] parses a negative sequence like any other.
 */
public class SessionRowIds {

    private val nextSequence = AtomicLong(-1L)

    /**
     * The row id for [key] as it is enqueued.
     *
     * [incomingMediaId] is kept when it is already a row id for this very track, so that a queue the app
     * built keeps the ids the app is holding; anything else - a browse id, a bare track id, an id for some
     * other track - gets a fresh one.
     */
    public fun rowIdFor(incomingMediaId: String?, key: TrackKey): String {
        val alreadyARow: Boolean = MediaId.rowSequenceOf(incomingMediaId) != null &&
            MediaId.toTrackKey(incomingMediaId) == key
        if (alreadyARow && incomingMediaId != null) return incomingMediaId
        return MediaId.forQueueRow(key, nextSequence.getAndDecrement())
    }
}
