package app.needler.core.data.fake

import app.needler.core.network.CredentialProvider
import app.needler.core.network.ServerUrl
import app.needler.core.network.media.SubsonicMediaUrls
import app.needler.core.network.subsonic.AlbumListType
import app.needler.core.network.subsonic.SubsonicApi
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

/**
 * A scriptable Subsonic client.
 *
 * Every call records itself in [calls], which is how the sync tests assert the one thing that cannot
 * be seen from the result: that a delta went through `getIndexes` and **not** through `getArtists`.
 * That server accepts `ifModifiedSince` on `getArtists` and ignores it, so a delta built on it is a
 * full sync wearing a delta's clothes - the request log is the only place that shows.
 */
public class FakeSubsonicApi : SubsonicApi {

    public val calls: MutableList<String> = ArrayList()

    public var indexesResponse: (Long?) -> IndexesDto = { IndexesDto(lastModified = 0L) }
    public var artistsResponse: () -> ArtistsDto = { ArtistsDto() }
    public var artistResponse: (String) -> ArtistId3Dto = { artistDto() }
    public var albumResponse: (String) -> AlbumId3Dto = { albumDto() }
    public var albumListResponse: (AlbumListType) -> List<AlbumId3Dto> = { emptyList() }
    public var playlistsResponse: () -> List<PlaylistDto> = { emptyList() }
    public var playlistResponse: (String) -> PlaylistDto = { PlaylistDto(id = it) }
    public var createPlaylistResponse: (String?, List<String>, String?) -> PlaylistDto =
        { name, _, id -> PlaylistDto(id = id ?: "pl-new", name = name.orEmpty()) }
    public var starred2Response: () -> Starred2Dto = { Starred2Dto() }
    public var scanStatusResponse: () -> ScanStatusDto = { ScanStatusDto(scanning = false) }
    public var failWith: (() -> Throwable)? = null

    override val mediaUrls: SubsonicMediaUrls = SubsonicMediaUrls(
        object : CredentialProvider {
            override fun serverUrl(): ServerUrl? = ServerUrl.parseOrNull("https://music.example.net")
            override fun bearerToken(): String? = "bearer"
            override fun appPassword(): String? = "app-password"
        },
    )

    private fun record(name: String) {
        calls.add(name)
        failWith?.let { throw it() }
    }

    override suspend fun ping(): SubsonicServerInfoDto {
        record("ping")
        return SubsonicServerInfoDto(status = "ok", version = "1.16.1", openSubsonic = true)
    }

    override suspend fun openSubsonicExtensions(): List<OpenSubsonicExtensionDto> {
        record("getOpenSubsonicExtensions")
        return emptyList()
    }

    override suspend fun artists(ifModifiedSince: Long?, musicFolderId: String?): ArtistsDto {
        record("getArtists")
        return artistsResponse()
    }

    override suspend fun indexes(ifModifiedSince: Long?, musicFolderId: String?): IndexesDto {
        record("getIndexes(" + ifModifiedSince + ")")
        return indexesResponse(ifModifiedSince)
    }

    override suspend fun artist(id: String): ArtistId3Dto {
        record("getArtist(" + id + ")")
        return artistResponse(id)
    }

    override suspend fun album(id: String): AlbumId3Dto {
        record("getAlbum(" + id + ")")
        return albumResponse(id)
    }

    override suspend fun song(id: String): ChildDto {
        record("getSong(" + id + ")")
        return songDto()
    }

    override suspend fun albumList2(
        type: AlbumListType,
        size: Int,
        offset: Int,
        fromYear: Int?,
        toYear: Int?,
        genre: String?,
    ): List<AlbumId3Dto> {
        record("getAlbumList2(" + type.wire + ")")
        return albumListResponse(type)
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
        record("search3")
        return SearchResult3Dto()
    }

    override suspend fun genres(): List<GenreDto> {
        record("getGenres")
        return emptyList()
    }

    override suspend fun songsByGenre(genre: String, count: Int, offset: Int): List<ChildDto> {
        record("getSongsByGenre")
        return emptyList()
    }

    override suspend fun scanStatus(): ScanStatusDto {
        record("getScanStatus")
        return scanStatusResponse()
    }

    override suspend fun starred2(): Starred2Dto {
        record("getStarred2")
        return starred2Response()
    }

    override suspend fun star(ids: List<String>, albumIds: List<String>, artistIds: List<String>) {
        record("star(" + (ids + albumIds + artistIds).joinToString(",") + ")")
    }

    override suspend fun unstar(ids: List<String>, albumIds: List<String>, artistIds: List<String>) {
        record("unstar(" + (ids + albumIds + artistIds).joinToString(",") + ")")
    }

    override suspend fun playlists(): List<PlaylistDto> {
        record("getPlaylists")
        return playlistsResponse()
    }

    override suspend fun playlist(id: String): PlaylistDto {
        record("getPlaylist(" + id + ")")
        return playlistResponse(id)
    }

    override suspend fun createPlaylist(
        name: String?,
        songIds: List<String>,
        playlistId: String?,
    ): PlaylistDto {
        record("createPlaylist(" + name + "," + songIds.joinToString("|") + "," + playlistId + ")")
        return createPlaylistResponse(name, songIds, playlistId)
    }

    override suspend fun updatePlaylist(
        playlistId: String,
        name: String?,
        comment: String?,
        isPublic: Boolean?,
        songIdsToAdd: List<String>,
        songIndexesToRemove: List<Int>,
    ) {
        record(
            "updatePlaylist(" + playlistId + ",add=" + songIdsToAdd.joinToString("|") +
                ",remove=" + songIndexesToRemove.joinToString("|") + ")",
        )
    }

    override suspend fun deletePlaylist(id: String) {
        record("deletePlaylist(" + id + ")")
    }

    override suspend fun scrobble(ids: List<String>, timesMillis: List<Long>, submission: Boolean) {
        record(
            "scrobble(" + ids.joinToString("|") + "," + timesMillis.joinToString("|") +
                ",submission=" + submission + ")",
        )
    }

    override suspend fun lyricsBySongId(id: String): LyricsListDto {
        record("getLyricsBySongId")
        return LyricsListDto()
    }
}
