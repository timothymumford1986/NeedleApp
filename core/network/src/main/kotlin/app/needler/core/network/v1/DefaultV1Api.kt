package app.needler.core.network.v1

import app.needler.core.network.ApiLane
import app.needler.core.network.CredentialProvider
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.ServerUrl
import app.needler.core.network.SkipAuth
import app.needler.core.network.internal.HttpEngine
import app.needler.core.network.v1.dto.ActiveRequestsDto
import app.needler.core.network.v1.dto.AlbumInfoDto
import app.needler.core.network.v1.dto.AlbumRequestDto
import app.needler.core.network.v1.dto.AlbumTracksDto
import app.needler.core.network.v1.dto.AppPasswordCreateRequestDto
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
import app.needler.core.network.v1.dto.DeviceSessionRequestDto
import app.needler.core.network.v1.dto.DeviceSessionResponseDto
import app.needler.core.network.v1.dto.DownloadAccessDto
import app.needler.core.network.v1.dto.DownloadActivitySummaryDto
import app.needler.core.network.v1.dto.DownloadListDto
import app.needler.core.network.v1.dto.LibraryStatsDto
import app.needler.core.network.v1.dto.LoginRequestDto
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
import kotlinx.serialization.SerializationStrategy
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The only implementation of [V1Api]. Builds requests, tags them with [ApiLane.V1] so the
 * credential interceptor attaches the bearer, and hands them to the shared [HttpEngine].
 *
 * Nothing here logs or embeds a credential: the bearer is added by the interceptor, and the
 * onboarding `bearer` override is set as a header, never as a query parameter.
 */
