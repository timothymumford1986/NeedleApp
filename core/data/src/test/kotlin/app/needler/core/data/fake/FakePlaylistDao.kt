package app.needler.core.data.fake

import app.needler.core.data.local.dao.FavouriteDao
import app.needler.core.data.local.dao.PlaylistDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.ArtistEntity
import app.needler.core.data.local.entity.FavouriteEntity
import app.needler.core.data.local.entity.PlaylistEntity
import app.needler.core.data.local.entity.PlaylistTrackEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.local.projection.PlaylistTrackRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Playlists in memory.
 *
 * `adoptServerId` is modelled faithfully because it is the whole point of `local_only`: a playlist
 * created offline is re-keyed onto the server's id rather than inserted a second time.
 */
public class FakePlaylistDao : PlaylistDao() {

    public val playlists: MutableMap<String, PlaylistEntity> = LinkedHashMap()
    public val entries: MutableList<PlaylistTrackEntity> = ArrayList()
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    private fun bump() {
        changes.value += 1
    }

    override suspend fun upsert(playlist: PlaylistEntity) {
        playlists[playlist.playlistId] = playlist
        bump()
    }

    override suspend fun upsertAll(playlists: List<PlaylistEntity>) {
        playlists.forEach { this.playlists[it.playlistId] = it }
        bump()
    }

    override fun observePlaylists(): Flow<List<PlaylistEntity>> =
        changes.map { playlists.values.sortedBy { it.nameNormalised } }

    override fun observePlaylist(playlistId: String): Flow<PlaylistEntity?> =
        changes.map { playlists[playlistId] }

    override suspend fun getPlaylist(playlistId: String): PlaylistEntity? = playlists[playlistId]

    override fun observePlaylistTracks(playlistId: String): Flow<List<PlaylistTrackRow>> =
        changes.map {
            entries.filter { it.playlistId == playlistId }.sortedBy { it.position }.map { row ->
                playlistTrackRow(
                    position = row.position,
                    releaseGroupMbid = row.releaseGroupMbid,
                    discNo = row.discNo,
                    trackNo = row.trackNo,
                    fileId = row.sourceFileId,
                )
            }
        }

    override suspend fun upsertTracks(entries: List<PlaylistTrackEntity>) {
        entries.forEach { row ->
            this.entries.removeAll { it.playlistId == row.playlistId && it.position == row.position }
            this.entries.add(row)
        }
        bump()
    }

    override suspend fun getTracksOf(playlistId: String): List<PlaylistTrackEntity> =
        entries.filter { it.playlistId == playlistId }.sortedBy { it.position }

    override suspend fun deleteTracksOf(playlistId: String) {
        entries.removeAll { it.playlistId == playlistId }
        bump()
    }

    override suspend fun countTracks(playlistId: String): Int =
        entries.count { it.playlistId == playlistId }

    override suspend fun updatePlaylistId(localId: String, serverId: String, updatedAt: Long) {
        val row: PlaylistEntity = playlists.remove(localId) ?: return
        playlists[serverId] = row.copy(
            playlistId = serverId,
            localOnly = false,
            updatedAt = updatedAt,
        )
        bump()
    }

    override suspend fun updateTrackPlaylistId(localId: String, serverId: String) {
        for (index in entries.indices) {
            if (entries[index].playlistId == localId) {
                entries[index] = entries[index].copy(playlistId = serverId)
            }
        }
        bump()
    }

    override suspend fun delete(playlistId: String) {
        playlists.remove(playlistId)
        entries.removeAll { it.playlistId == playlistId }
        bump()
    }

    override suspend fun clearTracks() {
        entries.clear()
        bump()
    }

    override suspend fun clear() {
        playlists.clear()
        bump()
    }
}

/** Favourites in memory, including the resolved track key a starred song carries. */
public class FakeFavouriteDao : FavouriteDao {

    public val rows: MutableMap<String, FavouriteEntity> = LinkedHashMap()
    public var albums: List<AlbumEntity> = emptyList()
    public var artists: List<ArtistEntity> = emptyList()
    public var tracks: List<TrackEntity> = emptyList()
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0)

    private fun key(row: FavouriteEntity): String = row.entityType.dbValue + "/" + row.entityId

    override suspend fun upsert(favourite: FavouriteEntity) {
        rows[key(favourite)] = favourite
        changes.value += 1
    }

    override suspend fun upsertAll(favourites: List<FavouriteEntity>) {
        favourites.forEach { rows[key(it)] = it }
        changes.value += 1
    }

    override fun observeFavourites(): Flow<List<FavouriteEntity>> =
        changes.map { rows.values.sortedByDescending { it.starredAt ?: 0L } }

    override fun observeStarredAlbums(): Flow<List<AlbumEntity>> = changes.map { albums }

    override fun observeStarredArtists(): Flow<List<ArtistEntity>> = changes.map { artists }

    override fun observeStarredTracks(): Flow<List<TrackEntity>> = changes.map { tracks }

    override fun observeAlbumIsStarred(releaseGroupMbid: String): Flow<Boolean> =
        changes.map { rows.containsKey("album/" + releaseGroupMbid) }

    override fun observeArtistIsStarred(artistMbid: String): Flow<Boolean> =
        changes.map { rows.containsKey("artist/" + artistMbid) }

    override fun observeTrackIsStarred(
        releaseGroupMbid: String,
        discNo: Int,
        trackNo: Int,
    ): Flow<Boolean> = changes.map {
        rows.values.any {
            it.releaseGroupMbid == releaseGroupMbid && it.discNo == discNo && it.trackNo == trackNo
        }
    }

    override suspend fun getFavourite(entityType: String, entityId: String): FavouriteEntity? =
        rows[entityType + "/" + entityId]

    override suspend fun getPendingSync(): List<FavouriteEntity> = rows.values.filter { it.pendingSync }

    override suspend fun setPendingSync(entityType: String, entityId: String, pendingSync: Boolean) {
        val id: String = entityType + "/" + entityId
        rows[id]?.let { rows[id] = it.copy(pendingSync = pendingSync) }
        changes.value += 1
    }

    override suspend fun delete(entityType: String, entityId: String) {
        rows.remove(entityType + "/" + entityId)
        changes.value += 1
    }

    override suspend fun deleteSyncedOfType(entityType: String) {
        rows.values
            .filter { it.entityType.dbValue == entityType && !it.pendingSync }
            .map(::key)
            .forEach { rows.remove(it) }
        changes.value += 1
    }

    override suspend fun clear() {
        rows.clear()
        changes.value += 1
    }
}
