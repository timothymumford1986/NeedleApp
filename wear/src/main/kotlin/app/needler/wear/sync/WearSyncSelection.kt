package app.needler.wear.sync

import app.needler.wear.playback.WearPlaybackProtocol
import app.needler.wear.store.WearAudioKey

/**
 * What the user has asked to have on the watch, and how the watch turns that into one request.
 *
 * Everything in this file is pure, which is the point: the sync policy's arithmetic - which album the
 * pipeline works on, what happens when the phone stops offering an album, how the staleness check
 * eventually reaches an album that is already complete - is the part that can be quietly wrong, and it
 * is all assertable in a JVM unit test. [WearSyncCoordinator] is left with Google Play services.
 *
 * ## Why the choosing happens on the watch
 *
 * REQUIREMENTS.md requires that "the user must be able to see and change what is on it", and gives Wear
 * no drawn screen at all. There were two places to put that control:
 *
 *  * **The phone's Settings screen.** Screen 12's Storage section already lists every downloaded album
 *    by size with a remove control beside each, and a "keep on watch" control belongs there. It is the
 *    better long-term home, and it is the right place for a library too long for
 *    [WearPlaybackProtocol.MAX_OFFER_ALBUMS] to carry.
 *  * **The watch.** Which is where this lands, because the person deciding what to take on a run is
 *    wearing it, and because a watch that can only be loaded from a phone screen is a watch that
 *    cannot be loaded at all when the phone is in another room.
 *
 * Both, eventually. The watch is the half that works with nothing else built.
 */
data class WearSyncSelection(
    /**
     * Release-group MBIDs the user wants on the watch, in the order they asked for them.
     *
     * Order is priority: the pipeline fills the first incomplete album before it starts the next. That
     * matters on a link this slow - a user who selects three albums and leaves the house gets one whole
     * album rather than three thirds, and one whole album is the one that is worth listening to.
     */
    val wantedAlbums: List<String>,
) {

    val isEmpty: Boolean get() = wantedAlbums.isEmpty()

    fun contains(albumKey: String): Boolean = albumKey in wantedAlbums

    /**
     * Adds an album, at the end, if there is room in the pipeline.
     *
     * Appended rather than promoted, so selecting a fourth album does not push the one that is halfway
     * transferred down the queue. Over [WearPlaybackProtocol.MAX_WANTED_ALBUMS] the selection is
     * returned unchanged, which the screen reports rather than silently dropping the tap.
     */
    fun with(albumKey: String): WearSyncSelection {
        if (!WearAudioKey.isSafeIdentifier(albumKey)) return this
        if (contains(albumKey)) return this
        if (wantedAlbums.size >= WearPlaybackProtocol.MAX_WANTED_ALBUMS) return this
        return WearSyncSelection(wantedAlbums = wantedAlbums + albumKey)
    }

    /**
     * Removes an album from the wanted list.
     *
     * This is not the same action as removing its bytes, and keeping them separate is deliberate:
     * un-wanting an album whose transfer has not finished should stop the transfer without throwing
     * away the tracks that already crossed the link, because they are perfectly good music and they
     * cost minutes to fetch. The on-watch screen offers both, and says which is which.
     */
    fun without(albumKey: String): WearSyncSelection =
        WearSyncSelection(wantedAlbums = wantedAlbums.filter { wanted -> wanted != albumKey })

    /** The list as it goes on the wire, bounded and de-duplicated. */
    fun forWire(): ArrayList<String> = ArrayList(
        wantedAlbums.distinct().take(WearPlaybackProtocol.MAX_WANTED_ALBUMS),
    )

    /** One album key per line, for the file the watch persists this in. See [decode]. */
    fun encode(): String = wantedAlbums.joinToString(separator = "\n", postfix = "\n")

    companion object {

        /** Nothing selected: the state a watch is in before the user asks for anything. */
        val Empty: WearSyncSelection = WearSyncSelection(wantedAlbums = emptyList())

        /**
         * Reads the persisted selection.
         *
         * One album key per line and nothing else, because that is all there is to store and a
         * line-per-key file cannot be half-parsed into a different meaning. Unsafe or duplicate keys are
         * dropped rather than rejecting the whole file: the user's other selections are not forfeit
         * because one line was damaged.
         *
         * The selection is persisted here rather than relied upon from the retained data item it is also
         * published as. The retained item is the phone's copy; this file is the watch's own, and it is
         * what survives Google Play services clearing its data layer - which would otherwise silently
         * un-want every album the user had asked for.
         */
        fun decode(text: String?): WearSyncSelection {
            if (text.isNullOrBlank()) return Empty
            val keys: List<String> = text.lineSequence()
                .map { line -> line.trim() }
                .filter { line -> line.isNotEmpty() && WearAudioKey.isSafeIdentifier(line) }
                .distinct()
                .take(WearPlaybackProtocol.MAX_WANTED_ALBUMS)
                .toList()
            return WearSyncSelection(wantedAlbums = keys)
        }
    }
}

