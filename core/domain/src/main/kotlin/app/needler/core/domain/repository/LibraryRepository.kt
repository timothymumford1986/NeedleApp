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