public class DefaultV1Api(
    http: NeedlerHttpClient,
    private val credentials: CredentialProvider,
) : V1Api {

    private val engine = HttpEngine(http, credentials)

    // ---------------------------------------------------------------- Connect

    override suspend fun version(): VersionInfoDto =
        engine.v1Json(get(url("version")), VersionInfoDto.serializer())

    override suspend fun status(): StatusReportDto =
        engine.v1Json(get(url("status")), StatusReportDto.serializer())

    override suspend fun connectAppsSettings(): ConnectAppsSettingsDto =
        engine.v1Json(get(url("connect-apps", "settings")), ConnectAppsSettingsDto.serializer())

    override suspend fun authProviders(): AuthProvidersDto =
        engine.v1Json(
            Request.Builder()
                .url(url("auth", "providers"))
                .get()
                .tag(ApiLane::class, ApiLane.V1)
                .tag(SkipAuth::class, SkipAuth)
                .build(),
            AuthProvidersDto.serializer(),
        )

    // ------------------------------------------------------------------- Auth

    override suspend fun login(username: String, password: String): AuthResponseDto =
        engine.v1Json(
            Request.Builder()
                .url(url("auth", "login"))
                .post(json(LoginRequestDto.serializer(), LoginRequestDto(username, password)))
                .tag(ApiLane::class, ApiLane.V1)
                // The login body carries the account password: no stored credential is attached,
                // and RedactingLogInterceptor never logs bodies.
                .tag(SkipAuth::class, SkipAuth)
                .build(),
            AuthResponseDto.serializer(),
        )

    override suspend fun createDeviceSession(deviceName: String, bearer: String?): DeviceSessionResponseDto =
        engine.v1Json(
            post(
                url("auth", "device-sessions"),
                json(DeviceSessionRequestDto.serializer(), DeviceSessionRequestDto(deviceName)),
                bearer,
            ),
            DeviceSessionResponseDto.serializer(),
        )

    override suspend fun me(bearer: String?): UserDto =
        engine.v1Json(get(url("auth", "me"), bearer), UserDto.serializer())

    override suspend fun sessions(): SessionListDto =
        engine.v1Json(get(url("auth", "sessions")), SessionListDto.serializer())

    override suspend fun revokeSession(sessionId: String) {
        engine.v1Unit(delete(url("auth", "sessions", sessionId)))
    }

    override suspend fun appPasswords(bearer: String?): AppPasswordListDto =
        engine.v1Json(get(url("connect-apps", "app-passwords"), bearer), AppPasswordListDto.serializer())

    override suspend fun createAppPassword(name: String, bearer: String?): AppPasswordCreateResponseDto =
        engine.v1Json(
            post(
                url("connect-apps", "app-passwords"),
                json(AppPasswordCreateRequestDto.serializer(), AppPasswordCreateRequestDto(name)),
                bearer,
            ),
            AppPasswordCreateResponseDto.serializer(),
        )

    override suspend fun revokeAppPassword(appPasswordId: String, bearer: String?) {
        engine.v1Unit(delete(url("connect-apps", "app-passwords", appPasswordId), bearer))
    }

    // --------------------------------------------------------- Catalogue search

    override suspend fun search(
        query: String,
        limitArtists: Int,
        limitAlbums: Int,
        buckets: Set<SearchBucket>?,
    ): SearchResponseDto {
        val url = urlBuilder("search")
            .addQueryParameter("q", query)
            .addQueryParameter("limit_artists", limitArtists.toString())
            .addQueryParameter("limit_albums", limitAlbums.toString())
            .apply {
                if (!buckets.isNullOrEmpty()) {
                    addQueryParameter("buckets", buckets.joinToString(",") { it.wire })
                }
            }
            .build()
        return engine.v1Json(get(url), SearchResponseDto.serializer())
    }

    override suspend fun searchBucket(
        bucket: SearchBucket,
        query: String,
        limit: Int,
        offset: Int,
    ): SearchBucketResponseDto {
        val url = urlBuilder("search", bucket.wire)
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        return engine.v1Json(get(url), SearchBucketResponseDto.serializer())
    }

    override suspend fun suggest(query: String, limit: Int): SuggestResponseDto {
        val url = urlBuilder("search", "suggest")
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        return engine.v1Json(get(url), SuggestResponseDto.serializer())
    }

    // ------------------------------------------------- Album and artist detail

    override suspend fun album(releaseGroupMbid: String): AlbumInfoDto =
        engine.v1Json(get(url("albums", releaseGroupMbid)), AlbumInfoDto.serializer())

    override suspend fun albumTracks(releaseGroupMbid: String): AlbumTracksDto =
        engine.v1Json(get(url("albums", releaseGroupMbid, "tracks")), AlbumTracksDto.serializer())

    override suspend fun artist(artistMbid: String): ArtistInfoDto =
        engine.v1Json(get(url("artists", artistMbid)), ArtistInfoDto.serializer())

    override suspend fun artistReleases(artistMbid: String, limit: Int, offset: Int): ArtistReleasesDto {
        val url = urlBuilder("artists", artistMbid, "releases")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        return engine.v1Json(get(url), ArtistReleasesDto.serializer())
    }

    // --------------------------------------------------------------- Requests

    override suspend fun requestAlbum(request: AlbumRequestDto): RequestAcceptedDto =
        engine.v1Json(
            post(url("requests", "new"), json(AlbumRequestDto.serializer(), request)),
            RequestAcceptedDto.serializer(),
        )

    override suspend fun requestAlbums(request: BatchAlbumRequestDto): BatchRequestResponseDto {
        require(request.items.size <= BatchAlbumRequestDto.MAX_ITEMS) {
            "A batch request carries at most ${BatchAlbumRequestDto.MAX_ITEMS} items"
        }
        return engine.v1Json(
            post(url("requests", "batch"), json(BatchAlbumRequestDto.serializer(), request)),
            BatchRequestResponseDto.serializer(),
        )
    }

    override suspend fun requestTrack(recordingMbid: String, request: TrackRequestDto): TrackRequestResponseDto =
        engine.v1Json(
            post(url("tracks", recordingMbid, "request"), json(TrackRequestDto.serializer(), request)),
            TrackRequestResponseDto.serializer(),
        )

    override suspend fun cancelRequest(musicbrainzId: String, kind: RequestKind): CancelRequestDto {
        val url = urlBuilder("requests", "active", musicbrainzId)
            .addQueryParameter("request_kind", kind.wire)
            .build()
        return engine.v1Json(delete(url), CancelRequestDto.serializer())
    }

    override suspend fun retryRequest(musicbrainzId: String, kind: RequestKind): RetryRequestDto {
        val url = urlBuilder("requests", "retry", musicbrainzId)
            .addQueryParameter("request_kind", kind.wire)
            .build()
        return engine.v1Json(post(url, EMPTY_BODY), RetryRequestDto.serializer())
    }

    override suspend fun activeRequests(): ActiveRequestsDto =
        engine.v1Json(get(url("requests", "active")), ActiveRequestsDto.serializer())

    override suspend fun requestHistory(
        page: Int,
        pageSize: Int,
        status: String?,
        sort: RequestHistorySort?,
    ): RequestHistoryDto {
        val url = urlBuilder("requests", "history")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", pageSize.toString())
            .apply {
                if (status != null) addQueryParameter("status", status)
                if (sort != null) addQueryParameter("sort", sort.wire)
            }
            .build()
        return engine.v1Json(get(url), RequestHistoryDto.serializer())
    }

    override suspend fun wantedRequests(): WantedWatchesDto =
        engine.v1Json(get(url("requests", "wanted")), WantedWatchesDto.serializer())

    // ------------------------------------------------------- Download queue

    override suspend fun downloads(
        status: String?,
        releaseGroupMbid: String?,
        page: Int,
        pageSize: Int,
    ): DownloadListDto {
        val url = urlBuilder("downloads")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", pageSize.toString())
            .apply {
                if (status != null) addQueryParameter("status", status)
                if (releaseGroupMbid != null) addQueryParameter("release_group_mbid", releaseGroupMbid)
            }
            .build()
        return engine.v1Json(get(url), DownloadListDto.serializer())
    }

    override suspend fun downloadActivitySummary(): DownloadActivitySummaryDto =
        engine.v1Json(get(url("downloads", "activity-summary")), DownloadActivitySummaryDto.serializer())

    override suspend fun cancelDownload(taskId: String): CancelDownloadDto =
        engine.v1Json(post(url("downloads", taskId, "cancel"), EMPTY_BODY), CancelDownloadDto.serializer())

    override suspend fun retryDownload(taskId: String): RetryDownloadDto =
        engine.v1Json(post(url("downloads", taskId, "retry"), EMPTY_BODY), RetryDownloadDto.serializer())

    // ------------------------------------------------ Following notifications

    override suspend fun newReleases(limit: Int, offset: Int): NewReleasesDto {
        val url = urlBuilder("following", "new-releases")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        return engine.v1Json(get(url), NewReleasesDto.serializer())
    }

    override suspend fun unseenNewReleaseCount(): UnseenCountDto =
        engine.v1Json(get(url("following", "new-releases", "unseen-count")), UnseenCountDto.serializer())

    override suspend fun markNewReleasesSeen(): UnseenCountDto =
        engine.v1Json(post(url("following", "new-releases", "seen"), EMPTY_BODY), UnseenCountDto.serializer())

    // ------------------------------------------------------- Gates and totals

    override suspend fun downloadAccess(): DownloadAccessDto =
        engine.v1Json(get(url("download", "access")), DownloadAccessDto.serializer())

    override suspend fun libraryStats(): LibraryStatsDto =
        engine.v1Json(get(url("library", "stats")), LibraryStatsDto.serializer())

    override suspend fun scrobblePreferences(): ScrobblePreferencesDto =
        engine.v1Json(get(url("me", "scrobble-preferences")), ScrobblePreferencesDto.serializer())

    // ------------------------------------------------------------- Plumbing

    private fun server(): ServerUrl = credentials.serverUrl()
        ?: error("No server configured: save a ServerUrl before using V1Api")

    private fun urlBuilder(vararg segments: String): HttpUrl.Builder {
        val base = server().apiV1("").toHttpUrlOrNull()
            ?: error("Saved server URL is not a valid HTTP URL")
        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder
    }

    private fun url(vararg segments: String): HttpUrl = urlBuilder(*segments).build()

    private fun get(url: HttpUrl, bearer: String? = null): Request =
        Request.Builder().url(url).get().laneV1(bearer).build()

    private fun post(url: HttpUrl, body: RequestBody, bearer: String? = null): Request =
        Request.Builder().url(url).post(body).laneV1(bearer).build()

    private fun delete(url: HttpUrl, bearer: String? = null): Request =
        Request.Builder().url(url).delete().laneV1(bearer).build()

    private fun Request.Builder.laneV1(bearer: String?): Request.Builder = apply {
        tag(ApiLane::class, ApiLane.V1)
        // An explicit bearer wins: the interceptor leaves an existing Authorization header alone.
        if (!bearer.isNullOrEmpty()) header("Authorization", "Bearer $bearer")
    }

    private fun <T> json(serializer: SerializationStrategy<T>, value: T): RequestBody =
        engine.json.encodeToString(serializer, value).toRequestBody(JSON_MEDIA_TYPE)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** For the `POST` endpoints that take no body at all. */
        val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null, 0, 0)
    }
}
