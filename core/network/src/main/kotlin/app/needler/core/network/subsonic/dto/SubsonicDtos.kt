package app.needler.core.network.subsonic.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * OpenSubsonic 1.16.1 wire types, as DroppedNeedle's compat shim emits them.
 *
 * Unlike the `/api/v1` lane these are camelCase, and the server strips nulls before serialising,
 * so almost everything is optional. Every field still carries an explicit @SerialName so a Kotlin
 * rename cannot break the wire contract, and every DTO tolerates unknown keys.
 *
 * Subsonic ids are type-prefixed and built from MusicBrainz ids:
 *   `al-<release-group MBID>`, `ar-<artist MBID>`, `tr-<file id>`, `pl-<playlist id>`,
 *   `ge-<genre slug>`. Strip `al-` and the remainder is exactly the key
 *   `POST /api/v1/requests/new` accepts. `tr-<file id>` is NOT stable across quality upgrades and
 *   must never be used as an offline cache key.
 */

/** The `error` object inside a `status=failed` envelope. */
@Serializable
public data class SubsonicErrorDto(
    @SerialName("code") val code: Int = 0,
    @SerialName("message") val message: String? = null,
)

/**
 * Envelope metadata, returned by `ping`. [openSubsonic] being true is what marks the server as
 * OpenSubsonic rather than plain Subsonic.
 */
@Serializable
public data class SubsonicServerInfoDto(
    @SerialName("status") val status: String = "",
    /** Protocol version, `1.16.1`. */
    @SerialName("version") val version: String = "",
    /** Server product name, from the server's `advertise_server_name`. */
    @SerialName("type") val type: String? = null,
    @SerialName("serverVersion") val serverVersion: String? = null,
    @SerialName("openSubsonic") val openSubsonic: Boolean = false,
)

/**
 * One entry of `getOpenSubsonicExtensions`.
 *
 * DroppedNeedle advertises `apiKeyAuthentication`, `formPost`, `transcodeOffset` and
 * `transcoding`. Anything absent from the response must be treated as unavailable.
 */
@Serializable
public data class OpenSubsonicExtensionDto(
    @SerialName("name") val name: String = "",
    @SerialName("versions") val versions: List<Int> = emptyList(),
)

// ----------------------------------------------------------------- Artists

/** `getArtists` → `artists`. */
@Serializable
public data class ArtistsDto(
    @SerialName("ignoredArticles") val ignoredArticles: String? = null,
    @SerialName("index") val index: List<ArtistIndexDto> = emptyList(),
)

@Serializable
public data class ArtistIndexDto(
    /** The index letter, `A`…`Z` or `#`, for the alphabetical jump list. */
    @SerialName("name") val name: String = "",
    @SerialName("artist") val artist: List<ArtistId3Dto> = emptyList(),
)

/** `getArtist` → `artist`, and every artist entry elsewhere. [album] is populated by `getArtist`. */
@Serializable
public data class ArtistId3Dto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("coverArt") val coverArt: String? = null,
    @SerialName("albumCount") val albumCount: Int? = null,
    /** ISO-8601 UTC instant the user starred this, or null. */
    @SerialName("starred") val starred: String? = null,
    @SerialName("musicBrainzId") val musicBrainzId: String? = null,
    @SerialName("sortName") val sortName: String? = null,
    @SerialName("album") val album: List<AlbumId3Dto> = emptyList(),
)

/** `getIndexes` → `indexes`. The only endpoint that actually honours `ifModifiedSince`. */
@Serializable
public data class IndexesDto(
    /** The server's library revision; feed it back as `ifModifiedSince` on the next delta sync. */
    @SerialName("lastModified") val lastModified: Long = 0,
    @SerialName("ignoredArticles") val ignoredArticles: String? = null,
    @SerialName("index") val index: List<FileIndexDto> = emptyList(),
)

@Serializable
public data class FileIndexDto(
    @SerialName("name") val name: String = "",
    @SerialName("artist") val artist: List<ArtistFileDto> = emptyList(),
)

/** The file-structure artist shape used by `getIndexes`: no album count, no MBID. */
@Serializable
public data class ArtistFileDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("starred") val starred: String? = null,
    @SerialName("coverArt") val coverArt: String? = null,
)

// ------------------------------------------------------------------ Albums

