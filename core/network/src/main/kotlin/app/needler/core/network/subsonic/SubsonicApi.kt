package app.needler.core.network.subsonic

import app.needler.core.network.NetworkError
import app.needler.core.network.media.SubsonicMediaUrls
import app.needler.core.network.subsonic.dto.AlbumId3Dto
import app.needler.core.network.subsonic.dto.ArtistId3Dto
import app.needler.core.network.subsonic.dto.ArtistsDto
import app.needler.core.network.subsonic.dto.ChildDto
import app.needler.core.network.subsonic.dto.GenreDto
import app.needler.core.network.subsonic.dto.IndexesDto
import app.needler.core.network.subsonic.dto.LyricsListDto
import app.needler.core.network.subsonic.dto.OpenSubsonicExtensionDto
import app.needler.core.network.subsonic.dto.PlaylistDto
import app.needler.core.network.subsonic.dto.ScanStatusDto
import app.needler.core.network.subsonic.dto.SearchResult3Dto
import app.needler.core.network.subsonic.dto.Starred2Dto
import app.needler.core.network.subsonic.dto.SubsonicServerInfoDto

/** `type` on `getAlbumList2`. `highest` is rejected by this server, so it is not offered. */
public enum class AlbumListType(public val wire: String) {
    /** Recently added — screen 02's "Recently added" row. */
    Newest("newest"),

    /** Server-computed play frequency — "Most played". */
    Frequent("frequent"),

    /** Recently played. */
    Recent("recent"),
    AlphabeticalByName("alphabeticalByName"),
    AlphabeticalByArtist("alphabeticalByArtist"),
    Starred("starred"),
    Random("random"),
    ByYear("byYear"),
    ByGenre("byGenre"),
}

/**
 * The `/subsonic/rest/*` lane: browsing and playing the owned library, playlists and favourites.
 *
 * Auth is the `apiKey=<app-password>` query parameter — the `apiKeyAuthentication` extension.
 * The legacy `u`+`t`+`s` salted-MD5 scheme is deliberately not implemented. `v`, `c=Needler` and
 * `f=json` are sent on every call.
 *
 * This lane is only reachable when an administrator has switched `subsonic_enabled` on. When they
 * have not, every call fails with [NetworkError.SubsonicProtocolDisabled] — which is a different
 * outcome from [NetworkError.Unauthorised] and must be presented differently: the app names the
 * setting an admin has to turn on, rather than asking the user to sign in again.
 *
 * The app-password does not expire, so this lane keeps working after the 30-day companion bearer
 * dies. That asymmetry is what lets an expired session degrade Needler to a pure music player
 * instead of bricking it.
 *
 * Every function returns DTOs and throws only [NetworkError].
 */
public interface SubsonicApi {

    /**
     * URL builders for `stream`, `download` and `getCoverArt`, which Media3 and Coil fetch
     * directly. Audio is never buffered through this interface.
     */
    public val mediaUrls: SubsonicMediaUrls

    // ------------------------------------------------------------ Negotiation

    /** `ping` — liveness plus the envelope's server name, protocol version and `openSubsonic`. */
    public suspend fun ping(): SubsonicServerInfoDto

    /**
     * `getOpenSubsonicExtensions` — the only endpoint on this lane that needs no credential.
     *
     * Anything absent from the response must be treated as unavailable, however plausible: this
     * server implements `songLyrics:1`, `playbackReport:1` and `indexBasedQueue:1` without
     * advertising them.
     */
    public suspend fun openSubsonicExtensions(): List<OpenSubsonicExtensionDto>

    // ---------------------------------------------------------------- Library

    /**
     * `getArtists` — the alphabetical artist index with its jump letters.
     *
     * @param ifModifiedSince accepted by the contract but **ignored by this server's handler**; a
     *   delta sync must use [indexes] instead, which honours it.
     */
    public suspend fun artists(ifModifiedSince: Long? = null, musicFolderId: String? = null): ArtistsDto

    /**
     * `getIndexes` — the delta-sync entry point. Pass the stored `lastModified` as
     * [ifModifiedSince] and an unchanged library answers with an empty index list, which is the
     * "one request, under 100 ms" budget in REQUIREMENTS.md §"Performance budgets".
     */
    public suspend fun indexes(ifModifiedSince: Long? = null, musicFolderId: String? = null): IndexesDto

    /** `getArtist` — one artist plus its owned albums. [id] is an `ar-<artist MBID>`. */
    public suspend fun artist(id: String): ArtistId3Dto

    /** `getAlbum` — one album plus its songs, in disc then track order. [id] is `al-<rg MBID>`. */
    public suspend fun album(id: String): AlbumId3Dto

