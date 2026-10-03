package app.needler.wear.sync

import app.needler.wear.playback.WearPlaybackProtocol
import app.needler.wear.store.WearStoreContents
import app.needler.wear.store.WearStoreSpace
import app.needler.wear.store.WearStoredAlbum

/**
 * Everything the on-watch screen draws, assembled from the three things that know it.
 *
 * The screen has to answer four questions at once and they come from three different places: what is on
 * the watch ([WearStoreContents]), what the user asked for ([WearSyncSelection]), what the phone could
 * supply ([WearOfferState]), and how much room is left ([WearStoreSpace]). Combining them is the part
 * with decisions in it, so it is here and pure, and [of] is what `WearOnWatchStateTest` asserts.
 *
 * ## This screen is the "see and change" half of the sync policy
 *
 * The sync policy is stated on [WearPlaybackProtocol.PATH_SYNC_OFFER]: only the phone's **Device**
 * tier is ever offered, the watch's tier is the Device tier too and is never evicted, and the bound is a
 * free-space floor that refuses rather than evicts. The requirement attached to that policy is that "the
 * watch's bound must be explicit and the user must be able to see and change what is on it", and this is
 * where both halves are met:
 *
 *  * **Explicit bound.** [freeBytes] and [floorBytes] are on screen, next to what the store is using, so
 *    the number that decides whether an album can be added is visible rather than implied.
 *  * **Change what is on it.** Every row can be added or removed, one album at a time. REQUIREMENTS.md
 *    makes per-album removal the user's only lever on a full phone - "albums on the device listed by size,
 *    largest first, each removable on its own... it is the only view that can answer what is actually
 *    taking up the room" - and a watch has less room and fewer levers, so it needs the same thing more.
 */
data class WearOnWatchState(
    /** False before the store has been scanned. The screen draws nothing rather than "nothing here". */
    val scanned: Boolean,
    /** What the phone is offering, including the two states that are not a list of albums. */
    val offer: WearOfferState,
    /** On-watch albums first, then albums the phone is offering. See [of]. */
    val rows: List<WearOnWatchRow>,
    /** Bytes of audio on the watch. */
    val usedBytes: Long,
    /** The room the volume reports now, and the floor it is held to. */
    val space: WearStoreSpace,
    /** Albums on the phone's device tier that it could not fit in its offer. */
    val notShown: Int,
) {

    val albumsOnWatch: Int get() = rows.count { row -> row.onWatch }

    val tracksOnWatch: Int get() = rows.sumOf { row -> row.heldTracks }

    /** True when the selection is full, so the screen can say why an add did nothing. */
    val selectionFull: Boolean
        get() = rows.count { row -> row.wanted } >= WearPlaybackProtocol.MAX_WANTED_ALBUMS

    /** True when something is still arriving, which is what the screen reports as syncing. */
    val transferring: Boolean get() = rows.any { row -> row.transferring }

    companion object {

        /** Before the first scan. */
        val NotScanned: WearOnWatchState = WearOnWatchState(
            scanned = false,
            offer = WearOfferState.Loading,
            rows = emptyList(),
            usedBytes = 0L,
            space = WearStoreSpace.Unknown,
            notShown = 0,
        )

        /**
         * Combines the store, the selection and the offer into one list of rows.
         *
         * Ordering, and why it is this way round:
         *
         *  * **Albums on the watch come first**, largest first by track count. They are what the user
         *    came to play, and on a full watch they are what the user came to remove -
         *    REQUIREMENTS.md's "largest first" for the phone's Storage screen, for the same reason.
         *  * **Then albums the phone is offering and the watch does not have**, in the order the phone
         *    sent them, which is most recently downloaded first. The newest download is the one somebody
         *    is most likely to want on their wrist.
         *
         * An album that is on the watch but **not** in the offer keeps its row rather than vanishing.
         * That is the state a watch is in when the phone has removed the download, and it matters: those
         * bytes are still perfectly good music and the user must still be able to remove them - but the
         * phone can no longer check whether the server has replaced them, so [WearOnWatchRow.refreshable]
         * is false and the screen says so. Hiding the row would leave music on the watch that nothing in
         * the app admitted to.
         */
        fun of(
            contents: WearStoreContents,
            selection: WearSyncSelection,
            offer: WearOfferState,
        ): WearOnWatchState {
            val offered: List<WearOfferedAlbum> = when (offer) {
                is WearOfferState.Offered -> offer.albums
                else -> emptyList()
            }
            val offeredByKey: Map<String, WearOfferedAlbum> =
                offered.associateBy { album -> album.albumKey }
            val space: WearStoreSpace = contents.space

            // While the offer has not come back there is nothing to conclude from an album's absence
            // from it, and concluding anything would flash "not on phone" against music that is
            // perfectly refreshable. REQUIREMENTS.md's rule for a failed free-space reading is the same
            // shape: unknown suspends the judgement rather than guessing at it.
            val offerKnown: Boolean = offer !is WearOfferState.Loading

            val onWatchRows: List<WearOnWatchRow> = contents.albums.map { album ->
                rowFor(
                    album = album,
                    offered = offeredByKey[album.albumKey],
                    offerKnown = offerKnown,
                    wanted = selection.contains(album.albumKey),
                    space = space,
                )
            }

            val heldKeys: Set<String> = contents.albums.mapTo(HashSet()) { album -> album.albumKey }
            val addableRows: List<WearOnWatchRow> = offered
                .filter { album -> album.albumKey !in heldKeys }
                .map { album ->
                    WearOnWatchRow(
                        albumKey = album.albumKey,
                        title = album.title,
                        artist = album.artist.takeIf { artist -> artist.isNotBlank() },
                        format = null,
                        heldTracks = 0,
                        offeredTracks = album.trackCount,
                        watchBytes = 0L,
                        offeredBytes = album.sizeBytes,
                        wanted = selection.contains(album.albumKey),
                        refreshable = true,
                        fits = space.canAccept(album.sizeBytes),
                    )
                }

            return WearOnWatchState(
                scanned = contents.scanned,
                offer = offer,
                rows = onWatchRows + addableRows,
                usedBytes = contents.bytes,
                space = space,
                notShown = (offer as? WearOfferState.Offered)?.notShown ?: 0,
            )
        }

        private fun rowFor(
            album: WearStoredAlbum,
            offered: WearOfferedAlbum?,
            offerKnown: Boolean,
            wanted: Boolean,
            space: WearStoreSpace,
        ): WearOnWatchRow {
            // The title on the watch wins over the phone's, because it came with the bytes and is
            // readable with the phone out of range. The offer's title fills in only when no track
            // carried one.
            val title: String = album.title.ifBlank { offered?.title ?: "" }
            return WearOnWatchRow(
                albumKey = album.albumKey,
                title = title,
                artist = album.artist ?: offered?.artist?.takeIf { artist -> artist.isNotBlank() },
                format = album.format,
                heldTracks = album.trackCount,
                offeredTracks = offered?.trackCount ?: 0,
                watchBytes = album.bytes,
                offeredBytes = offered?.sizeBytes ?: 0L,
                wanted = wanted,
                // Absent from a known offer means the phone has dropped the download. Absent from an
                // offer that has not arrived means nothing at all.
                refreshable = offered != null || !offerKnown,
                // An album already here needs room only for what is missing, and the honest estimate of
                // that is the phone's figure for the whole album less what the watch holds. It is an
                // estimate because the missing tracks may be longer or shorter than the average; it is
                // only ever used to grey an add, and the ingest re-checks the real figure per track.
                fits = space.canAccept(
                    ((offered?.sizeBytes ?: 0L) - album.bytes).coerceAtLeast(1L),
                ),
            )
        }
    }
}

