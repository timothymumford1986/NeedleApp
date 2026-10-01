package app.needler.feature.player.crate

import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.QueueItem
import app.needler.feature.player.ui.PlayerFormat

/**
 * The crate, as screen 08 draws it: a Playing row and an Up next list with its count and length.
 *
 * It carries the domain's [PlayQueue] whole rather than a flattened list of rows, because the one
 * rule that matters about reordering - that the current index follows the *item*, not the slot -
 * lives on that type. Copying the items out into a UI list and tracking "which row is playing"
 * beside it is precisely how the classic reorder bug gets written.
 */
data class CrateUiState(
    val queue: PlayQueue = PlayQueue.Empty,
    /** Whether the Playing row is actually playing, which changes only its glyph. */
    val isPlaying: Boolean = false,
) {
    /** The Playing row, or null when nothing is loaded. */
    val playing: QueueItem? get() = queue.currentItem

    /** Everything after it. When nothing is loaded this is the whole crate. */
    val upNext: List<QueueItem> get() = queue.upNext

    /** True when there is nothing at all - the state the crate spends most of its life in. */
    val isEmpty: Boolean get() = queue.items.isEmpty()

    /**
     * How long Up next runs for, ignoring tracks whose length the server never reported.
     *
     * [PlayQueue.totalDurationMs] measures the whole crate including the row that is already
     * playing, and the pack's "6 tracks - 21 min" is the *rest*, so it is summed here rather than
     * read off the queue.
     */
    val upNextDurationMs: Long get() = upNext.sumOf { item -> item.track.durationMs ?: 0L }

    /** `6 tracks - 21 min`, beside the Up next heading. */
    val upNextSummary: String get() = PlayerFormat.crateSummary(upNext.size, upNextDurationMs)

    /** The same, spoken: "6 tracks, 21 minutes". */
    val spokenUpNextSummary: String
        get() = PlayerFormat.spokenCrateSummary(upNext.size, upNextDurationMs)

    /** The short form the tablet sidebar prints beside its crate heading: `4 up next`. */
    val upNextCountLabel: String get() = upNext.size.toString() + " up next"

    /**
     * True when the whole crate is one record, and every row of it knows its place in that record.
     *
     * ## What this is for
     *
     * Queue a single album and every row said the same two words. A device audit found an eleven-track
     * crate printing "The Marias - Submarine" and the same thumbnail eleven times: a second line that
     * repeats is a second line carrying no information, and TalkBack read the pair out on every row.
     *
     * Playing an album is the commonest way to fill the crate, so this is the normal case rather than an
     * edge one. [rowSubtitle] and [showsRowArtwork] are what change when it holds.
     *
     * Two conditions, both load-bearing:
     *
     *  * **More than one row.** A crate of one has nothing to repeat, and "Track 4" on its own tells a
     *    listener less than the artist and the album do.
     *  * **One `ReleaseGroupMbid` throughout**, not one album *title*. Two records can share a title,
     *    and the identity is what the rest of the app joins on.
     *
     * There is deliberately no third condition for a missing track number, which was written and then
     * removed: `TrackKey` `require`s `trackNumber >= 1` in its own init block, so a track that does not
     * know its place in the record cannot be constructed. [PlayerFormat.trackPosition] therefore always
     * has something to print, and a guard for it would have been unreachable code with an unwritable
     * test.
     */
    val isOneRecord: Boolean
        get() {
            val items: List<QueueItem> = queue.items
            if (items.size < 2) return false
            val first = items.first().track.releaseGroupMbid
            return items.all { it.track.releaseGroupMbid == first }
        }

    /**
     * True when a one-record crate spans more than one disc, so "Track 1" would appear twice.
     *
     * False for a crate that is not one record at all: the question only arises once [rowSubtitle] is
     * printing positions.
     */
    val spansDiscs: Boolean
        get() = isOneRecord && queue.items.distinctBy { it.track.key.discNumber }.size > 1

    /**
     * The second line of one row: the artist and the album, or the track's place in the record.
     *
     * Reads from [isOneRecord], which is a property of the crate and not of the row, so that eleven rows
     * cannot disagree about which form they are in - the thing that would make a crate look half-fixed.
     *
     * Never blank for a row that exists. `NeedlerQueueRow` builds its content description as
     * `"$title, $subtitle"`, so an empty string would have TalkBack say "Hamptons," and stop.
     */
    fun rowSubtitle(item: QueueItem): String = if (isOneRecord) {
        PlayerFormat.trackPosition(item, includeDisc = spansDiscs)
    } else {
        PlayerFormat.artistAndAlbum(item)
    }

    /**
     * Whether a row draws its own cover.
     *
     * One record means one cover, already drawn at full size in the player above the crate, so eleven
     * copies of it down the left-hand edge are eleven thumbnails saying what the sleeve says. Dropping
     * them also gives the title the width back, which is the half of the row that differs.
     */
    val showsRowArtwork: Boolean get() = !isOneRecord

    /**
     * Where a row of Up next sits in [PlayQueue.items].
     *
     * The screen draws two sections but the queue is one list, and every command - a move, a jump -
     * is expressed against the whole list. Returns -1 for a position that is not in Up next, which a
     * stale gesture can produce after a skip.
     */
    fun queueIndexOfUpNext(position: Int): Int {
        if (position !in upNext.indices) return -1
        val current: Int = queue.currentIndex ?: return position
        return current + 1 + position
    }

    companion object {
        /** An empty crate: what this screen draws before anything has been played. */
        val Empty: CrateUiState = CrateUiState()
    }
}
