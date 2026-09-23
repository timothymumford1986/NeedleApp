package app.needler.core.domain.repository

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackListKind
import kotlinx.coroutines.flow.Flow

/**
 * The owned library, plus the one place where the two server lanes are joined for browsing.
 *
 * **The mirror is the read path.** Every `observe*` function here is served from the local Room mirror
 * and therefore behaves identically online and offline; none of them can fail, which is why they return
 * plain `Flow` rather than `Flow<Outcome<...>>`. The `refresh*` functions are the only network-touching
 * members and they write into the mirror, so callers observe the result rather than consuming a return
 * value.
 */
public interface LibraryRepository {

    /** All artists, alphabetical by sort name, for the Artists screen and its index jump. */
    public fun observeArtists(): Flow<List<Artist>>

    public fun observeArtist(mbid: ArtistMbid): Flow<Artist?>

    /** Albums of one artist that the server owns. Mirror only. */
    public fun observeOwnedAlbumsByArtist(mbid: ArtistMbid): Flow<List<Album>>

    /**
     * The artist's full discography: owned albums from the mirror joined with the catalogue
     * discography from `GET /api/v1/artists/{mbid}/releases` on release-group MBID, emitted as one
     * list of [Album] with owned albums first and everything un-owned carrying
     * [app.needler.core.domain.model.AlbumState.NotOwned].
     *
     * This and `SearchRepository` are the only two places in the codebase that know both lanes exist.
     * The catalogue half is cached in the mirror by [refreshArtistDiscography], so this flow still
     * emits offline - just without any discography fetched since.
     */
    public fun observeArtistDiscography(mbid: ArtistMbid): Flow<List<Album>>

    public fun observeAlbum(mbid: ReleaseGroupMbid): Flow<Album?>

    /** Tracks of one album, ordered by disc then track number. */
    public fun observeAlbumTracks(mbid: ReleaseGroupMbid): Flow<List<Track>>

    /** One of the library's album lists, served from the mirror. */
    public fun observeAlbumList(
        kind: AlbumListKind,
        limit: Int = 100,
        offset: Int = 0,
    ): Flow<List<Album>>

    /**
     * Every track in the library under one ordering, bounded by [limit]. The Songs tab's query.
     *
     * It exists because that tab had no query of its own and was assembled instead from the tracks
     * of the first few albums of an album list. That gave two wrong answers at once: the list was a
     * sample of the library rather than the library, and "Title" ordered by *album* title, so
     * choosing it produced one record in track order instead of an alphabet of songs. Both were
     * seen on a device before this function was written.
     *
     * Served from the mirror like every other `observe*` here, so it reads identically on a train
     * and at home - REQUIREMENTS.md "Library browse": "All library browsing reads the local metadata
     * mirror, so it works identically online and offline." Only tracks of albums the library
     * actually owns are returned; a catalogue-only album has no files behind it and therefore
     * nothing to list.
     *
     * ## What each ordering can honestly mean for a song
     *
     * [TrackListKind.ALPHABETICAL_BY_TITLE] and [TrackListKind.ALPHABETICAL_BY_ARTIST] are exact:
     * both are normalised columns the mirror writes on sync, so neither sorts at read time.
     * [TrackListKind.NEWEST] is the track's *album's* `added_at`, because a track has no arrival
     * time of its own and minting one would be a claim about when the user got the song that
     * nothing in the mirror supports. [TrackListKind.STARRED] is exact as well, and is the only
     * ordering here that is genuinely about the track rather than about its record.
     *
     * [TrackListKind.FREQUENT] is the exception, and it is an honest one. There is no play count on
     * `track`, for the same reason [observeAlbumList] cannot honour `FREQUENT` either: play counts
     * are computed server-side by `getAlbumList2` and the mirror carries no column reproducing them.
     * The cache index does count plays, but that row is deleted when its bytes are evicted, so a
     * song played two hundred times would silently drop to nothing the moment the cache reclaimed
     * it - a "most played" list that forgets its most played entries is worse than one that is not
     * offered. It therefore falls back to [TrackListKind.NEWEST], exactly as the album list does,
     * and the fallback is written down here rather than left to be discovered on a device.
     *
     * ## Bounded rather than paged
     *
     * [limit] is a cap, not a page cursor. A five-thousand-album library is fifty thousand-odd
     * tracks and holding them all in a `StateFlow` that is rebuilt on every sort change would spend
     * the whole of REQUIREMENTS.md's scroll budget on allocation. Paging 3 is deliberately not used:
     * `:core:data` has no paging dependency, and the Glance widgets and Android Auto read these same
     * repositories and can consume neither a `PagingSource` nor a `PagingData`. A caller that wants
     * the next slice passes [offset], which is the same bargain [observeAlbumList] and
     * [observeTracksByGenre] already make.
     */
    public fun observeTracks(
        kind: TrackListKind,
        limit: Int = 200,
        offset: Int = 0,
    ): Flow<List<Track>>

    public fun observeGenres(): Flow<List<Genre>>

    public fun observeTracksByGenre(
        genre: String,
        limit: Int = 200,
        offset: Int = 0,
    ): Flow<List<Track>>

    /**
     * Library totals for the header on screens 02, 09 and 13. Falls back to summing the mirror when
     * `GET /api/v1/library/stats` has never been reached; see
     * [app.needler.core.domain.model.StatsSource].
     */
    public fun observeLibraryStats(): Flow<LibraryStats>

    /** A single track by its stable key. Null when the mirror does not have it. */
    public suspend fun getTrack(key: TrackKey): Track?

    /** Several tracks in one query, for restoring the crate or resolving a playlist. */
    public suspend fun getTracks(keys: List<TrackKey>): List<Track>

    public suspend fun getAlbum(mbid: ReleaseGroupMbid): Album?

    /**
     * Fetches the artist's catalogue discography and merges it into the mirror.
     *
     * Needs the `/api/v1` lane, so it fails with [app.needler.core.domain.model.NeedlerError.SessionExpired]
     * on a degraded session and [app.needler.core.domain.model.NeedlerError.Offline] with no network.
     * Neither is fatal: the owned half of artist detail still renders.
     */
    public suspend fun refreshArtistDiscography(mbid: ArtistMbid): Outcome<Unit>

    /**
     * Re-reads one album and its tracks from the server into the mirror.
     *
     * This is also the staleness checkpoint: each track's `file_id`, size, duration and format are
     * compared against the cached record, and any difference evicts the cached bytes - see
     * [SyncRepository.syncAlbum], which this delegates to.
     */
    public suspend fun refreshAlbum(mbid: ReleaseGroupMbid): Outcome<Unit>
}