/**
 * One album on the on-watch screen, whether it is on the watch, offered, or both.
 *
 * @param heldTracks tracks actually on the watch. Zero for an album the user has not added.
 * @param offeredTracks tracks the phone has downloaded, or 0 when the phone is not offering this album.
 *   Not the album's true length - see [WearOfferedAlbum.trackCount] for why the difference matters.
 * @param watchBytes what removing this album from the watch would free. Real bytes on the watch, which
 *   REQUIREMENTS.md insists on for the phone's equivalent: "a size shown against an album is the bytes
 *   actually on disk, not what the server says the album weighs."
 * @param offeredBytes what the phone says the whole album weighs, which is what an add would cost.
 * @param wanted whether the user has asked for it. A wanted album with missing tracks is transferring;
 *   an unwanted album with tracks is one the user stopped fetching and kept what arrived.
 * @param refreshable whether the phone is still offering this album. False means the phone no longer has
 *   it downloaded, so nothing can fill the gaps and nothing can check whether the server replaced the
 *   bytes - which REQUIREMENTS.md "Invalidating upgraded files" makes the one thing the user needs told
 *   about, since the alternative is silently keeping the worse copy.
 * @param fits whether there is room for what is missing. False greys the add rather than letting the
 *   user start a transfer the floor will refuse - see [WearStoreSpace.canAccept].
 */
data class WearOnWatchRow(
    val albumKey: String,
    val title: String,
    val artist: String?,
    val format: String?,
    val heldTracks: Int,
    val offeredTracks: Int,
    val watchBytes: Long,
    val offeredBytes: Long,
    val wanted: Boolean,
    val refreshable: Boolean,
    val fits: Boolean,
) {

    /** True when any of this album is on the watch. */
    val onWatch: Boolean get() = heldTracks > 0

    /** True when the whole of what the phone has is here. */
    val complete: Boolean get() = onWatch && offeredTracks > 0 && heldTracks >= offeredTracks

    /** True when the user has asked for it and tracks are still missing. */
    val transferring: Boolean get() = wanted && refreshable && heldTracks < offeredTracks

    /**
     * True when the user stopped fetching an album and kept what had arrived.
     *
     * Its own state rather than a variety of incomplete, because it is the one the user caused: the
     * screen says "3 tracks kept" rather than implying a transfer that is going to finish.
     */
    val partialAndStopped: Boolean get() = onWatch && !wanted && (offeredTracks == 0 || heldTracks < offeredTracks)

    /** Whether tapping this row can start playback. */
    val playable: Boolean get() = onWatch

    /** Whether tapping this row would add it to the selection. */
    val addable: Boolean get() = !wanted && refreshable && fits
}
