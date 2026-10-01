package app.needler.wear

import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.playback.PlaybackState

/**
 * Domain playback to the two things the watch is sent, with no Google Play services in sight.
 *
 * ## Why this is a separate, pure step
 *
 * [WearStatePublisher] cannot be unit-tested: it holds a `DataClient`, which needs Google Play
 * services, a paired node and a device. Everything in this file can be, and everything with a
 * decision in it lives here rather than there - which rows go on the wire, which index is playing,
 * what happens to a crate longer than the window. The publisher is left with key names and a
 * `putDataItem` call.
 *
 * It is the same split `:player:service` makes with `PlaybackStateMapper` beside
 * `Media3PlaybackController`, and it is worth repeating here because the arithmetic below is the part
 * that can be quietly wrong: a window index used as a crate index plays the wrong song, and it looks
 * like a Bluetooth glitch rather than like an off-by-one.
 */
internal object WearSnapshots {

    /**
     * The now-playing item, from the session state every other Needler surface reads.
     *
     * Position is not here, and will not be: see the protocol's "What is deliberately not on the
     * wire". `PlaybackState.error` is not here either, which is a decision rather than an oversight -
     * a watch cannot act on "the server's transcode slots are gone", the phone already surfaces it to
     * every client of the session, and a four-line error on a watch face in place of a transport is
     * worse than a transport that briefly does nothing.
     *
     * The artwork identifier is the release-group MBID, per the protocol: every track on an album
     * shares it, so the watch fetches one cover per album instead of one per track. The cost of that
     * choice is that a cover the server *replaces* for an album already on the wire keeps its old id,
     * and a watch holding a decoded copy keeps showing it until its process restarts. That is a stale
     * thumbnail on a watch, against one Bluetooth asset transfer per track; the thumbnail loses.
     */
    fun nowPlaying(state: PlaybackState): WearNowPlaying {
        val row: QueueItem = state.currentItem ?: return WearNowPlaying.NothingLoaded
        val ref: ArtworkRef? = row.track.artwork
        return WearNowPlaying(
            hasItem = true,
            title = row.track.title,
            artist = row.track.artistName,
            album = row.track.albumTitle?.takeIf { it.isNotBlank() },
            isPlaying = state.isPlaying,
            isBuffering = state.isBuffering,
            // The id goes with the reference or not at all. An id on its own would be sent to a watch
            // that then caches a decoded cover under it, having never received one.
            artworkId = ref?.let { row.track.releaseGroupMbid.value },
            artwork = ref,
        )
    }

    /**
     * The window of the crate that goes on the wire: at most [maxRows] rows, starting at the playing
     * row.
     *
     * Three decisions, and the first is the one to be careful with.
     *
     * **[WearCrateWindow.currentIndexInWindow] is an index into the published rows**, not into the
     * crate. It happens to be 0 whenever anything is playing, because the window starts at the playing
     * row - and it is still sent explicitly, because the watch must not encode that rule. If the
     * window ever grows a row or two of history, the watch that reads the key keeps working and the
     * watch that assumed zero highlights the wrong row and skips to the wrong track.
     *
     * **The window starts at the playing row, not the top of the crate.** The crate screen on a watch
     * answers "what is next"; the rows already played are the ones nobody scrolls back up to on a
     * screen an inch across. It also means a 300-row crate whose listener is two hundred rows down
     * still sends something useful.
     *
     * **Nothing playing sends the top of the crate.** That is the state a restored crate is in before
     * the first play: rows exist and none of them is current. Sending nothing would render as an
     * empty crate, which is a lie about a crate that has rows in it.
     */
    fun crateWindow(
        queue: PlayQueue,
        maxRows: Int = WearPlaybackProtocol.MAX_CRATE_ROWS,
    ): WearCrateWindow {
        val items: List<QueueItem> = queue.items
        if (items.isEmpty()) return WearCrateWindow.EmptyCrate
        val playing: Int? = queue.currentIndex?.takeIf { it in items.indices }
        val start: Int = playing ?: 0
        val end: Int = minOf(items.size, start + maxRows.coerceAtLeast(0))
        val rows: List<WearCrateRowSnapshot> = items.subList(start, end).map { item ->
            WearCrateRowSnapshot(
                id = item.id,
                title = item.track.title,
                artist = item.track.artistName,
            )
        }
        return WearCrateWindow(
            rows = rows,
            currentIndexInWindow = if (playing == null || rows.isEmpty()) {
                WearPlaybackProtocol.NO_CURRENT_ROW
            } else {
                0
            },
            windowStart = start,
            // The whole crate, not the window: this is the only number that says how much the watch
            // is not being shown.
            total = items.size,
        )
    }
}

/**
 * The now-playing item, flattened to what a `DataMap` can hold, plus the one thing it cannot.
 *
 * [artwork] is the domain reference, not the bytes: turning it into an `Asset` needs the network and
 * belongs to [WearArtworkAssets]. Keeping it here rather than resolving it during the mapping is what
 * lets this whole type be built and asserted in a unit test.
 */
internal data class WearNowPlaying(
    val hasItem: Boolean,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val artworkId: String? = null,
    val artwork: ArtworkRef? = null,
) {
    internal companion object {
        /**
         * The session has nothing loaded.
         *
         * Published as `hasItem = false` rather than by deleting the data item. Both render as
         * `Idle` on the watch, and a publish keeps one code path where a delete would add a second -
         * one that also has to decide what to do about the crate item, which may still have rows.
         */
        val NothingLoaded: WearNowPlaying = WearNowPlaying(hasItem = false)
    }
}

/** One crate row on the wire: the id to act on and the two strings a watch draws. */
internal data class WearCrateRowSnapshot(
    val id: String,
    val title: String,
    val artist: String,
)

/**
 * The published window of the crate.
 *
 * @param rows in play order, beginning [windowStart] rows into the crate.
 * @param currentIndexInWindow index into [rows], or [WearPlaybackProtocol.NO_CURRENT_ROW].
 * @param windowStart how far into the crate [rows] begins; the watch needs it to count what follows.
 * @param total rows in the whole crate.
 */
internal data class WearCrateWindow(
    val rows: List<WearCrateRowSnapshot>,
    val currentIndexInWindow: Int,
    val windowStart: Int,
    val total: Int,
) {
    internal companion object {
        /** An empty crate: no rows, nothing current, nothing beyond. */
        val EmptyCrate: WearCrateWindow = WearCrateWindow(
            rows = emptyList(),
            currentIndexInWindow = WearPlaybackProtocol.NO_CURRENT_ROW,
            windowStart = 0,
            total = 0,
        )
    }
}