/** `getAlbum` → `album`; also every album entry in lists. [song] is populated by `getAlbum`. */
@Serializable
public data class AlbumId3Dto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("artist") val artist: String? = null,
    @SerialName("artistId") val artistId: String? = null,
    @SerialName("coverArt") val coverArt: String? = null,
    @SerialName("songCount") val songCount: Int? = null,
    /** Seconds. */
    @SerialName("duration") val duration: Int? = null,
    @SerialName("playCount") val playCount: Long? = null,
    @SerialName("created") val created: String? = null,
    @SerialName("starred") val starred: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("isCompilation") val isCompilation: Boolean? = null,
    /** The release-group MBID, which is the join key to the catalogue lane. */
    @SerialName("musicBrainzId") val musicBrainzId: String? = null,
    @SerialName("played") val played: String? = null,
    @SerialName("genres") val genres: List<ItemGenreDto> = emptyList(),
    @SerialName("artists") val artists: List<ArtistId3Dto> = emptyList(),
    @SerialName("displayArtist") val displayArtist: String? = null,
    @SerialName("releaseTypes") val releaseTypes: List<String> = emptyList(),
    @SerialName("sortName") val sortName: String? = null,
    @SerialName("originalReleaseDate") val originalReleaseDate: ItemDateDto? = null,
    @SerialName("discTitles") val discTitles: List<DiscTitleDto> = emptyList(),
    @SerialName("song") val song: List<ChildDto> = emptyList(),
)

/** `getAlbumList2` → `albumList2`. */
@Serializable
public data class AlbumList2Dto(
    @SerialName("album") val album: List<AlbumId3Dto> = emptyList(),
)

@Serializable
public data class ItemGenreDto(
    @SerialName("name") val name: String = "",
)

@Serializable
public data class ItemDateDto(
    @SerialName("year") val year: Int = 0,
    @SerialName("month") val month: Int? = null,
    @SerialName("day") val day: Int? = null,
)

@Serializable
public data class DiscTitleDto(
    @SerialName("disc") val disc: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("coverArt") val coverArt: String? = null,
)

// ------------------------------------------------------------------- Songs

/**
 * A song (or, in the file-structure endpoints, a directory). `getSong` → `song`.
 *
 * [id] is `tr-<file id>`: the current fetch handle only. DroppedNeedle upgrades files in place, so
 * a change in [id], [size], [duration] or [suffix] means cached bytes are stale and must be
 * evicted (REQUIREMENTS.md §"Track identity is not stable").
 *
 * [transcodedContentType] and [transcodedSuffix] are only present when the server has transcoding
 * enabled and ffmpeg available — a second signal beside the `transcoding` extension.
 */
@Serializable
public data class ChildDto(
    @SerialName("id") val id: String = "",
    @SerialName("isDir") val isDir: Boolean = false,
    @SerialName("title") val title: String = "",
    @SerialName("parent") val parent: String? = null,
    @SerialName("album") val album: String? = null,
    @SerialName("artist") val artist: String? = null,
    @SerialName("track") val track: Int? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("genre") val genre: String? = null,
    @SerialName("coverArt") val coverArt: String? = null,
    @SerialName("size") val size: Long? = null,
    @SerialName("contentType") val contentType: String? = null,
    /** File format, e.g. `flac`, `mp3` — the FLAC / MP3 320 badge on screen 13. */
    @SerialName("suffix") val suffix: String? = null,
    @SerialName("transcodedContentType") val transcodedContentType: String? = null,
    @SerialName("transcodedSuffix") val transcodedSuffix: String? = null,
    /** Seconds. */
    @SerialName("duration") val duration: Int? = null,
    @SerialName("bitRate") val bitRate: Int? = null,
    @SerialName("path") val path: String? = null,
    @SerialName("discNumber") val discNumber: Int? = null,
    @SerialName("created") val created: String? = null,
    @SerialName("starred") val starred: String? = null,
    @SerialName("albumId") val albumId: String? = null,
    @SerialName("artistId") val artistId: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("playCount") val playCount: Long? = null,
    @SerialName("mediaType") val mediaType: String? = null,
    @SerialName("bitDepth") val bitDepth: Int? = null,
    @SerialName("samplingRate") val samplingRate: Int? = null,
    @SerialName("channelCount") val channelCount: Int? = null,
    /** The recording MBID where the server has one — part of the stable cache key. */
    @SerialName("musicBrainzId") val musicBrainzId: String? = null,
    @SerialName("played") val played: String? = null,
    @SerialName("sortName") val sortName: String? = null,
    @SerialName("genres") val genres: List<ItemGenreDto> = emptyList(),
    @SerialName("artists") val artists: List<ArtistId3Dto> = emptyList(),
    @SerialName("displayArtist") val displayArtist: String? = null,
    @SerialName("albumArtists") val albumArtists: List<ArtistId3Dto> = emptyList(),
    @SerialName("displayAlbumArtist") val displayAlbumArtist: String? = null,
    @SerialName("replayGain") val replayGain: ReplayGainDto? = null,
)