/** One album the phone says it could put on the watch. */
data class WearOfferedAlbum(
    val albumKey: String,
    val title: String,
    val artist: String,
    /**
     * How many tracks of this album are downloaded **on the phone**, not how many the album has.
     *
     * REQUIREMENTS.md "Partial content is a normal state" is why the difference matters: a
     * part-delivered pull leaves an album the phone can never complete, and a watch that compared its
     * holdings against the album's true length would ask for a track that exists nowhere, for ever.
     */
    val trackCount: Int,
    /** Bytes on the phone's disk for it, so the watch can check its room before it asks. */
    val sizeBytes: Long,
)

/**
 * The phone's offer, as the watch sees it.
 *
 * Three states rather than a nullable list, and the reason is the one
 * [app.needler.wear.playback.WearCrateState] gives: a retained data item outlives the connection that
 * delivered it, so a list of albums read an hour ago renders a perfectly convincing picker whose every
 * tap would do nothing. [PhoneUnreachable] is a fact to show, not a variety of empty.
 */
sealed interface WearOfferState {

    /** Before the first answer: the retained item has not been read and no node check has returned. */
    data object Loading : WearOfferState

    /** No paired node is connected, so nothing can be added however many albums are cached. */
    data object PhoneUnreachable : WearOfferState

    /**
     * The phone answered. [albums] may be empty, which means the phone has nothing downloaded - a
     * complete, honest state, and a different one from not having asked.
     *
     * @param notShown downloaded albums the phone did not list. See
     *   [WearPlaybackProtocol.MAX_OFFER_ALBUMS].
     */
    data class Offered(
        val albums: List<WearOfferedAlbum>,
        val notShown: Int,
    ) : WearOfferState {
        fun album(albumKey: String): WearOfferedAlbum? =
            albums.firstOrNull { album -> album.albumKey == albumKey }

        companion object {
            /** The phone has nothing downloaded to offer. */
            val Nothing: Offered = Offered(albums = emptyList(), notShown = 0)
        }
    }
}

/**
 * Which album the watch asks the phone to work on next.
 *
 * ## One album at a time, and why
 *
 * [WearPlaybackProtocol.KEY_ACTIVE_ALBUM] records the wire argument: reporting the watch's entire
 * holdings so the phone could plan across the whole selection would grow the selection item with the
 * user's library, against a 100 KB ceiling, and an item that one day silently fails to publish is the
 * worst failure this pipeline could have. Bounding the report to one album bounds it for ever.
 *
 * ## Rotation, and what it is for
 *
 * When every wanted album is as full as the phone can make it there is still something to do: check
 * whether any of them has been replaced on the server. REQUIREMENTS.md "Invalidating upgraded files"
 * makes that mandatory - "DroppedNeedle upgrades files in place when a better source appears, so cached
 * audio can silently become the older, worse copy" - and only the phone can perform the comparison, so
 * the watch has to keep offering it albums to compare.
 *
 * It does that by rotating: one album per sync session, where a session is the on-watch screen opening
 * or the user asking to sync. A user who opens that screen a handful of times has had their whole
 * selection checked, and nothing anywhere runs on a timer. A counter that resets when the process dies
 * simply starts the rotation at the first album again, which costs nothing.
 */
object WearActiveAlbum {

    /**
     * The album the watch wants bytes for, or an empty string for none.
     *
     * @param wanted the user's selection, in priority order.
     * @param offeredTrackCounts how many tracks of each album the phone says it has. An album absent
     *   from this map is one the phone is not offering - unpinned since the watch asked, or off the end
     *   of the offer - and it is skipped rather than chosen: the phone could do nothing with it, and
     *   choosing it would stall the pipeline on an album that will never fill.
     * @param heldCounts how many tracks of each album the watch holds.
     * @param rotation a counter the caller advances once per sync session. Only consulted when every
     *   wanted album is already full.
     */
    fun of(
        wanted: List<String>,
        offeredTrackCounts: Map<String, Int>,
        heldCounts: Map<String, Int>,
        rotation: Int,
    ): String {
        if (wanted.isEmpty()) return ""

        val fillable: List<String> = wanted.filter { albumKey -> offeredTrackCounts.containsKey(albumKey) }

        val incomplete: String? = fillable.firstOrNull { albumKey ->
            val held: Int = heldCounts[albumKey] ?: 0
            val offered: Int = offeredTrackCounts[albumKey] ?: 0
            held < offered
        }
        if (incomplete != null) return incomplete

        // Everything the phone can fill is full. Rotate through the fillable albums so the phone's
        // staleness check reaches each of them in turn. Nothing fillable at all means there is nothing
        // to ask about, and an empty answer is what stops the phone publishing.
        if (fillable.isEmpty()) return ""
        val index: Int = if (rotation < 0) 0 else rotation % fillable.size
        return fillable[index]
    }
}
