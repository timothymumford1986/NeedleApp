package app.needler.core.network.v1

import app.needler.core.network.NetworkError
import app.needler.core.network.v1.dto.ActiveRequestsDto
import app.needler.core.network.v1.dto.AlbumInfoDto
import app.needler.core.network.v1.dto.AlbumRequestDto
import app.needler.core.network.v1.dto.AlbumTracksDto
import app.needler.core.network.v1.dto.AppPasswordCreateResponseDto
import app.needler.core.network.v1.dto.AppPasswordListDto
import app.needler.core.network.v1.dto.ArtistInfoDto
import app.needler.core.network.v1.dto.ArtistReleasesDto
import app.needler.core.network.v1.dto.AuthProvidersDto
import app.needler.core.network.v1.dto.AuthResponseDto
import app.needler.core.network.v1.dto.BatchAlbumRequestDto
import app.needler.core.network.v1.dto.BatchRequestResponseDto
import app.needler.core.network.v1.dto.CancelDownloadDto
import app.needler.core.network.v1.dto.CancelRequestDto
import app.needler.core.network.v1.dto.ConnectAppsSettingsDto
import app.needler.core.network.v1.dto.DeviceSessionResponseDto
import app.needler.core.network.v1.dto.DownloadAccessDto
import app.needler.core.network.v1.dto.DownloadActivitySummaryDto
import app.needler.core.network.v1.dto.DownloadListDto
import app.needler.core.network.v1.dto.LibraryStatsDto
import app.needler.core.network.v1.dto.NewReleasesDto
import app.needler.core.network.v1.dto.RequestAcceptedDto
import app.needler.core.network.v1.dto.RequestHistoryDto
import app.needler.core.network.v1.dto.RetryDownloadDto
import app.needler.core.network.v1.dto.RetryRequestDto
import app.needler.core.network.v1.dto.ScrobblePreferencesDto
import app.needler.core.network.v1.dto.SearchBucketResponseDto
import app.needler.core.network.v1.dto.SearchResponseDto
import app.needler.core.network.v1.dto.SessionListDto
import app.needler.core.network.v1.dto.StatusReportDto
import app.needler.core.network.v1.dto.SuggestResponseDto
import app.needler.core.network.v1.dto.TrackRequestDto
import app.needler.core.network.v1.dto.TrackRequestResponseDto
import app.needler.core.network.v1.dto.UnseenCountDto
import app.needler.core.network.v1.dto.UserDto
import app.needler.core.network.v1.dto.VersionInfoDto
import app.needler.core.network.v1.dto.WantedWatchesDto

/** The two buckets `GET /api/v1/search/{bucket}` accepts. */
public enum class SearchBucket(public val wire: String) {
    Artists("artists"),
    Albums("albums"),
}

/** `request_kind` on the request cancel/retry endpoints. */
public enum class RequestKind(public val wire: String) {
    Album("album"),
    Track("track"),
}

/** `sort` on `GET /api/v1/requests/history`. */
public enum class RequestHistorySort(public val wire: String) {
    Newest("newest"),
    Oldest("oldest"),
    Status("status"),
}

/**
 * The `/api/v1` lane: search, requesting, the download queue, identity and the server's gates.
 *
 * Auth is `Authorization: Bearer <companion token>`, attached by this module's interceptor from
 * [app.needler.core.network.CredentialProvider]. Onboarding is the exception: [login] returns the
 * standard bearer, and the two mint calls that follow take it as an explicit `bearer` argument,
 * because that token is discarded immediately afterwards and is never stored.
 *
 * Every function returns DTOs and throws only [NetworkError]. A `401` here means the 30-day
 * companion session expired: the caller marks the session stale, keeps playback working, and
 * prompts for re-authentication. It never blocks the Subsonic lane.
 *
 * All calls require a saved server in the credential provider; without one they throw
 * [IllegalStateException], which is a programming error rather than a runtime condition.
 */
public interface V1Api {

    // ---------------------------------------------------------------- Connect

    /**
     * `GET /api/v1/version`.
     *
     * Note this endpoint is **authenticated** on the real server, so it cannot be the pre-sign-in
     * reachability probe REQUIREMENTS.md §"Accepted URL forms" describes; use [authProviders] for
     * that and call this one after sign-in.
     */
    public suspend fun version(): VersionInfoDto