@Serializable
public data class ReplayGainDto(
    @SerialName("trackGain") val trackGain: Double? = null,
    @SerialName("albumGain") val albumGain: Double? = null,
    @SerialName("trackPeak") val trackPeak: Double? = null,
    @SerialName("albumPeak") val albumPeak: Double? = null,
)

// ------------------------------------------------------------------ Search

/** `search3` → `searchResult3`. Runs against the owned library only. */
@Serializable
public data class SearchResult3Dto(
    @SerialName("artist") val artist: List<ArtistId3Dto> = emptyList(),
    @SerialName("album") val album: List<AlbumId3Dto> = emptyList(),
    @SerialName("song") val song: List<ChildDto> = emptyList(),
)

// ------------------------------------------------------------------ Genres

/** `getGenres` → `genres`. */
@Serializable
public data class GenresDto(
    @SerialName("genre") val genre: List<GenreDto> = emptyList(),
)

@Serializable
public data class GenreDto(
    /** The genre name. Note the key is `value`, not `name`. */
    @SerialName("value") val value: String = "",
    @SerialName("songCount") val songCount: Int = 0,
    @SerialName("albumCount") val albumCount: Int = 0,
)

/** `getSongsByGenre` → `songsByGenre`. */
@Serializable
public data class SongsByGenreDto(
    @SerialName("song") val song: List<ChildDto> = emptyList(),
)

// --------------------------------------------------------------- Favourites

/**
 * `getStarred2` → `starred2`.
 *
 * Binary favourites are the supported mechanism; `setRating` validates and returns success
 * without persisting anything, so star ratings must not be offered.
 */
@Serializable
public data class Starred2Dto(
    @SerialName("artist") val artist: List<ArtistId3Dto> = emptyList(),
    @SerialName("album") val album: List<AlbumId3Dto> = emptyList(),
    @SerialName("song") val song: List<ChildDto> = emptyList(),
)

// ---------------------------------------------------------------- Playlists

/** `getPlaylists` → `playlists`. */
@Serializable
public data class PlaylistsDto(
    @SerialName("playlist") val playlist: List<PlaylistDto> = emptyList(),
)

/**
 * `getPlaylist` / `createPlaylist` → `playlist`. [entry] is populated by the detail calls only.
 *
 * The server counts and returns only library-linked entries, so [songCount] always matches
 * [entry]'s size on the detail call.
 */
@Serializable
public data class PlaylistDto(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("comment") val comment: String? = null,
    @SerialName("owner") val owner: String? = null,
    @SerialName("public") val isPublic: Boolean? = null,
    @SerialName("songCount") val songCount: Int = 0,
    @SerialName("duration") val duration: Int? = null,
    @SerialName("created") val created: String? = null,
    @SerialName("changed") val changed: String? = null,
    @SerialName("coverArt") val coverArt: String? = null,
    @SerialName("entry") val entry: List<ChildDto> = emptyList(),
)

// --------------------------------------------------------------- Scan state

/** `getScanStatus` → `scanStatus`. Supplies the "last scan 47m ago" line on screens 09 and 12. */
@Serializable
public data class ScanStatusDto(
    @SerialName("scanning") val scanning: Boolean = false,
    @SerialName("count") val count: Long? = null,
)

// ------------------------------------------------------------------ Lyrics

/** `getLyricsBySongId` → `lyricsList` (the `songLyrics:1` extension). Deferred to v2 in the UI. */
@Serializable
public data class LyricsListDto(
    @SerialName("structuredLyrics") val structuredLyrics: List<StructuredLyricsDto> = emptyList(),
)

@Serializable
public data class StructuredLyricsDto(
    @SerialName("lang") val lang: String = "",
    @SerialName("synced") val synced: Boolean = false,
    @SerialName("line") val line: List<LyricsLineDto> = emptyList(),
    @SerialName("displayArtist") val displayArtist: String? = null,
    @SerialName("displayTitle") val displayTitle: String? = null,
    @SerialName("offset") val offset: Double? = null,
)

@Serializable
public data class LyricsLineDto(
    @SerialName("value") val value: String = "",
    /** Milliseconds into the track, when [StructuredLyricsDto.synced] is true. */
    @SerialName("start") val start: Long? = null,
)
