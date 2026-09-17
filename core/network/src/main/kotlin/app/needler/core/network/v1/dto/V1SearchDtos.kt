package app.needler.core.network.v1.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/search` → `SearchResponse`.
 *
 * [serviceStatus] is the upstream-degradation map REQUIREMENTS.md wants surfaced as a quiet
 * inline note: `{"musicbrainz": "degraded"}`, and `null` when nothing is degraded.
 * There is no total count and no query echo.
 */
@Serializable
public data class SearchResponseDto(
    @SerialName("artists") val artists: List<SearchResultDto> = emptyList(),
    @SerialName("albums") val albums: List<SearchResultDto> = emptyList(),
    @SerialName("top_artist") val topArtist: SearchResultDto? = null,
    @SerialName("top_album") val topAlbum: SearchResultDto? = null,
    /** source name → `degraded` | `error`. */
    @SerialName("service_status") val serviceStatus: Map<String, String>? = null,
    /** bucket name → `ok` | `partial` | `timeout` | `error` | `stale`. */
    @SerialName("bucket_status") val bucketStatus: Map<String, String>? = null,
)

/**
 * One catalogue hit. [musicbrainzId] is an **artist MBID** when [type] is `artist` and a
 * **release-group MBID** when [type] is `album` — the same key `POST /api/v1/requests/new` takes
 * and the same value behind a Subsonic `al-`/`ar-` id.
 */
@Serializable
public data class SearchResultDto(
    /** `artist` | `album`. */
    @SerialName("type") val type: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("musicbrainz_id") val musicbrainzId: String = "",
    @SerialName("artist") val artist: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("in_library") val inLibrary: Boolean = false,
    @SerialName("requested") val requested: Boolean = false,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("album_thumb_url") val albumThumbUrl: String? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("fanart_url") val fanartUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("disambiguation") val disambiguation: String? = null,
    @SerialName("type_info") val typeInfo: String? = null,
    @SerialName("score") val score: Int = 0,
)

/**
 * `GET /api/v1/search/{bucket}` → `SearchBucketResponse`, for paging a single bucket.
 * Only `artists` and `albums` are legal buckets. [topResult] is only set when `offset == 0`.
 */
@Serializable
public data class SearchBucketResponseDto(
    @SerialName("bucket") val bucket: String = "",
    @SerialName("limit") val limit: Int = 0,
    @SerialName("offset") val offset: Int = 0,
    @SerialName("results") val results: List<SearchResultDto> = emptyList(),
    @SerialName("top_result") val topResult: SearchResultDto? = null,
    /** `ok` | `partial` | `timeout` | `error` | `stale`. */
    @SerialName("status") val status: String = "ok",
)

/** `GET /api/v1/search/suggest` → `SuggestResponse`. Needs `q` of at least two characters. */
@Serializable
public data class SuggestResponseDto(
    @SerialName("results") val results: List<SuggestResultDto> = emptyList(),
    @SerialName("remote_status") val remoteStatus: String = "ok",
)

/** A completion. Carries no artwork fields, unlike [SearchResultDto]. */
@Serializable
public data class SuggestResultDto(
    /** `artist` | `album`. */
    @SerialName("type") val type: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("musicbrainz_id") val musicbrainzId: String = "",
    @SerialName("artist") val artist: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("in_library") val inLibrary: Boolean = false,
    @SerialName("requested") val requested: Boolean = false,
    @SerialName("disambiguation") val disambiguation: String? = null,
    @SerialName("score") val score: Int = 0,
)
