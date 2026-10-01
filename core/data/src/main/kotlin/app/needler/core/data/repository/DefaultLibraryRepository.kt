package app.needler.core.data.repository

import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.ArtistDao
import app.needler.core.data.local.dao.FavouriteDao
import app.needler.core.data.local.dao.PinDao
import app.needler.core.data.local.dao.PullDao
import app.needler.core.data.local.dao.SyncStateDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.FavouriteEntity
import app.needler.core.data.local.entity.FavouriteTypeDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.SyncStateEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.local.projection.LibrarySongRow
import app.needler.core.data.local.projection.LibraryTotalsRow
import app.needler.core.data.mapper.CatalogueMappers
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.mapper.GenreCodec
import app.needler.core.data.mapper.networkCall
import app.needler.core.data.sync.AlbumSyncer
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.StatsSource
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.TrackListKind
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.ArtistReleasesDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * The owned library, served from the mirror.
 *
 * Every `observe*` here reads Room and nothing else, which is what makes offline need no separate
 * code path: the same query answers whether the server is two feet away or unreachable. The two
 * `refresh*` functions are the only members that touch the network, and they write into the mirror
 * rather than returning anything the UI renders - callers observe the result.
 *
 * `observeArtistDiscography` is one of exactly two places in the codebase that know both server
 * lanes exist. It does not fetch: [refreshArtistDiscography] merges the catalogue half into the
 * mirror as un-owned rows, so artist detail still shows the full discography on a train, minus
 * anything never fetched.
 */
