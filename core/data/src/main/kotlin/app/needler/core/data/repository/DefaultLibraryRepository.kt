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
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.local.entity.PullEntity
import app.needler.core.data.local.entity.SyncStateEntity
import app.needler.core.data.local.entity.TrackEntity
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

    override fun observeArtists(): Flow<List<Artist>> =
        artistDao.observeArtists().map { rows -> rows.map(EntityMappers::artist) }

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

    override fun observeAlbumTracks(mbid: ReleaseGroupMbid): Flow<List<Track>> = combine(
        trackDao.observeAlbumTracks(mbid.value),
        albumDao.observeAlbum(mbid.value),
    ) { rows: List<TrackEntity>, album: AlbumEntity? ->
        rows.map { EntityMappers.track(it, albumTitle = album?.title) }
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

            AlbumListKind.STARRED -> favouriteDao.observeStarredAlbums().map { rows ->
                rows.drop(offset).take(limit).map { EntityMappers.album(it, isFavourite = true) }
            }
        }

    // --------------------------------------------------------------------- genres

    /**
     * Genre buckets, counted over the denormalised `album.genres` column.
     *
     * Genres are a display denormalisation rather than a table, so the counting happens in Kotlin
     * over one narrow column. That keeps the schema honest about where the truth lives - the tracks -
     * and still answers instantly for a library of any plausible size.
     */
    override fun observeGenres(): Flow<List<Genre>> =
        albumDao.observeGenreColumns().map { encoded ->
            val counts: MutableMap<String, Int> = LinkedHashMap()
            for (column in encoded) {
                for (genre in GenreCodec.decode(column)) {
                    counts[genre] = (counts[genre] ?: 0) + 1
                }
            }
            counts.entries
                .sortedBy { it.key.lowercase() }
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
     */
    override suspend fun refreshArtistDiscography(mbid: ArtistMbid): Outcome<Unit> {
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
}
