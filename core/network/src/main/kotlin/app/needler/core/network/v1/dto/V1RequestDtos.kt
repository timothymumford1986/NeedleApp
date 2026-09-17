package app.needler.core.network.v1.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/v1/requests/new`.
 *
 * [musicbrainzId] is the release-group MBID — a Subsonic `al-` id with the prefix stripped.
 * There is no quality field: quality is a server-side policy, which is why "Prefer FLAC" can only
 * be shown read-only. [monitorArtist] is the cheap artist-following toggle on the request sheet.
 *
 * Note the short field names `artist` and `album` here; the batch item uses `artist_name` and
 * `album_title` instead.
 */
@Serializable
public data class AlbumRequestDto(
    @SerialName("musicbrainz_id") val musicbrainzId: String,
    @SerialName("artist") val artist: String? = null,
    @SerialName("album") val album: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("artist_mbid") val artistMbid: String? = null,
    @SerialName("monitor_artist") val monitorArtist: Boolean = false,
    @SerialName("auto_download_artist") val autoDownloadArtist: Boolean = false,
)

/**
 * `POST /api/v1/requests/new` → `RequestAcceptedResponse`, HTTP **202**.
 *
 * [status] must be rendered as returned rather than inferred from the cached role: observed
 * values are `pending`, `awaiting_approval`, `failed`, or the existing status when joining an
 * in-flight request. [qualitySnapshotSummary] is the policy that will be applied — the honest,
 * read-only answer to screen 12's "Prefer FLAC".
 */
@Serializable
public data class RequestAcceptedDto(
    @SerialName("success") val success: Boolean = false,
    @SerialName("message") val message: String = "",
    @SerialName("musicbrainz_id") val musicbrainzId: String = "",
    @SerialName("status") val status: String = "pending",
    @SerialName("quality_snapshot_summary") val qualitySnapshotSummary: String? = null,
)

/** One item of `POST /api/v1/requests/batch`. Note `artist_name` / `album_title` here. */
@Serializable
public data class BatchAlbumItemDto(
    @SerialName("musicbrainz_id") val musicbrainzId: String,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_title") val albumTitle: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("artist_mbid") val artistMbid: String? = null,
)

/**
 * Body of `POST /api/v1/requests/batch`.
 *
 * The 500-item cap is enforced at decode time on the server: 501 items is a `422`, not an
 * overflow count, so the caller must chunk. See [BatchRequestResponseDto.overflow].
 */
@Serializable
public data class BatchAlbumRequestDto(
    @SerialName("items") val items: List<BatchAlbumItemDto>,
    @SerialName("monitor_artist") val monitorArtist: Boolean = false,
    @SerialName("auto_download_artist") val autoDownloadArtist: Boolean = false,
) {
    public companion object {
        /** Server-enforced maximum number of items per batch. */
        public const val MAX_ITEMS: Int = 500
    }
}

/**
 * `POST /api/v1/requests/batch` → `BatchRequestResponse`, HTTP **202**.
 *
 * [overflow] exists in the contract but the server never sets it to anything but `0`; do not
 * build UI on it. [status] is one of `pending`, `awaiting_approval`, `already_requested`, `failed`.
 */
@Serializable
public data class BatchRequestResponseDto(
    @SerialName("success") val success: Boolean = false,
    @SerialName("message") val message: String = "",
    @SerialName("requested") val requested: Int = 0,
    @SerialName("skipped") val skipped: Int = 0,
    @SerialName("overflow") val overflow: Int = 0,
    @SerialName("status") val status: String = "pending",
)

/**
 * Body of `POST /api/v1/tracks/{recording_mbid}/request`.
 *
 * [releaseId] is the one field on this whole surface whose wire name differs from the server's
 * internal name (`release_mbid`): send `release_id`.
 */
@Serializable
public data class TrackRequestDto(
    @SerialName("artist_name") val artistName: String,
    @SerialName("track_title") val trackTitle: String,
    @SerialName("album_title") val albumTitle: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("release_group_mbid") val releaseGroupMbid: String? = null,
    @SerialName("artist_mbid") val artistMbid: String? = null,
    @SerialName("release_id") val releaseId: String? = null,
)

/** `POST /api/v1/tracks/{recording_mbid}/request` → `TrackRequestResponse`. */
@Serializable
public data class TrackRequestResponseDto(
    /** `awaiting_approval` | `queued` | `already_in_library`. */
    @SerialName("status") val status: String = "",
    /** May be null even when queued. */
    @SerialName("task_id") val taskId: String? = null,
)

/**
 * `DELETE /api/v1/requests/active/{musicbrainz_id}` → `CancelRequestResponse`.
 *
 * A refusal is HTTP 200 with `success=false` (for example
 * `Cannot cancel request with status 'processing'`); only a cross-user cancel is a `403`.
 */
@Serializable
public data class CancelRequestDto(
    @SerialName("success") val success: Boolean = false,
    @SerialName("message") val message: String = "",
)

/** `POST /api/v1/requests/retry/{musicbrainz_id}` → `RetryRequestResponse`. Same 200/false shape. */
@Serializable
public data class RetryRequestDto(
    @SerialName("success") val success: Boolean = false,
    @SerialName("message") val message: String = "",
)

/** `GET /api/v1/requests/active` → `ActiveRequestsResponse`. No paging. */
@Serializable
public data class ActiveRequestsDto(
    @SerialName("items") val items: List<ActiveRequestItemDto> = emptyList(),
    @SerialName("count") val count: Int = 0,
)

/**
 * One of the user's own in-flight requests, including those waiting for admin approval — the
 * "Waiting" badge on screens 03 and 10.
 *
 * [requestedAt] and [eta] are ISO-8601 strings here (the `/downloads` lane uses epoch floats).
 */
@Serializable
public data class ActiveRequestItemDto(
    @SerialName("musicbrainz_id") val musicbrainzId: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("album_title") val albumTitle: String = "",
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("status") val status: String = "",
    @SerialName("artist_mbid") val artistMbid: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("progress") val progress: Double? = null,
    @SerialName("eta") val eta: String? = null,
    @SerialName("size") val size: Double? = null,
    @SerialName("size_remaining") val sizeRemaining: Double? = null,
    @SerialName("download_status") val downloadStatus: String? = null,
    @SerialName("download_state") val downloadState: String? = null,
    @SerialName("status_messages") val statusMessages: List<StatusMessageDto>? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("library_queue_id") val libraryQueueId: Int? = null,
    @SerialName("quality") val quality: String? = null,
    @SerialName("protocol") val protocol: String? = null,
    @SerialName("download_client") val downloadClient: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("requested_by_name") val requestedByName: String? = null,
    /** `album` | `track`. */
    @SerialName("request_kind") val requestKind: String = "album",
    @SerialName("track_title") val trackTitle: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("track_release_group_mbid") val trackReleaseGroupMbid: String? = null,
)

@Serializable
public data class StatusMessageDto(
    @SerialName("title") val title: String? = null,
    @SerialName("messages") val messages: List<String> = emptyList(),
)

/** `GET /api/v1/requests/history` → `RequestHistoryResponse`. The only paged request list. */
@Serializable
public data class RequestHistoryDto(
    @SerialName("items") val items: List<RequestHistoryItemDto> = emptyList(),
    @SerialName("total") val total: Int = 0,
    @SerialName("page") val page: Int = 1,
    @SerialName("page_size") val pageSize: Int = 20,
    @SerialName("total_pages") val totalPages: Int = 0,
)

@Serializable
public data class RequestHistoryItemDto(
    @SerialName("musicbrainz_id") val musicbrainzId: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("album_title") val albumTitle: String = "",
    @SerialName("requested_at") val requestedAt: String? = null,
    @SerialName("status") val status: String = "",
    @SerialName("artist_mbid") val artistMbid: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("in_library") val inLibrary: Boolean = false,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("requested_by_name") val requestedByName: String? = null,
    @SerialName("reviewed_by_name") val reviewedByName: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("download_task_id") val downloadTaskId: String? = null,
    @SerialName("can_reimport") val canReimport: Boolean = false,
    @SerialName("request_kind") val requestKind: String = "album",
    @SerialName("track_title") val trackTitle: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("track_release_group_mbid") val trackReleaseGroupMbid: String? = null,
)

/**
 * `GET /api/v1/requests/wanted` → `WantedWatchesResponse`. No paging.
 * [count] counts [items] only; it does not include [retrying].
 */
@Serializable
public data class WantedWatchesDto(
    @SerialName("items") val items: List<WantedWatchItemDto> = emptyList(),
    @SerialName("count") val count: Int = 0,
    @SerialName("retrying") val retrying: List<WantedRetryingItemDto> = emptyList(),
)

/** A standing watch for music the server could not find yet. All `*_at` values are epoch seconds. */
@Serializable
public data class WantedWatchItemDto(
    @SerialName("release_group_mbid") val releaseGroupMbid: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("album_title") val albumTitle: String = "",
    /** `missing` | `partial`. */
    @SerialName("kind") val kind: String = "",
    /** `watching` | `dormant` | `stopped` | `fulfilled`. */
    @SerialName("state") val state: String = "",
    @SerialName("check_count") val checkCount: Int = 0,
    @SerialName("next_check_at") val nextCheckAt: Double? = null,
    @SerialName("new_candidate_count") val newCandidateCount: Int = 0,
    @SerialName("created_at") val createdAt: Double? = null,
    @SerialName("artist_mbid") val artistMbid: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("first_release_date") val firstReleaseDate: String? = null,
    @SerialName("last_checked_at") val lastCheckedAt: Double? = null,
    @SerialName("last_outcome") val lastOutcome: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_name") val userName: String? = null,
)

@Serializable
public data class WantedRetryingItemDto(
    @SerialName("release_group_mbid") val releaseGroupMbid: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("album_title") val albumTitle: String = "",
    @SerialName("retry_count") val retryCount: Int = 0,
    @SerialName("max_attempts") val maxAttempts: Int = 0,
    @SerialName("next_retry_at") val nextRetryAt: Double? = null,
    @SerialName("artist_mbid") val artistMbid: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("cover_url") val coverUrl: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_name") val userName: String? = null,
)
