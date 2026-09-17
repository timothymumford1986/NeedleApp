package app.needler.core.network.subsonic

import app.needler.core.network.ApiLane
import app.needler.core.network.CredentialProvider
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.NetworkError
import app.needler.core.network.internal.HttpEngine
import app.needler.core.network.media.SubsonicMediaUrls
import app.needler.core.network.subsonic.dto.AlbumId3Dto
import app.needler.core.network.subsonic.dto.AlbumList2Dto
import app.needler.core.network.subsonic.dto.ArtistId3Dto
import app.needler.core.network.subsonic.dto.ArtistsDto
import app.needler.core.network.subsonic.dto.ChildDto
import app.needler.core.network.subsonic.dto.GenreDto
import app.needler.core.network.subsonic.dto.GenresDto
import app.needler.core.network.subsonic.dto.IndexesDto
import app.needler.core.network.subsonic.dto.LyricsListDto
import app.needler.core.network.subsonic.dto.OpenSubsonicExtensionDto
import app.needler.core.network.subsonic.dto.PlaylistDto
import app.needler.core.network.subsonic.dto.PlaylistsDto
import app.needler.core.network.subsonic.dto.ScanStatusDto
import app.needler.core.network.subsonic.dto.SearchResult3Dto
import app.needler.core.network.subsonic.dto.SongsByGenreDto
import app.needler.core.network.subsonic.dto.Starred2Dto
import app.needler.core.network.subsonic.dto.SubsonicServerInfoDto
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * The only implementation of [SubsonicApi].
 *
 * Every call is a `GET` with the method name as the last path segment. `v`, `c` and `f=json` are
 * added by [SubsonicMediaUrls.methodUrl]; the `apiKey` credential is added by this module's
 * interceptor, so the app-password never passes through URL-building code that could be logged.
 *
 * Failures arrive in three shapes and all three are normalised here:
 *  * HTTP 200 with `status=failed` — the usual case, mapped by [SubsonicEnvelopeParser];
 *  * HTTP 200 with `status=failed` **and** a `Retry-After` header — the shim's rate limiter;
 *  * a real HTTP status — only on the binary endpoints, which this class does not fetch.
 */
