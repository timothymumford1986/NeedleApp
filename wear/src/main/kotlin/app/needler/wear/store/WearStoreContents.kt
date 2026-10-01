package app.needler.wear.store

/**
 * What the watch holds, grouped into albums, with the room it has left.
 *
 * This is the pure half of [WearAudioStore]: every decision about how a flat list of sidecars becomes
 * a list of albums is here, so it can be asserted in a JVM unit test with no watch, no phone and no
 * files. The store is left with directory scanning and the commit order.
 *
 * ## Why "not scanned" is a state
 *
 * An empty list before the first scan reads as a lost library, which is the same mistake
 * [app.needler.wear.playback.WearPlaybackState] separates `Connecting` from `Idle` to avoid. The
 * on-watch screen draws nothing rather than "nothing on your watch" until it has actually looked.
 */
data class WearStoreContents(
    /** Albums, most tracks first, then alphabetically - see [of]. */
    val albums: List<WearStoredAlbum>,
    val space: WearStoreSpace,
    /** False only before the first scan. See the class note. */
    val scanned: Boolean = true,
) {

    val trackCount: Int get() = albums.sumOf { album -> album.trackCount }

    /** Bytes of audio the watch is holding. What a "remove all" would free. */
    val bytes: Long get() = albums.sumOf { album -> album.bytes }

    val isEmpty: Boolean get() = albums.isEmpty()

    /** Every track key the watch holds, for diffing against an album's offered track count. */
    val heldKeys: Set<String>
        get() = albums.flatMapTo(LinkedHashSet()) { album ->
            album.tracks.map { track -> track.key.canonical }
        }

    fun album(albumKey: String): WearStoredAlbum? =
        albums.firstOrNull { album -> album.albumKey == albumKey }

    /** How many tracks of [albumKey] the watch holds. Zero for an album it has never seen. */
    fun heldCount(albumKey: String): Int = album(albumKey)?.trackCount ?: 0

    /**
     * What the watch reports holding for one album, as
     * [app.needler.wear.playback.WearPlaybackProtocol.KEY_HELD_TRACKS] entries.
     *
     * Capped, and the cap is the reason this is here rather than in the coordinator: the selection item
     * has a 100 KB ceiling and an album with hundreds of tracks would otherwise be the thing that
     * silently stopped it publishing. Over the cap, the first entries are reported and the tail is
     * fetched on later passes - slower, rather than not at all.
     */
    fun heldEntriesFor(albumKey: String, limit: Int): List<String> {
        val album: WearStoredAlbum = album(albumKey) ?: return emptyList()
        if (limit <= 0) return emptyList()
        return album.tracks.take(limit).map { track -> track.heldEntry }
    }

    companion object {

        /** Before the first scan. Distinct from an empty store; see the class note. */
        val NotScanned: WearStoreContents = WearStoreContents(
            albums = emptyList(),
            space = WearStoreSpace.Unknown,
            scanned = false,
        )

        /**
         * Groups sidecars into albums.
         *
         * Two orderings, and both are chosen rather than inherited from the filesystem - a directory
         * listing is in whatever order the volume returns, which is stable enough to look deliberate and
         * arbitrary enough to be wrong.
         *
         *  * **Tracks within an album run in record order**: disc, then track number. That is the order
         *    the album plays in, and the order the pipeline fills it in, so a part-transferred album
         *    reads as a record with its later tracks missing rather than as a shuffled fragment.
         *  * **Albums run by track count, most first, then by title.** The biggest album is the one the
         *    user is looking for when they came to free space, which is the same reason
         *    REQUIREMENTS.md lists the phone's downloads "by size, largest first". Track count rather
         *    than bytes, because a lossless album and a lossy one of the same length differ tenfold in
         *    bytes and not at all in what removing them costs the listener; the byte figure is drawn
         *    beside each row either way.
         */
        fun of(records: List<WearTrackRecord>, space: WearStoreSpace): WearStoreContents {
            val grouped: Map<String, List<WearTrackRecord>> =
                records.groupBy { record -> record.albumKey }
            val albums: List<WearStoredAlbum> = grouped.map { (albumKey, tracks) ->
                WearStoredAlbum(
                    albumKey = albumKey,
                    tracks = tracks.sortedWith(
                        compareBy(
                            { track -> track.key.discNumber },
                            { track -> track.key.trackNumber },
                        ),
                    ),
                )
            }.sortedWith(
                compareByDescending<WearStoredAlbum> { album -> album.trackCount }
                    .thenBy { album -> album.title.lowercase() },
            )
            return WearStoreContents(albums = albums, space = space, scanned = true)
        }
    }
}

/**
 * One album as the watch holds it: the tracks that actually arrived, in record order.
 *
 * Partial by design. REQUIREMENTS.md "Partial content is a normal state" treats a half-present album as
 * a requirement rather than an edge case on the phone, and a watch fills an album one track at a time
 * over Bluetooth, so partial is the *usual* state here for as long as a transfer is running. The
 * difference from the phone is that the missing tracks have no fallback - the watch cannot stream them -
 * so the screen shows what is here and says how much is not, and never offers to play a track that is
 * absent.
 *
 * [title] and [artist] are read off the tracks rather than carried separately, because the track item is
 * the only thing on the wire that describes them and a watch out of range must still be able to list
 * what it holds without consulting the phone's offer.
 */
data class WearStoredAlbum(
    val albumKey: String,
    val tracks: List<WearTrackRecord>,
) {

    val trackCount: Int get() = tracks.size

    val bytes: Long get() = tracks.sumOf { track -> track.sizeBytes }

    /** The album title, from the first track that carries one. Blank when nothing does. */
    val title: String
        get() = tracks.firstOrNull { track -> track.albumTitle.isNotBlank() }?.albumTitle ?: ""

    /**
     * The album's artist, or null on a compilation.
     *
     * Null when the tracks disagree, and the screen then draws the title alone. The alternative was to
     * invent a "Various artists" label, and REQUIREMENTS.md "Vocabulary" fixes the product's words - a
     * new user-visible phrase is not a thing to mint inside a data class on the watch. Nothing on the
     * wire carries an album artist: [app.needler.wear.playback.WearPlaybackProtocol.KEY_ALBUM_ARTIST]
     * does, but that is the phone's offer, which a watch out of range cannot read.
     */
    val artist: String?
        get() {
            val named: List<String> = tracks.map { track -> track.artist }.filter { it.isNotBlank() }
            val distinct: Set<String> = named.toSet()
            return if (distinct.size == 1) distinct.first() else null
        }

    /** The quality badge for the album: the one format its tracks share, or null when they differ. */
    val format: String?
        get() {
            val named: List<String> = tracks.map { track -> track.format }.filter { it.isNotBlank() }
            val distinct: Set<String> = named.toSet()
            return if (distinct.size == 1) distinct.first() else null
        }
}