    /** `getSong` — one track. [id] is `tr-<file id>`, a fetch handle and not a stable key. */
    public suspend fun song(id: String): ChildDto

    /**
     * `getAlbumList2` — the home rows. `size` is capped at 500 by the server.
     *
     * [fromYear] and [toYear] are required by, and only accepted with, [AlbumListType.ByYear];
     * [genre] likewise with [AlbumListType.ByGenre]. Passing either otherwise is a server-side
     * parameter error.
     */
    public suspend fun albumList2(
        type: AlbumListType,
        size: Int = 20,
        offset: Int = 0,
        fromYear: Int? = null,
        toYear: Int? = null,
        genre: String? = null,
    ): List<AlbumId3Dto>

    /**
     * `search3` — search over the owned library. Runs against the server's metadata, and is the
     * lane Needler mirrors locally so the first keystroke needs no network call.
     */
    public suspend fun search3(
        query: String,
        artistCount: Int = 20,
        artistOffset: Int = 0,
        albumCount: Int = 20,
        albumOffset: Int = 0,
        songCount: Int = 20,
        songOffset: Int = 0,
    ): SearchResult3Dto

    /** `getGenres`. Note each entry's name is under the key `value`. */
    public suspend fun genres(): List<GenreDto>

    /** `getSongsByGenre`. `count` is capped at 500. */
    public suspend fun songsByGenre(genre: String, count: Int = 50, offset: Int = 0): List<ChildDto>

    /** `getScanStatus` — supplies the "last scan 47m ago" line. */
    public suspend fun scanStatus(): ScanStatusDto

    // ------------------------------------------------------------- Favourites

    /** `getStarred2` — the Favourites screen. Binary favourites only; ratings are a no-op here. */
    public suspend fun starred2(): Starred2Dto

    /**
     * `star` — add favourites. [ids] is routed by id prefix, so albums, artists and tracks can be
     * mixed; [albumIds] and [artistIds] are the explicit alternatives. At least one is required.
     */
    public suspend fun star(
        ids: List<String> = emptyList(),
        albumIds: List<String> = emptyList(),
        artistIds: List<String> = emptyList(),
    )

    /** `unstar` — remove favourites. Same parameter shape as [star]. */
    public suspend fun unstar(
        ids: List<String> = emptyList(),
        albumIds: List<String> = emptyList(),
        artistIds: List<String> = emptyList(),
    )

    // -------------------------------------------------------------- Playlists

    /** `getPlaylists`. */
    public suspend fun playlists(): List<PlaylistDto>

    /** `getPlaylist` — one playlist with its entries. */
    public suspend fun playlist(id: String): PlaylistDto

    /**
     * `createPlaylist`.
     *
     * Passing [playlistId] **replaces** that playlist's contents rather than creating a new one,
     * which is the protocol's own quirk. Pass [name] for a new playlist.
     */
    public suspend fun createPlaylist(
        name: String? = null,
        songIds: List<String> = emptyList(),
        playlistId: String? = null,
    ): PlaylistDto

    /**
     * `updatePlaylist` — rename, re-share, add tracks and remove tracks by **index**.
     *
     * Index-based removal means an offline edit replayed later can remove the wrong row if the
     * playlist moved server-side; the protocol offers no revision or conflict signal, so the
     * write queue replays last-write-wins.
     */
    public suspend fun updatePlaylist(
        playlistId: String,
        name: String? = null,
        comment: String? = null,
        isPublic: Boolean? = null,
        songIdsToAdd: List<String> = emptyList(),
        songIndexesToRemove: List<Int> = emptyList(),
    )

    /** `deletePlaylist`. */
    public suspend fun deletePlaylist(id: String)

    // ------------------------------------------------------------- Scrobbling

    /**
     * `scrobble`.
     *
     * Call with `submission = false` on track start (a now-playing ping, which uses only the first
     * id) and `submission = true` past the halfway point. The server forwards to ListenBrainz or
     * Last.fm according to the user's server-side preferences.
     *
     * @param timesMillis epoch milliseconds, positionally parallel to [ids]. Scrobbles accrued
     *   offline are replayed with their original timestamps, which is exactly what this carries.
     *   Must be either empty or the same length as [ids].
     */
    public suspend fun scrobble(
        ids: List<String>,
        timesMillis: List<Long> = emptyList(),
        submission: Boolean = true,
    )

    // ----------------------------------------------------------------- Lyrics

    /** `getLyricsBySongId` — the `songLyrics:1` extension, which the server does not advertise. */
    public suspend fun lyricsBySongId(id: String): LyricsListDto
}
