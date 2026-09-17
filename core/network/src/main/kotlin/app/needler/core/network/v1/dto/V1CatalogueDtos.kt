package app.needler.core.network.v1.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/albums/{release_group_mbid}` → `AlbumInfo`.
 *
 * The path id is a MusicBrainz **release-group** MBID — the same value as a Subsonic `al-` id with
 * its prefix stripped. Only provider (MusicBrainz) ids are accepted here.
 */
@Serializable
public data class AlbumInfoDto(
    @SerialName("title") val title: String = "",
    @SerialName("musicbrainz_id") val musicbrainzId: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("artist_id") val artistId: String = "",
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("barcode") val barcode: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("disambiguation") val disambiguation: String? = null,
    @SerialName("tracks") val tracks: List<CatalogueTrackDto> = emptyList(),
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("total_length") val totalLength: Long? = null,
    @SerialName("in_library") val inLibrary: Boolean = false,
    @SerialName("requested") val requested: Boolean = false,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("album_thumb_url") val albumThumbUrl: String? = null,
    @SerialName("service_status") val serviceStatus: Map<String, String>? = null,
    @SerialName("selected_release_mbid") val selectedReleaseMbid: String? = null,
    @SerialName("pick_basis") val pickBasis: String? = null,
)

/** `GET /api/v1/albums/{release_group_mbid}/tracks` → `AlbumTracksInfo`. */
@Serializable
public data class AlbumTracksDto(
    @SerialName("tracks") val tracks: List<CatalogueTrackDto> = emptyList(),
    @SerialName("total_tracks") val totalTracks: Int = 0,
    @SerialName("total_length") val totalLength: Long? = null,
    @SerialName("label") val label: String? = null,
    @SerialName("barcode") val barcode: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("selected_release_mbid") val selectedReleaseMbid: String? = null,
)

/**
 * A catalogue (MusicBrainz) track, not an owned file.
 *
 * [recordingId] is the recording MBID `POST /api/v1/tracks/{recording_mbid}/request` is keyed on,
 * and one of the three components of Needler's stable offline cache key.
 */
@Serializable
public data class CatalogueTrackDto(
    @SerialName("position") val position: Int = 0,
    @SerialName("title") val title: String = "",
    @SerialName("disc_number") val discNumber: Int = 1,
    /** Length in milliseconds, as MusicBrainz reports it. */
    @SerialName("length") val length: Long? = null,
    @SerialName("recording_id") val recordingId: String? = null,
    @SerialName("release_track_id") val releaseTrackId: String? = null,
    @SerialName("media_format") val mediaFormat: String? = null,
)

/**
 * `GET /api/v1/artists/{artist_mbid}` → `ArtistInfo`.
 *
 * `followed`, `auto_download` and `auto_download_state` are omitted deliberately: the struct is
 * globally cached server-side so they are always their defaults here. Per-user follow state comes
 * from `GET /api/v1/artists/{artist_mbid}/follow`, which v1 of Needler does not need.
 */
@Serializable
public data class ArtistInfoDto(
    @SerialName("name") val name: String = "",
    @SerialName("musicbrainz_id") val musicbrainzId: String = "",
    @SerialName("disambiguation") val disambiguation: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("life_span") val lifeSpan: LifeSpanDto? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("image") val image: String? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("fanart_url") val fanartUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("tags") val tags: List<String> = emptyList(),
    @SerialName("aliases") val aliases: List<String> = emptyList(),
    @SerialName("in_library") val inLibrary: Boolean = false,
    @SerialName("appears_in_library") val appearsInLibrary: Boolean = false,
    @SerialName("albums") val albums: List<ReleaseItemDto> = emptyList(),
    @SerialName("singles") val singles: List<ReleaseItemDto> = emptyList(),
    @SerialName("eps") val eps: List<ReleaseItemDto> = emptyList(),
    @SerialName("release_group_count") val releaseGroupCount: Int = 0,
    @SerialName("service_status") val serviceStatus: Map<String, String>? = null,
)

@Serializable
public data class LifeSpanDto(
    @SerialName("begin") val begin: String? = null,
    @SerialName("end") val end: String? = null,
    /** A string on the wire, not a boolean. */
    @SerialName("ended") val ended: String? = null,
)

/**
 * `GET /api/v1/artists/{artist_mbid}/releases` → `ArtistReleases`.
 *
 * This is the un-owned half of artist detail: it is joined with the mirror's owned albums on
 * release-group MBID, which is where the two lanes meet (REQUIREMENTS.md §"Where the two lanes
 * meet"). [warming] means the discography is still resolving upstream, and
 * [sourceTotalCount] is null while that is true.
 *
 * The three buckets are `albums`, `singles` and **`eps`** (lower-case).
 */
@Serializable
public data class ArtistReleasesDto(
    @SerialName("albums") val albums: List<ReleaseItemDto> = emptyList(),
    @SerialName("singles") val singles: List<ReleaseItemDto> = emptyList(),
    @SerialName("eps") val eps: List<ReleaseItemDto> = emptyList(),
    @SerialName("offset") val offset: Int = 0,
    @SerialName("limit") val limit: Int = 50,
    @SerialName("returned_count") val returnedCount: Int = 0,
    @SerialName("next_offset") val nextOffset: Int? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("source_total_count") val sourceTotalCount: Int? = null,
    @SerialName("warming") val warming: Boolean = false,
    @SerialName("service_status") val serviceStatus: Map<String, String>? = null,
)

/** One release group in a discography. [id] is the release-group MBID. */
@Serializable
public data class ReleaseItemDto(
    @SerialName("id") val id: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("type") val type: String? = null,
    @SerialName("first_release_date") val firstReleaseDate: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("in_library") val inLibrary: Boolean = false,
    @SerialName("requested") val requested: Boolean = false,
)