    /** `GET /api/v1/status` — per-service health, `download_client` and `library`. */
    public suspend fun status(): StatusReportDto

    /**
     * `GET /api/v1/connect-apps/settings`.
     *
     * `subsonic_enabled` here is the gate the whole library and playback lane depends on, and it
     * defaults to off. `transcoding_enabled` decides whether "MP3 320 on mobile data" may be shown.
     */
    public suspend fun connectAppsSettings(): ConnectAppsSettingsDto

    /**
     * `GET /api/v1/auth/providers` — public, no credentials.
     *
     * This is the reachability probe for the Connect screen: a wrong URL fails here rather than
     * later, and it does not need a token.
     */
    public suspend fun authProviders(): AuthProvidersDto

    // ------------------------------------------------------------------- Auth

    /** `POST /api/v1/auth/login` with the account password. Returns the standard bearer. */
    public suspend fun login(username: String, password: String): AuthResponseDto

    /**
     * `POST /api/v1/auth/device-sessions` — mints the 30-day companion bearer.
     *
     * @param bearer the standard bearer from [login]. A companion session cannot mint another
     *   device session (the server answers `403`), so this must be the login token.
     */
    public suspend fun createDeviceSession(deviceName: String, bearer: String? = null): DeviceSessionResponseDto

    /** `GET /api/v1/auth/me` — the caller's identity and role (`admin`, `trusted`, `user`). */
    public suspend fun me(bearer: String? = null): UserDto

    /** `GET /api/v1/auth/sessions` — the session list the user can revoke from. */
    public suspend fun sessions(): SessionListDto

    /** `DELETE /api/v1/auth/sessions/{sessionId}`. */
    public suspend fun revokeSession(sessionId: String)

    /** `GET /api/v1/connect-apps/app-passwords` — 25-slot roster, used to find an existing `Needler`. */
    public suspend fun appPasswords(bearer: String? = null): AppPasswordListDto

    /**
     * `POST /api/v1/connect-apps/app-passwords`.
     *
     * The secret is returned exactly once and is never re-fetchable, so a failed write to
     * Keystore-backed storage must abort onboarding and revoke it again.
     */
    public suspend fun createAppPassword(name: String = "Needler", bearer: String? = null): AppPasswordCreateResponseDto

    /** `DELETE /api/v1/connect-apps/app-passwords/{id}`. Used to rotate the `Needler` slot. */
    public suspend fun revokeAppPassword(appPasswordId: String, bearer: String? = null)

    // --------------------------------------------------------- Catalogue search

    /**
     * `GET /api/v1/search` — the MusicBrainz catalogue half of unified search.
     *
     * Slow relative to local `search3`, so callers run it after a 300 ms debounce and merge on
     * release-group MBID. `service_status` on the response reports upstream degradation.
     */
    public suspend fun search(
        query: String,
        limitArtists: Int = 10,
        limitAlbums: Int = 10,
        buckets: Set<SearchBucket>? = null,
    ): SearchResponseDto

    /** `GET /api/v1/search/{bucket}` — paging one bucket. There is no total count. */
    public suspend fun searchBucket(
        bucket: SearchBucket,
        query: String,
        limit: Int = 50,
        offset: Int = 0,
    ): SearchBucketResponseDto

    /** `GET /api/v1/search/suggest` — completions. `query` must be at least two characters. */
    public suspend fun suggest(query: String, limit: Int = 5): SuggestResponseDto

    // ------------------------------------------------- Album and artist detail

    /** `GET /api/v1/albums/{releaseGroupMbid}`. */
    public suspend fun album(releaseGroupMbid: String): AlbumInfoDto

    /** `GET /api/v1/albums/{releaseGroupMbid}/tracks`. */
    public suspend fun albumTracks(releaseGroupMbid: String): AlbumTracksDto

    /** `GET /api/v1/artists/{artistMbid}`. */
    public suspend fun artist(artistMbid: String): ArtistInfoDto

