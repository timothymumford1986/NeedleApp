package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * An artist, keyed on the MusicBrainz artist MBID.
 *
 * [ownedAlbumCount] comes from the mirror. [catalogueAlbumCount] is only known once the artist's
 * discography has been fetched from `GET /api/v1/artists/{mbid}/releases`, which is why it is nullable:
 * artist detail is one of the two places where both server lanes are visible at once.
 */
public data class Artist(
    val mbid: ArtistMbid,
    val name: String,
    /** Name used for alphabetical ordering and the index jump on the Artists screen. */
    val sortName: String = name,
    val ownedAlbumCount: Int = 0,
    val catalogueAlbumCount: Int? = null,
    val artwork: ArtworkRef? = null,
    /**
     * MusicBrainz's disambiguation comment - "drummer, London", "US rock band" - or null.
     *
     * It exists because name-identical artists are otherwise indistinguishable on screen, and two
     * rows reading exactly the same string is a defect that shipped. `UnifiedSearchUseCase`
     * deduplicates the catalogue half of an artist search on the case-folded name for exactly that
     * reason, and recorded the alternative it could not take: "telling them apart by MusicBrainz's
     * disambiguation comment was rejected because `Artist` carries no such field". This is that
     * field. Collapsing several distinct artists into one row is a lossy answer to a question the
     * server already answers - `GET /api/v1/search` and `GET /api/v1/search/suggest` both send
     * `disambiguation`, and `GET /api/v1/artists/{artist_mbid}` sends it too - so the merge can now
     * keep the rows and let them read differently instead.
     *
     * It is a catalogue field. Nothing in the mirror writes it: the Subsonic `getArtists` lane that
     * fills the `artist` table carries no such value, so an owned artist read back from Room has it
     * null and no column was added to hold it. That is the honest split - a disambiguation comment
     * tells apart two *catalogue* strangers, and an artist already in the library is told apart by
     * the albums of theirs that are on the device.
     *
     * Nullable and defaulted, so every existing construction of this class is unaffected.
     */
    val disambiguation: String? = null,
    val genres: List<String> = emptyList(),
    val isFavourite: Boolean = false,
    /**
     * True when the user asked to be told about this artist's future releases, via the
     * `monitor_artist` flag on a request. Drives the "new release from a followed artist"
     * notification; the full following UI is out of v1 scope.
     */
    val isMonitored: Boolean = false,
)

/**
 * One page of an artist's catalogue discography, and what the server said about the rest.
 *
 * `GET /api/v1/artists/{mbid}/releases` takes `limit` and `offset` and answers with `returned_count`,
 * `has_more`, `next_offset` and `source_total_count`. Those four were being thrown away: the fetch
 * asked for the default first fifty release groups across all three buckets and reported only whether
 * it had written anything, so a prolific artist's screen showed fifty records, claimed in the next
 * sentence that this was "this artist's whole discography as MusicBrainz has it", and offered nothing
 * to ask for the rest. This type is what a caller needs to page and to say how far it has got.
 *
 * REQUIREMENTS.md "Library browse" makes artist detail "where the two lanes meet visibly", and
 * paging behaviour is per endpoint rather than global in this API - the same document records that
 * `GET /api/v1/downloads` "has no `total` and no `total_pages`" and is therefore blind. This endpoint
 * is not that one: it names both the next offset and the size of the population, so a client may say
 * how much of a discography it has looked up. It still may not offer "page 3 of 7", because the
 * cursor is an offset rather than a page number and [sourceTotal] counts release groups upstream
 * rather than pages of them.
 *
 * ## One cursor, not a flag and a cursor
 *
 * [hasMore] is derived from [nextOffset] rather than carried beside it, so the state "there is more
 * and no way to ask for it" cannot be represented. The wire can say exactly that - `has_more` true
 * with `next_offset` absent and nothing returned to advance past - and a caller that believed both
 * fields would re-request the same offset for ever. The fetch resolves that contradiction once, in
 * favour of stopping, and what reaches here is only ever an offset that can actually be asked for.
 */
public data class ArtistDiscographyPage(
    /** The offset this page was asked for, so a late answer can be told from the current one. */
    val offset: Int,
    /**
     * How many release groups this page carried, across all three buckets.
     *
     * The server's own `returned_count` where it sent one. It counts what the catalogue holds, owned
     * and un-owned alike, which is what makes it comparable with [sourceTotal] - the number of rows
     * artist detail draws is not, because the owned half of that screen comes from the mirror and the
     * catalogue's copies of those records are discarded on the join.
     */
    val returned: Int,
    /**
     * The offset to ask for next, or null when this is the end of the discography.
     *
     * Null also when the server claimed more and gave no usable cursor; see the type's own KDoc.
     */
    val nextOffset: Int? = null,
    /**
     * How many release groups the catalogue holds for this artist, or null when the server did not
     * say.
     *
     * `source_total_count` is documented as null while `warming` is true, so a caller may never
     * assume a figure is there.
     */
    val sourceTotal: Int? = null,
) {

    /** True when there is another page, which is exactly "there is an offset to ask for". */
    public val hasMore: Boolean get() = nextOffset != null

    public companion object {
        /**
         * The answer from a source that does not page: this is everything, and there is no count.
         *
         * The default implementation of
         * [app.needler.core.domain.repository.LibraryRepository.refreshArtistDiscographyPage] returns
         * it, which is the honest reading of an implementation that only knows how to fetch the
         * discography whole. [sourceTotal] is deliberately null rather than zero, so nothing drawn from it can
         * claim a total the source never reported.
         */
        public val UNPAGED: ArtistDiscographyPage = ArtistDiscographyPage(offset = 0, returned = 0)
    }
}

/** A genre bucket, from Subsonic `getGenres`. */
public data class Genre(
    val name: String,
    val albumCount: Int? = null,
    val trackCount: Int? = null,
)

/**
 * The library totals in the header of screens 02, 09 and 13 ("176 albums - 42 GB") plus the
 * "last scan 47m ago" line.
 *
 * `GET /api/v1/library/stats` is authoritative; the sum over the mirror is the offline fallback, and
 * [source] says which one this instance came from so the UI can be honest when offline.
 */
public data class LibraryStats(
    val albumCount: Int,
    val artistCount: Int,
    val trackCount: Int,
    val totalSizeBytes: Long?,
    val lastScanAt: Instant? = null,
    val source: StatsSource = StatsSource.LOCAL_MIRROR,
)

/** Where a [LibraryStats] snapshot came from. */
public enum class StatsSource {
    /** `GET /api/v1/library/stats`. */
    SERVER,

    /** Summed over the local metadata mirror, used offline or with an expired session. */
    LOCAL_MIRROR,
}
