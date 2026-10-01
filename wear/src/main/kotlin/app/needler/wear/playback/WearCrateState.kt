package app.needler.wear.playback

/**
 * The crate as the watch renders it - "in the crate" is REQUIREMENTS.md "Vocabulary" for the play
 * queue, and the word "queue" appears nowhere a user can see it.
 *
 * ## Why this is not `PlayQueue`
 *
 * The same argument [WearPlaybackState] makes about `PlaybackState`, only more so. `:core:domain`'s
 * [app.needler.core.domain.model.PlayQueue] holds `QueueItem` -> `Track` -> `TrackFetchHandle`, which
 * carries stream URLs, quality badges, MBIDs, favourite flags and genres for *every row*. A watch
 * draws two strings per row. Flattening a 300-row `PlayQueue` onto a `DataMap` to send several
 * kilobytes of stream URLs across a Bluetooth link, so that a list an inch wide can ignore them, is
 * the thing this type exists to make impossible.
 *
 * ## Four states, for the reason [WearPlaybackState] has four
 *
 * [PhoneUnreachable] is kept separate from [Empty] here too. A retained data item outlives the link
 * that delivered it, so a watch that read the crate an hour ago can render a perfectly convincing
 * list of rows that nothing will happen when you tap. An empty crate and a dead link are different
 * facts and the screen says which one it has.
 *
 * ## A window, not the crate
 *
 * [InTheCrate] holds the rows the phone published, which is at most
 * [WearPlaybackProtocol.MAX_CRATE_ROWS] of them counted from the playing row.
 * [InTheCrate.notShownAfter] is how many follow them, so the screen can say so rather than implying
 * that a 300-row crate holds forty things.
 */
sealed interface WearCrateState {

    /**
     * Before the first answer: the retained crate item has not been read and the node check has not
     * come back.
     *
     * The initial value of the state holder rather than something the client emits late, so the first
     * composed frame of the crate screen is already correct.
     */
    data object Connecting : WearCrateState

    /**
     * No paired node is connected, so the rows - if there are any - cannot be acted on.
     *
     * No list is drawn in this state. A list whose rows silently do nothing is worse than no list,
     * because tapping one produces no feedback of any kind: the message is simply not delivered.
     */
    data object PhoneUnreachable : WearCrateState

    /** The phone answered and the crate is empty. */
    data object Empty : WearCrateState

    /**
     * The published window of the crate.
     *
     * @param rows in play order, beginning at the playing row wherever the crate is long enough for
     *   the window to have started there.
     * @param playingIndex index into [rows] of the row that is playing, or null when nothing is - the
     *   crate can hold rows with the session stopped, which is exactly what happens after a restore.
     * @param notShownAfter rows after this window, which the screen reports as a count. There is no
     *   count for the rows *before* it: those are the ones already played, and nobody reaches back up
     *   a crate on a watch.
     */
    data class InTheCrate(
        val rows: List<WearCrateRow>,
        val playingIndex: Int?,
        val notShownAfter: Int,
    ) : WearCrateState

    companion object {

        /**
         * One decoded crate item to one state, over primitives rather than over a `DataMap`.
         *
         * The split is what makes this testable: a `DataMap` is a Google Play services type, and the
         * arithmetic below - which row is playing, how many follow the window - is the only part with
         * anything to get wrong. [DataLayerPlaybackClient] does the key reading and hands the result
         * here.
         *
         * Every input is treated as untrustworthy, because the phone that sent it is a separately
         * installed APK with its own version code. An index outside the rows means "nothing is
         * playing" rather than an exception, a negative count clamps to zero, and a [total] smaller
         * than the window it was sent with clamps the same way. A crate that renders slightly wrong
         * against a mismatched phone build is a far better outcome than a watch app that crashes
         * whenever the two disagree.
         *
         * @param currentIndex an index into [rows], not into the crate. See
         *   [WearPlaybackProtocol.KEY_CRATE_CURRENT] for why that distinction is the one thing on
         *   this item worth being careful about.
         * @param windowStart how far into the crate [rows] begins. Used only to work out what follows
         *   the window; nothing renders it.
         */
        fun of(
            rows: List<WearCrateRow>,
            currentIndex: Int,
            windowStart: Int,
            total: Int,
        ): WearCrateState {
            if (rows.isEmpty()) return Empty
            val start: Int = windowStart.coerceAtLeast(0)
            return InTheCrate(
                rows = rows,
                playingIndex = currentIndex.takeIf { it in rows.indices },
                notShownAfter = (total - start - rows.size).coerceAtLeast(0),
            )
        }
    }
}

/**
 * One row of the crate, as the watch draws it: two strings and the handle to act on.
 *
 * [id] is `QueueItem.id` - a row identifier, not a track identity, because the crate can hold the same
 * track twice and [WearPlaybackProtocol.PATH_SKIP_TO_ROW] has to name the row the user actually
 * tapped.
 */
data class WearCrateRow(
    val id: String,
    val title: String,
    val artist: String,
)