    /**
     * `GET /api/v1/artists/{artistMbid}/releases` — the full discography, owned or not.
     * Joined with the mirror's owned albums on release-group MBID for artist detail.
     */
    public suspend fun artistReleases(artistMbid: String, limit: Int = 50, offset: Int = 0): ArtistReleasesDto

    // --------------------------------------------------------------- Requests

    /** `POST /api/v1/requests/new` (HTTP 202). Render the returned `status`, never infer it. */
    public suspend fun requestAlbum(request: AlbumRequestDto): RequestAcceptedDto

    /**
     * `POST /api/v1/requests/batch` (HTTP 202). At most
     * [BatchAlbumRequestDto.MAX_ITEMS] items — more is a `422`, so chunk before calling.
     */
    public suspend fun requestAlbums(request: BatchAlbumRequestDto): BatchRequestResponseDto

    /** `POST /api/v1/tracks/{recordingMbid}/request` — keyed on the recording MBID. */
    public suspend fun requestTrack(recordingMbid: String, request: TrackRequestDto): TrackRequestResponseDto

    /** `DELETE /api/v1/requests/active/{musicbrainzId}`. A refusal is 200 with `success=false`. */
    public suspend fun cancelRequest(musicbrainzId: String, kind: RequestKind = RequestKind.Album): CancelRequestDto

    /** `POST /api/v1/requests/retry/{musicbrainzId}`. */
    public suspend fun retryRequest(musicbrainzId: String, kind: RequestKind = RequestKind.Album): RetryRequestDto

    /** `GET /api/v1/requests/active` — includes the user's own pending approvals. No paging. */
    public suspend fun activeRequests(): ActiveRequestsDto

    /** `GET /api/v1/requests/history`. */
    public suspend fun requestHistory(
        page: Int = 1,
        pageSize: Int = 20,
        status: String? = null,
        sort: RequestHistorySort? = null,
    ): RequestHistoryDto

    /** `GET /api/v1/requests/wanted` — standing watches for music not yet found. No paging. */
    public suspend fun wantedRequests(): WantedWatchesDto

    // ------------------------------------------------------- Download queue

    /** `GET /api/v1/downloads` — the full task list, polled every 2 s while Pulls is foregrounded. */
    public suspend fun downloads(
        status: String? = null,
        releaseGroupMbid: String? = null,
        page: Int = 1,
        pageSize: Int = 20,
    ): DownloadListDto

    /** `GET /api/v1/downloads/activity-summary` — the cheap poll; compare `revision` first. */
    public suspend fun downloadActivitySummary(): DownloadActivitySummaryDto

    /**
     * `POST /api/v1/downloads/{taskId}/cancel`.
     * @throws NetworkError.Forbidden for another user's task, [NetworkError.NotFound] if it is gone.
     */
    public suspend fun cancelDownload(taskId: String): CancelDownloadDto

    /**
     * `POST /api/v1/downloads/{taskId}/retry`. Only `failed`, `cancelled` and `partial` are
     * retryable; anything else is [NetworkError.InvalidRequest]. Returns the **new** task id.
     */
    public suspend fun retryDownload(taskId: String): RetryDownloadDto

    // ------------------------------------------------ Following notifications

    /** `GET /api/v1/following/new-releases`. */
    public suspend fun newReleases(limit: Int = 50, offset: Int = 0): NewReleasesDto

    /** `GET /api/v1/following/new-releases/unseen-count` — drives the third notification. */
    public suspend fun unseenNewReleaseCount(): UnseenCountDto

    /** `POST /api/v1/following/new-releases/seen` — no request body; always answers `count: 0`. */
    public suspend fun markNewReleasesSeen(): UnseenCountDto

    // ------------------------------------------------------- Gates and totals

    /**
     * `GET /api/v1/download/access` — the admin gate on library download.
     * When `allowed` is false, hide every pin and "Pull local" affordance.
     */
    public suspend fun downloadAccess(): DownloadAccessDto

    /** `GET /api/v1/library/stats` — authoritative album count and library size for the header. */
    public suspend fun libraryStats(): LibraryStatsDto

    /** `GET /api/v1/me/scrobble-preferences` — label the scrobble toggle with the real target. */
    public suspend fun scrobblePreferences(): ScrobblePreferencesDto
}