public class DefaultSubsonicApi(
    http: NeedlerHttpClient,
    private val credentials: CredentialProvider,
    override val mediaUrls: SubsonicMediaUrls = SubsonicMediaUrls(credentials),
) : SubsonicApi {

    private val engine = HttpEngine(http, credentials)

    // ------------------------------------------------------------ Negotiation

    override suspend fun ping(): SubsonicServerInfoDto {
        val envelope = envelope(url("ping"))
        return SubsonicServerInfoDto(
            status = envelope.status,
            version = envelope.version,
            type = envelope.type,
            serverVersion = envelope.serverVersion,
            openSubsonic = envelope.openSubsonic,
        )
    }

    override suspend fun openSubsonicExtensions(): List<OpenSubsonicExtensionDto> = payload(
        url("getOpenSubsonicExtensions"),
        "openSubsonicExtensions",
        ListSerializer(OpenSubsonicExtensionDto.serializer()),
    ) ?: emptyList()

    // ---------------------------------------------------------------- Library

    override suspend fun artists(ifModifiedSince: Long?, musicFolderId: String?): ArtistsDto {
        val url = url("getArtists") {
            if (ifModifiedSince != null) addQueryParameter("ifModifiedSince", ifModifiedSince.toString())
            if (musicFolderId != null) addQueryParameter("musicFolderId", musicFolderId)
        }
        return payload(url, "artists", ArtistsDto.serializer()) ?: ArtistsDto()
    }

    override suspend fun indexes(ifModifiedSince: Long?, musicFolderId: String?): IndexesDto {
        val url = url("getIndexes") {
            if (ifModifiedSince != null) addQueryParameter("ifModifiedSince", ifModifiedSince.toString())
            if (musicFolderId != null) addQueryParameter("musicFolderId", musicFolderId)
        }
        return payload(url, "indexes", IndexesDto.serializer()) ?: IndexesDto()
    }

    override suspend fun artist(id: String): ArtistId3Dto =
        required(url("getArtist") { addQueryParameter("id", id) }, "artist", ArtistId3Dto.serializer())

    override suspend fun album(id: String): AlbumId3Dto =
        required(url("getAlbum") { addQueryParameter("id", id) }, "album", AlbumId3Dto.serializer())

    override suspend fun song(id: String): ChildDto =
        required(url("getSong") { addQueryParameter("id", id) }, "song", ChildDto.serializer())

    override suspend fun albumList2(
        type: AlbumListType,
        size: Int,
        offset: Int,
        fromYear: Int?,
        toYear: Int?,
        genre: String?,
    ): List<AlbumId3Dto> {
        val url = url("getAlbumList2") {
            addQueryParameter("type", type.wire)
            addQueryParameter("size", size.coerceIn(1, 500).toString())
            addQueryParameter("offset", offset.coerceAtLeast(0).toString())
            if (fromYear != null) addQueryParameter("fromYear", fromYear.toString())
            if (toYear != null) addQueryParameter("toYear", toYear.toString())
            if (genre != null) addQueryParameter("genre", genre)
        }
        return payload(url, "albumList2", AlbumList2Dto.serializer())?.album ?: emptyList()
    }

    override suspend fun search3(
        query: String,
        artistCount: Int,
        artistOffset: Int,
        albumCount: Int,
        albumOffset: Int,
        songCount: Int,
        songOffset: Int,
    ): SearchResult3Dto {
        val url = url("search3") {
            addQueryParameter("query", query)
            addQueryParameter("artistCount", artistCount.coerceIn(0, 500).toString())
            addQueryParameter("artistOffset", artistOffset.coerceAtLeast(0).toString())
            addQueryParameter("albumCount", albumCount.coerceIn(0, 500).toString())
            addQueryParameter("albumOffset", albumOffset.coerceAtLeast(0).toString())
            addQueryParameter("songCount", songCount.coerceIn(0, 500).toString())
            addQueryParameter("songOffset", songOffset.coerceAtLeast(0).toString())
        }
        return payload(url, "searchResult3", SearchResult3Dto.serializer()) ?: SearchResult3Dto()
    }

    override suspend fun genres(): List<GenreDto> =
        payload(url("getGenres"), "genres", GenresDto.serializer())?.genre ?: emptyList()

    override suspend fun songsByGenre(genre: String, count: Int, offset: Int): List<ChildDto> {
        val url = url("getSongsByGenre") {
            addQueryParameter("genre", genre)
            addQueryParameter("count", count.coerceIn(1, 500).toString())
            addQueryParameter("offset", offset.coerceAtLeast(0).toString())
        }
        return payload(url, "songsByGenre", SongsByGenreDto.serializer())?.song ?: emptyList()
    }

    override suspend fun scanStatus(): ScanStatusDto =
        payload(url("getScanStatus"), "scanStatus", ScanStatusDto.serializer()) ?: ScanStatusDto()

    // ------------------------------------------------------------- Favourites

    override suspend fun starred2(): Starred2Dto =
        payload(url("getStarred2"), "starred2", Starred2Dto.serializer()) ?: Starred2Dto()

    override suspend fun star(ids: List<String>, albumIds: List<String>, artistIds: List<String>) {
        require(ids.isNotEmpty() || albumIds.isNotEmpty() || artistIds.isNotEmpty()) {
            "star needs at least one id"
        }
        envelope(url("star") { addFavouriteTargets(ids, albumIds, artistIds) })
    }

    override suspend fun unstar(ids: List<String>, albumIds: List<String>, artistIds: List<String>) {
        require(ids.isNotEmpty() || albumIds.isNotEmpty() || artistIds.isNotEmpty()) {
            "unstar needs at least one id"
        }
        envelope(url("unstar") { addFavouriteTargets(ids, albumIds, artistIds) })
    }

    // -------------------------------------------------------------- Playlists

    override suspend fun playlists(): List<PlaylistDto> =
        payload(url("getPlaylists"), "playlists", PlaylistsDto.serializer())?.playlist ?: emptyList()

    override suspend fun playlist(id: String): PlaylistDto =
        required(url("getPlaylist") { addQueryParameter("id", id) }, "playlist", PlaylistDto.serializer())

    override suspend fun createPlaylist(
        name: String?,
        songIds: List<String>,
        playlistId: String?,
    ): PlaylistDto {
        require(name != null || playlistId != null) { "createPlaylist needs either a name or a playlistId" }
        val url = url("createPlaylist") {
            if (name != null) addQueryParameter("name", name)
            if (playlistId != null) addQueryParameter("playlistId", playlistId)
            songIds.forEach { addQueryParameter("songId", it) }
        }
        return required(url, "playlist", PlaylistDto.serializer())
    }

    override suspend fun updatePlaylist(
        playlistId: String,
        name: String?,
        comment: String?,
        isPublic: Boolean?,
        songIdsToAdd: List<String>,
        songIndexesToRemove: List<Int>,
    ) {
        val url = url("updatePlaylist") {
            addQueryParameter("playlistId", playlistId)
            if (name != null) addQueryParameter("name", name)
            if (comment != null) addQueryParameter("comment", comment)
            if (isPublic != null) addQueryParameter("public", isPublic.toString())
            songIdsToAdd.forEach { addQueryParameter("songIdToAdd", it) }
            // Descending, so removing several rows in one call cannot shift the later indexes.
            songIndexesToRemove.sortedDescending().forEach {
                addQueryParameter("songIndexToRemove", it.toString())
            }
        }
        envelope(url)
    }

    override suspend fun deletePlaylist(id: String) {
        envelope(url("deletePlaylist") { addQueryParameter("id", id) })
    }

    // ------------------------------------------------------------- Scrobbling

    override suspend fun scrobble(ids: List<String>, timesMillis: List<Long>, submission: Boolean) {
        require(ids.isNotEmpty()) { "scrobble needs at least one id" }
        require(timesMillis.isEmpty() || timesMillis.size == ids.size) {
            "scrobble timestamps must be empty or parallel to ids"
        }
        val url = url("scrobble") {
            ids.forEach { addQueryParameter("id", it) }
            timesMillis.forEach { addQueryParameter("time", it.toString()) }
            addQueryParameter("submission", submission.toString())
        }
        envelope(url)
    }

    // ----------------------------------------------------------------- Lyrics

    override suspend fun lyricsBySongId(id: String): LyricsListDto =
        payload(url("getLyricsBySongId") { addQueryParameter("id", id) }, "lyricsList", LyricsListDto.serializer())
            ?: LyricsListDto()

    // ------------------------------------------------------------- Plumbing

    private fun HttpUrl.Builder.addFavouriteTargets(
        ids: List<String>,
        albumIds: List<String>,
        artistIds: List<String>,
    ) {
        ids.forEach { addQueryParameter("id", it) }
        albumIds.forEach { addQueryParameter("albumId", it) }
        artistIds.forEach { addQueryParameter("artistId", it) }
    }

    private fun url(method: String, block: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        mediaUrls.methodUrl(method, includeCredential = false).apply(block).build()

    private fun request(url: HttpUrl): Request = Request.Builder()
        .url(url)
        .get()
        .tag(ApiLane::class, ApiLane.Subsonic)
        .build()

    /** Run the call and return the parsed envelope, throwing on `status=failed`. */
    private suspend fun envelope(url: HttpUrl): SubsonicEnvelope {
        engine.execute(request(url)).use { response ->
            val body = response.body.string()
            if (!response.isSuccessful && body.isBlank()) {
                // A non-200 with no envelope can only be an infrastructure answer (a proxy, or a
                // 404 from the wrong base path); the v1 mapping covers those statuses correctly.
                throw engine.mapHttpFailure(response, null, ApiLane.Subsonic)
            }
            val envelope = SubsonicEnvelopeParser.parse(engine.json, body)
            if (envelope.isFailed) throw failure(envelope, response)
            return envelope
        }
    }

    /** Envelope plus the payload under [key], or null when the server omitted it. */
    private suspend fun <T> payload(
        url: HttpUrl,
        key: String,
        deserializer: DeserializationStrategy<T>,
    ): T? {
        val element = envelope(url).payload(key) ?: return null
        return try {
            engine.json.decodeFromJsonElement(deserializer, element)
        } catch (failure: Exception) {
            throw NetworkError.Serialisation(ApiLane.Subsonic, failure)
        }
    }

    /** Payload that must be present: its absence is a contract break, not an empty result. */
    private suspend fun <T> required(
        url: HttpUrl,
        key: String,
        deserializer: DeserializationStrategy<T>,
    ): T = payload(url, key, deserializer) ?: throw NetworkError.Serialisation(
        ApiLane.Subsonic,
        IllegalStateException("Envelope has no \"$key\" payload"),
    )

    private fun failure(envelope: SubsonicEnvelope, response: Response): NetworkError =
        SubsonicEnvelopeParser.toNetworkError(
            envelope.error,
            HttpEngine.retryAfterSeconds(response),
            credentials,
        )
}
