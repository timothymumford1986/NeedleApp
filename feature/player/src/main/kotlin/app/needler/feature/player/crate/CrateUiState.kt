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
