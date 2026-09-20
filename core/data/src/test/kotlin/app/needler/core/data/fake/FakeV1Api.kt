package app.needler.core.data.fake

import app.needler.core.network.v1.RequestHistorySort
import app.needler.core.network.v1.RequestKind
import app.needler.core.network.v1.SearchBucket
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.ActiveRequestsDto
import app.needler.core.network.v1.dto.AlbumInfoDto
import app.needler.core.network.v1.dto.AlbumRequestDto
import app.needler.core.network.v1.dto.AlbumTracksDto
import app.needler.core.network.v1.dto.AppPasswordCreateResponseDto
import app.needler.core.network.v1.dto.AppPasswordDto
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

/**
 * A scriptable `/api/v1` client.
 *
 * Only the calls a test drives are scripted; the rest answer with harmless defaults so a repository
 * under test cannot fail on a call it was not being tested for.
 */
public class FakeV1Api : V1Api {

    public val calls: MutableList<String> = ArrayList()

    public var searchResponse: (String) -> SearchResponseDto = { SearchResponseDto() }
    public var searchBucketResponse: (SearchBucket, Int) -> SearchBucketResponseDto =
        { bucket, offset -> SearchBucketResponseDto(bucket = bucket.wire, offset = offset) }
    public var suggestResponse: (String) -> SuggestResponseDto = { SuggestResponseDto() }
    public var artistReleasesResponse: (String) -> ArtistReleasesDto = { ArtistReleasesDto() }
    public var requestAlbumResponse: (AlbumRequestDto) -> RequestAcceptedDto =
        { RequestAcceptedDto(success = true, musicbrainzId = it.musicbrainzId, status = "pending") }
    public var requestAlbumsResponse: (BatchAlbumRequestDto) -> BatchRequestResponseDto =
        { BatchRequestResponseDto(success = true, requested = it.items.size, status = "pending") }
    public var cancelRequestResponse: (String) -> CancelRequestDto = { CancelRequestDto(success = true) }
    public var retryRequestResponse: (String) -> RetryRequestDto = { RetryRequestDto(success = true) }
    public var downloadsResponse: (Int) -> DownloadListDto = { DownloadListDto(page = it) }
    public var activeRequestsResponse: () -> ActiveRequestsDto = { ActiveRequestsDto() }
    public var activitySummaryResponse: () -> DownloadActivitySummaryDto =
        { DownloadActivitySummaryDto() }
    public var retryDownloadResponse: (String) -> RetryDownloadDto =
        { RetryDownloadDto(success = true, taskId = "new-task") }
    public var libraryStatsResponse: () -> LibraryStatsDto = { LibraryStatsDto() }
    public var scrobblePreferencesResponse: () -> ScrobblePreferencesDto = { ScrobblePreferencesDto() }
    public var failWith: (() -> Throwable)? = null

    private fun record(name: String) {
        calls.add(name)
        failWith?.let { throw it() }
    }

    override suspend fun version(): VersionInfoDto {
        record("version")
        return VersionInfoDto(version = "1.0.0")
    }

    override suspend fun status(): StatusReportDto {
        record("status")
        return StatusReportDto()
    }

    override suspend fun connectAppsSettings(): ConnectAppsSettingsDto {
        record("connectAppsSettings")
        return ConnectAppsSettingsDto(subsonicEnabled = true)
    }

    override suspend fun authProviders(): AuthProvidersDto {
        record("authProviders")
        return AuthProvidersDto(local = true)
    }

    override suspend fun login(username: String, password: String): AuthResponseDto {
        record("login")
        return AuthResponseDto(token = "login-token", user = UserDto(id = "u1", role = "admin"))
    }

    override suspend fun createDeviceSession(
        deviceName: String,
        bearer: String?,
    ): DeviceSessionResponseDto {
        record("createDeviceSession(" + deviceName + ")")
        return DeviceSessionResponseDto(
            token = "companion-token",
            user = UserDto(id = "u1", role = "admin", username = "yourname"),
        )
    }

    override suspend fun me(bearer: String?): UserDto {
        record("me")
        return UserDto(id = "u1", role = "admin", username = "yourname")
    }

    override suspend fun sessions(): SessionListDto {
        record("sessions")
        return SessionListDto()
    }

    override suspend fun revokeSession(sessionId: String) {
        record("revokeSession")
    }

    override suspend fun appPasswords(bearer: String?): AppPasswordListDto {
        record("appPasswords")
        return AppPasswordListDto()
    }

    override suspend fun createAppPassword(
        name: String,
        bearer: String?,
    ): AppPasswordCreateResponseDto {
        record("createAppPassword(" + name + ")")
        return AppPasswordCreateResponseDto(
            secret = "secret",
            appPassword = AppPasswordDto(id = "ap1", name = name),
        )
    }

    override suspend fun revokeAppPassword(appPasswordId: String, bearer: String?) {
        record("revokeAppPassword(" + appPasswordId + ")")
    }