public class DefaultLibraryRepository(
    private val albumDao: AlbumDao,
    private val artistDao: ArtistDao,
    private val trackDao: TrackDao,
    private val pinDao: PinDao,
    private val pullDao: PullDao,
    private val favouriteDao: FavouriteDao,
    private val syncStateDao: SyncStateDao,
    private val albumSyncer: AlbumSyncer,
    private val v1: V1Api,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LibraryRepository {

    // -------------------------------------------------------------------- artists

    /**
     * Artists, alphabetical, one window at a time.
     *
     * [ArtistDao.observeArtistsPaged] rather than [ArtistDao.observeArtists] plus a Kotlin slice, even
     * when the window is the whole table: one statement answers both, the offset is an index seek off
     * `index_artist_sort_name_normalised`, and there is no second code path to keep in step. The
     * paged statement existed before anything could reach it, so every caller - Android Auto and the
     * Artists screen alike - read the whole table and sliced, which is the shape REQUIREMENTS.md
     * "Performance budgets" ("Cold start to library content - Under 1.2 s") is written against.
     */
    override fun observeArtists(limit: Int, offset: Int): Flow<List<Artist>> =
        artistDao.observeArtistsPaged(limit, offset).map { rows -> rows.map(EntityMappers::artist) }

    override fun observeArtist(mbid: ArtistMbid): Flow<Artist?> = combine(
        artistDao.observeArtist(mbid.value),
        favouriteDao.observeArtistIsStarred(mbid.value),
    ) { row: ArtistEntity?, starred: Boolean ->
        row?.let { EntityMappers.artist(it, isFavourite = starred) }
    }

    override fun observeOwnedAlbumsByArtist(mbid: ArtistMbid): Flow<List<Album>> =
        albumDao.observeAlbumsByArtist(mbid.value).map { rows ->
            rows.filter { it.inLibrary }.map { EntityMappers.album(it) }
        }

    /**
     * Owned albums and the catalogue discography as one list, owned first.
     *
     * The join key is the release-group MBID, which both lanes agree on, so there is nothing to
     * merge at read time: the catalogue half was written into the same table by
     * [refreshArtistDiscography] and a row that is owned is simply the owned row.
     */
    override fun observeArtistDiscography(mbid: ArtistMbid): Flow<List<Album>> =
        albumDao.observeAlbumsByArtist(mbid.value).map { rows ->
            rows.map { EntityMappers.album(it) }
        }

    // --------------------------------------------------------------------- albums

    override fun observeAlbum(mbid: ReleaseGroupMbid): Flow<Album?> = combine(
        albumDao.observeAlbum(mbid.value),
        pinDao.observePin(mbid.value),
        pullDao.observePull(mbid.value),
        favouriteDao.observeAlbumIsStarred(mbid.value),
    ) { album: AlbumEntity?, pin: PinEntity?, pull: PullEntity?, starred: Boolean ->
        album?.let { EntityMappers.album(it, pin = pin, pull = pull, isFavourite = starred) }
    }

    /**
     * Tracks of one album, each carrying whether it is starred.
     *
     * The star was missing here, and its absence was invisible in the worst way: `Track.isFavourite`
     * defaults to false, so every track on album detail read as un-starred no matter what the mirror
     * held, and a star the user had tapped came back off the next time the screen was opened.
     * REQUIREMENTS.md "Playlists": "Binary favourites via `star`/`unstar` do persist and are the
     * supported mechanism" - a screen cannot offer that mechanism honestly while its read path drops
     * the answer.
     *
     * The join is `observeFavourites` filtered in Kotlin rather than a query of its own, because
     * [FavouriteDao] has no "starred tracks of one album" query and adding one is not this change's
     * to make. `observeStarredTracks` would have done it in SQL, but it joins the whole `track`
     * table to return rows this function already has; the favourite table alone is the smaller read,
     * and it carries the resolved disc and track columns precisely so that a caller can match on them
     * without building a composite string.
     */
    override fun observeAlbumTracks(mbid: ReleaseGroupMbid): Flow<List<Track>> = combine(
        trackDao.observeAlbumTracks(mbid.value),
        albumDao.observeAlbum(mbid.value),
        favouriteDao.observeFavourites(),
    ) { rows: List<TrackEntity>, album: AlbumEntity?, favourites: List<FavouriteEntity> ->
        val starred: Set<Long> = favourites
            .asSequence()
            .filter { it.entityType == FavouriteTypeDb.TRACK && it.releaseGroupMbid == mbid.value }
            .mapNotNull { row ->
                val disc: Int = row.discNo ?: return@mapNotNull null
                val track: Int = row.trackNo ?: return@mapNotNull null
                positionKey(disc, track)
            }
            .toSet()
        rows.map { row ->
            EntityMappers.track(
                row = row,
                isFavourite = positionKey(row.discNo, row.trackNo) in starred,
                albumTitle = album?.title,
            )
        }
    }

    /**
     * One of the library's album lists.
     *
     * Served from the mirror, so it works offline - which is also the reason two of the six kinds
     * cannot be honoured exactly. `FREQUENT` and `RECENT` are computed by the server from play
     * counts, and the mirror schema carries no play-count column to reproduce that ordering
     * offline. Both fall back to recently-added rather than emitting nothing; see the note in the
     * repository's own documentation.
     */
    override fun observeAlbumList(kind: AlbumListKind, limit: Int, offset: Int): Flow<List<Album>> =
        when (kind) {
            AlbumListKind.NEWEST,
            AlbumListKind.FREQUENT,
            AlbumListKind.RECENT,
            -> albumDao.observeLibraryByRecentlyAdded(limit, offset).map { rows ->
                rows.map { EntityMappers.album(it) }
            }

            AlbumListKind.ALPHABETICAL_BY_NAME ->
                albumDao.observeLibraryAlphabetical(limit, offset).map { rows ->
                    rows.map { EntityMappers.album(it) }
                }

            AlbumListKind.ALPHABETICAL_BY_ARTIST ->
                albumDao.observeLibraryByArtist(limit, offset).map { rows ->
                    rows.map { EntityMappers.album(it) }
                }

            // The window goes into SQL like the other five kinds. It used to read every starred album
            // and slice in Kotlin, which made `STARRED` the one kind of the advertised-as-paged list
            // that was not paged.
            AlbumListKind.STARRED -> favouriteDao.observeStarredAlbumsPaged(limit, offset).map { rows ->
                rows.map { EntityMappers.album(it, isFavourite = true) }
            }
        }

    /**
     * The Songs tab: every track in the library under one ordering.
     *
     * One query per emission and one row per song. The tab used to be built by flattening the
     * tracks of the first forty albums of an album list, which meant it showed a sample rather than
     * the library and sorted "Title" by album title; both were visible on a device. Four DAO
     * statements replace that, one per ordering the sort control offers, because SQLite cannot
     * index-serve an `ORDER BY` it only learns at bind time.
     *
     * `FREQUENT` falls through to recently-added, which is the same concession
     * [observeAlbumList] makes and for the same reason: play counts are the server's, and the
     * mirror carries no column that reproduces them. The cache index does count plays, but it drops
     * the row when it evicts the bytes, so it is an LRU signal rather than a listening history and
     * ordering a "most played" list by it would make well-worn songs disappear from it. The
     * interface documents the fallback rather than leaving it to be found.
     */
    override fun observeTracks(kind: TrackListKind, limit: Int, offset: Int): Flow<List<Track>> =
        when (kind) {
            TrackListKind.ALPHABETICAL_BY_TITLE ->
                trackDao.observeLibrarySongsByTitle(limit, offset)

            TrackListKind.ALPHABETICAL_BY_ARTIST ->
                trackDao.observeLibrarySongsByArtist(limit, offset)

            TrackListKind.NEWEST,
            TrackListKind.FREQUENT,
            -> trackDao.observeLibrarySongsByRecentlyAdded(limit, offset)

            TrackListKind.STARRED ->
                trackDao.observeStarredLibrarySongs(limit, offset)
        }.map { rows ->
            rows.map { row -> song(row, isFavourite = kind == TrackListKind.STARRED) }
        }

    /**
     * One [LibrarySongRow] as the domain sees it.
     *
     * The star is taken from the query rather than joined per row: the only ordering that can
     * produce a starred song is the starred one, and every row it returns is starred by
     * construction. The other three do not carry the flag, which is honest - the Songs tab draws no
     * star - and avoids a second query per song to answer a question nothing on the screen asks.
     *
     * The artist falls back to the album's when the mirror has none for the track. DroppedNeedle
     * sends a per-track artist only where it differs from the album's, so on a normal record the
     * column is null, and a song row that read "· Submarine" with nothing before the separator would
     * look like a bug rather than like missing data.
     */
    private fun song(row: LibrarySongRow, isFavourite: Boolean): Track {
        val track: Track = EntityMappers.track(
            row = row.track,
            isFavourite = isFavourite,
            albumTitle = row.albumTitle,
        )
        return if (track.artistName.isBlank()) track.copy(artistName = row.albumArtistName) else track
    }

    // --------------------------------------------------------------------- genres

    /**
     * Genre buckets, counted over the denormalised `album.genres` column.
     *
     * Genres are a display denormalisation rather than a table, so the counting happens in Kotlin
     * over one narrow column. That keeps the schema honest about where the truth lives - the tracks -
     * and still answers instantly for a library of any plausible size.
     *
     * [limit] and [offset] are applied **after** the aggregation, and that is not an oversight this
     * comment is apologising for: a bucket count over a pipe-encoded column cannot be windowed in
     * SQL, because nothing can know which genre is twenty-first alphabetically until every album's
     * column has been read. The window bounds what is allocated and returned - which is what stops a
     * browser being handed four hundred `Genre` objects to draw twenty of - and `drop` then `take`
     * rather than an index range, so no `offset + limit` can overflow when the caller's window is
     * unbounded.
     */
    override fun observeGenres(limit: Int, offset: Int): Flow<List<Genre>> =
        albumDao.observeGenreColumns().map { encoded ->
            val counts: MutableMap<String, Int> = LinkedHashMap()
            for (column in encoded) {
                for (genre in GenreCodec.decode(column)) {
                    counts[genre] = (counts[genre] ?: 0) + 1
                }
            }
            counts.entries
                .sortedBy { it.key.lowercase() }
                // Coerced because these are Kotlin list operations and both throw on a negative
                // count, where SQLite would have treated the same values as "from the start" and
                // "no limit". A browser that computed a page badly must not crash a head unit.
                .drop(offset.coerceAtLeast(0))
                .take(limit.coerceAtLeast(0))
                .map { Genre(name = it.key, albumCount = it.value) }
        }

    override fun observeTracksByGenre(genre: String, limit: Int, offset: Int): Flow<List<Track>> =
        trackDao.observeTracksByGenre(GenreCodec.likePattern(genre), limit, offset)
            .map { rows -> rows.map { EntityMappers.track(it) } }

    // ---------------------------------------------------------------------- stats

    /**
     * The "176 albums - 42 GB" header.
     *
     * `GET /api/v1/library/stats` is authoritative and is cached on `sync_state`; the sum over the
     * mirror is the offline fallback. [StatsSource] says which one this snapshot is, so the UI can
     * be honest rather than presenting a stale total as the server's word.
     */
    override fun observeLibraryStats(): Flow<LibraryStats> = combine(
        albumDao.observeLibraryTotals(),
        artistDao.observeArtistCount(),
        trackDao.observeTrackCount(),
        syncStateDao.observeSyncState(),
    ) { totals: LibraryTotalsRow, artists: Int, tracks: Int, sync: SyncStateEntity? ->
        val serverAlbums: Int? = sync?.serverAlbumCount
        LibraryStats(
            albumCount = serverAlbums ?: totals.albumCount,
            artistCount = artists,
            trackCount = tracks,
            totalSizeBytes = sync?.serverSizeBytes ?: totals.sizeBytes.takeIf { it > 0L },
            lastScanAt = sync?.lastScanAt?.let { kotlinx.datetime.Instant.fromEpochMilliseconds(it) },
            source = if (serverAlbums != null) StatsSource.SERVER else StatsSource.LOCAL_MIRROR,
        )
    }

    // ------------------------------------------------------------------- one-shots

    override suspend fun getTrack(key: TrackKey): Track? = trackDao.getTrack(
        releaseGroupMbid = key.releaseGroupMbid.value,
        discNo = key.discNumber,
        trackNo = key.trackNumber,
    )?.let { EntityMappers.track(it) }

    /**
     * Several tracks in one query, ordered to match [keys].
     *
     * The order matters: this is how the crate and a playlist are resolved, and a queue that came
     * back in database order would silently reshuffle itself.
     */
    override suspend fun getTracks(keys: List<TrackKey>): List<Track> {
        if (keys.isEmpty()) return emptyList()
        val rows: List<TrackEntity> = trackDao.getTracksByCanonicalKeys(keys.map { it.canonicalString })
        val byKey: Map<String, TrackEntity> = rows.associateBy { row ->
            row.releaseGroupMbid + "/" + row.discNo + "/" + row.trackNo
        }
        return keys.mapNotNull { key -> byKey[key.canonicalString]?.let { EntityMappers.track(it) } }
    }

    override suspend fun getAlbum(mbid: ReleaseGroupMbid): Album? {
        val row: AlbumEntity = albumDao.getAlbum(mbid.value) ?: return null
        return EntityMappers.album(
            row = row,
            pin = pinDao.getPin(mbid.value),
            pull = pullDao.getPull(mbid.value),
        )
    }

    // ------------------------------------------------------------------ refreshes

    /**
     * Fetches the artist's catalogue discography and merges it into the mirror.
     *
     * Merging means **inserting only what the mirror does not already have**. An owned album's row
     * knows its state, its track count, its size and its format; the catalogue copy knows none of
     * that, so overwriting would downgrade the row to a search result and lose the badge on artist
     * detail.
     *
     * The catalogue lane needs the companion bearer, so this fails with `SessionExpired` on a
     * degraded session and `Offline` with no network. Neither is fatal: the owned half of artist
     * detail still renders, which is the whole point of the mirror being the read path.
     *
     * ## A name-derived artist id never reaches the network
     *
     * DroppedNeedle mints a version 5 UUID for an artist it could not match to MusicBrainz, and this
     * route answers `400 Use the local library artist route for a DroppedNeedle artist ID` for every
     * one of them. Making the call anyway cost a round trip and produced a logged warning beside a
     * silently short list on screen, with nothing anywhere saying the discography was never
     * obtainable - which is how it went unnoticed. [ArtistMbid.isCatalogueIdentifier] is checked
     * first, and the refusal is returned as [NeedlerError.CapabilityUnavailable] so that the caller
     * can say so plainly.
     *
     * That case is reported as a failure rather than [Outcome.Ok] deliberately. "Nothing fetched, and
     * nothing ever will be" is not the same answer as "nothing to fetch", and artist detail draws a
     * different sentence for each. It is not retryable, so a write-queue entry carrying it is dropped
     * rather than replayed forever.
     */
    override suspend fun refreshArtistDiscography(mbid: ArtistMbid): Outcome<Unit> {
        if (!mbid.isCatalogueIdentifier) {
            return Outcome.Failure(NeedlerError.CapabilityUnavailable(NAME_DERIVED_ARTIST))
        }
        val call: Outcome<ArtistReleasesDto> = networkCall { v1.artistReleases(mbid.value) }
        val releases: ArtistReleasesDto = when (call) {
            is Outcome.Failure -> return call
            is Outcome.Success -> call.value
        }
        val artist: ArtistEntity? = artistDao.getArtist(mbid.value)
        val artistName: String = artist?.name.orEmpty()
        val now: Long = nowMillis()
        val incoming: List<Album> = (releases.albums + releases.eps + releases.singles)
            .mapNotNull { CatalogueMappers.album(it, artistName, mbid) }
        if (incoming.isEmpty()) return Outcome.Ok

        val known: Set<String> = albumDao
            .getAlbums(incoming.map { it.releaseGroupMbid.value })
            .map { it.releaseGroupMbid }
            .toSet()
        val fresh: List<AlbumEntity> = incoming
            .filter { !known.contains(it.releaseGroupMbid.value) }
            .map { CatalogueMappers.albumEntity(it, now) }
        if (fresh.isNotEmpty()) albumDao.upsertAll(fresh)

        if (artist != null && releases.sourceTotalCount != null) {
            artistDao.upsert(artist.copy(updatedAt = now))
        }
        return Outcome.Ok
    }

    /**
     * Re-reads one album and its tracks into the mirror.
     *
     * This is also the staleness checkpoint, which is why it delegates rather than duplicating:
     * `AlbumSyncer` is the single place the cached fingerprint is compared against fresh server
     * metadata, and having two of those would guarantee they disagreed.
     */
    override suspend fun refreshAlbum(mbid: ReleaseGroupMbid): Outcome<Unit> =
        when (val result = albumSyncer.syncAlbum(mbid)) {
            is Outcome.Failure -> result
            is Outcome.Success -> Outcome.Ok
        }

    private companion object {

        /**
         * The capability name reported when an artist's id cannot reach the catalogue.
         *
         * A string rather than a new [NeedlerError] case: the error hierarchy is one-to-one with the
         * requirements' failure-handling table and this is not a new row in it, only a capability the
         * server does not offer for this particular artist. It reaches the diagnostics log verbatim,
         * so it reads as a sentence.
         */
        const val NAME_DERIVED_ARTIST: String =
            "catalogue discography: this artist's id was derived from their name, not matched to " +
                "MusicBrainz"

        /**
         * Disc and track folded into one comparable value, for matching a starred favourite row
         * against a track row.
         *
         * Not the canonical `<mbid>/<disc>/<track>` string: both sides of this comparison are already
         * known to belong to the same release group, so including the MBID would allocate a string
         * per track on every emission to compare a part that cannot differ.
         */
        fun positionKey(discNo: Int, trackNo: Int): Long =
            discNo.toLong() shl Int.SIZE_BITS or (trackNo.toLong() and 0xFFFFFFFFL)
    }
}