    override suspend fun search(
        query: String,
        limitArtists: Int,
        limitAlbums: Int,
        buckets: Set<SearchBucket>?,
    ): SearchResponseDto {
        record("search(" + query + ")")
        return searchResponse(query)
    }

    override suspend fun searchBucket(
        bucket: SearchBucket,
        query: String,
        limit: Int,
        offset: Int,
    ): SearchBucketResponseDto {
        record("searchBucket(" + bucket.wire + "," + offset + ")")
        return searchBucketResponse(bucket, offset)
    }

    override suspend fun suggest(query: String, limit: Int): SuggestResponseDto {
        record("suggest(" + query + ")")
        return suggestResponse(query)
    }

    override suspend fun album(releaseGroupMbid: String): AlbumInfoDto {
        record("album(" + releaseGroupMbid + ")")
        return AlbumInfoDto(musicbrainzId = releaseGroupMbid)
    }

    override suspend fun albumTracks(releaseGroupMbid: String): AlbumTracksDto {
        record("albumTracks")
        return AlbumTracksDto()
    }

    override suspend fun artist(artistMbid: String): ArtistInfoDto {
        record("artist(" + artistMbid + ")")
        return ArtistInfoDto(musicbrainzId = artistMbid)
    }

    override suspend fun artistReleases(
        artistMbid: String,
        limit: Int,
        offset: Int,
    ): ArtistReleasesDto {
        record("artistReleases(" + artistMbid + ")")
        return artistReleasesResponse(artistMbid)
    }

    override suspend fun requestAlbum(request: AlbumRequestDto): RequestAcceptedDto {
        record("requestAlbum(" + request.musicbrainzId + ")")
        return requestAlbumResponse(request)
    }

    override suspend fun requestAlbums(request: BatchAlbumRequestDto): BatchRequestResponseDto {
        record("requestAlbums(" + request.items.size + ")")
        return requestAlbumsResponse(request)
    }

    override suspend fun requestTrack(
        recordingMbid: String,
        request: TrackRequestDto,
    ): TrackRequestResponseDto {
        record("requestTrack(" + recordingMbid + ")")
        return TrackRequestResponseDto(status = "queued", taskId = "task-1")
    }

    override suspend fun cancelRequest(musicbrainzId: String, kind: RequestKind): CancelRequestDto {
        record("cancelRequest(" + musicbrainzId + ")")
        return cancelRequestResponse(musicbrainzId)
    }

    override suspend fun retryRequest(musicbrainzId: String, kind: RequestKind): RetryRequestDto {
        record("retryRequest(" + musicbrainzId + ")")
        return retryRequestResponse(musicbrainzId)
    }

    override suspend fun activeRequests(): ActiveRequestsDto {
        record("activeRequests")
        return activeRequestsResponse()
    }

    override suspend fun requestHistory(
        page: Int,
        pageSize: Int,
        status: String?,
        sort: RequestHistorySort?,
    ): RequestHistoryDto {
        record("requestHistory")
        return RequestHistoryDto()
    }

    override suspend fun wantedRequests(): app.needler.core.network.v1.dto.WantedWatchesDto {
        record("wantedRequests")
        return app.needler.core.network.v1.dto.WantedWatchesDto()
    }

    override suspend fun downloads(
        status: String?,
        releaseGroupMbid: String?,
        page: Int,
        pageSize: Int,
    ): DownloadListDto {
        record("downloads(page=" + page + ")")
        return downloadsResponse(page)
    }

    override suspend fun downloadActivitySummary(): DownloadActivitySummaryDto {
        record("downloadActivitySummary")
        return activitySummaryResponse()
    }

    override suspend fun cancelDownload(taskId: String): CancelDownloadDto {
        record("cancelDownload(" + taskId + ")")
        return CancelDownloadDto(success = true)
    }

    override suspend fun retryDownload(taskId: String): RetryDownloadDto {
        record("retryDownload(" + taskId + ")")
        return retryDownloadResponse(taskId)
    }

    override suspend fun newReleases(limit: Int, offset: Int): NewReleasesDto {
        record("newReleases")
        return NewReleasesDto()
    }

    override suspend fun unseenNewReleaseCount(): UnseenCountDto {
        record("unseenNewReleaseCount")
        return UnseenCountDto()
    }

    override suspend fun markNewReleasesSeen(): UnseenCountDto {
        record("markNewReleasesSeen")
        return UnseenCountDto()
    }

    override suspend fun downloadAccess(): DownloadAccessDto {
        record("downloadAccess")
        return DownloadAccessDto(allowed = true)
    }

    override suspend fun libraryStats(): LibraryStatsDto {
        record("libraryStats")
        return libraryStatsResponse()
    }

    override suspend fun scrobblePreferences(): ScrobblePreferencesDto {
        record("scrobblePreferences")
        return scrobblePreferencesResponse()
    }
}
