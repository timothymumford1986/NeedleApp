package app.needler.core.data.repository

import app.needler.core.data.local.SortKeys
import app.needler.core.data.local.dao.PlaylistDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.dao.WriteQueueDao
import app.needler.core.data.local.entity.PlaylistEntity
import app.needler.core.data.local.entity.PlaylistTrackEntity
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.local.entity.WriteOperationTypeDb
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.mapper.SubsonicIds
import app.needler.core.data.mapper.SubsonicMappers
import app.needler.core.data.mapper.networkCall
import app.needler.core.data.platform.NetworkMonitor
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistEdit
import app.needler.core.domain.model.PlaylistEntry
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.WriteOperation
import app.needler.core.domain.repository.PlaylistRepository
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.core.network.subsonic.dto.PlaylistDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Playlists, read from the mirror and written through Subsonic.
 *
 * Every edit is applied **locally first** and then sent, or journalled when there is no connection.
 * The protocol offers no revision or conflict signal, so replay is last-write-wins and
 * `Playlist.hasPendingLocalEdits` is how the UI admits that rather than pretending the server
 * already agrees.
 *
 * A playlist created offline is a real row under a locally minted id with `local_only` set. The
 * write queue's replay re-keys that row to the id the server assigns - an adopt-server-id step, not
 * an insert. Without the flag the UI could not tell a real playlist from one the server has never
 * seen, and a failed replay would leave a permanently invisible row.
 */
public class DefaultPlaylistRepository(
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
    private val writeQueueDao: WriteQueueDao,
    private val writeQueue: WriteQueue,
    private val networkMonitor: NetworkMonitor,
    private val subsonic: SubsonicApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newLocalId: () -> String = { LOCAL_ID_PREFIX + java.util.UUID.randomUUID() },
) : PlaylistRepository {

    // ---------------------------------------------------------------------- reads

    override fun observePlaylists(): Flow<List<Playlist>> = combine(
        playlistDao.observePlaylists(),
        writeQueueDao.observeQueue(),
    ) { rows: List<PlaylistEntity>, queue ->
        val pending: Set<String> = queue
            .filter { it.operationType.isPlaylistEdit }
            .mapNotNull { it.entityKey }
            .toSet()
        rows.map { EntityMappers.playlist(it, hasPendingLocalEdits = pending.contains(it.playlistId)) }
    }

    override fun observePlaylist(id: PlaylistId): Flow<Playlist?> = combine(
        playlistDao.observePlaylist(id.value),
        writeQueueDao.observeQueue(),
    ) { row: PlaylistEntity?, queue ->
        val pending: Boolean = queue.any { it.operationType.isPlaylistEdit && it.entityKey == id.value }
        row?.let { EntityMappers.playlist(it, hasPendingLocalEdits = pending) }
    }

    override fun observePlaylistEntries(id: PlaylistId): Flow<List<PlaylistEntry>> =
        playlistDao.observePlaylistTracks(id.value).map { rows ->
            rows.map(EntityMappers::playlistEntry)
        }

    // --------------------------------------------------------------------- writes

    override suspend fun applyEdit(edit: PlaylistEdit): Outcome<PlaylistId> = when (edit) {
        is PlaylistEdit.Create -> create(edit)
        is PlaylistEdit.Rename -> mutate(edit.id, edit) { renameLocally(edit) }
        is PlaylistEdit.AddTracks -> mutate(edit.id, edit) { addLocally(edit) }
        is PlaylistEdit.RemoveTracks -> mutate(edit.id, edit) { removeLocally(edit) }
        is PlaylistEdit.Reorder -> mutate(edit.id, edit) { reorderLocally(edit) }
        is PlaylistEdit.Delete -> mutate(edit.id, edit) { playlistDao.delete(edit.id.value) }
    }

    override suspend fun addTracks(id: PlaylistId, keys: List<TrackKey>): Outcome<Unit> =
        toUnit(applyEdit(PlaylistEdit.AddTracks(id, keys)))

    override suspend fun removeTracks(id: PlaylistId, positions: List<Int>): Outcome<Unit> =
        toUnit(applyEdit(PlaylistEdit.RemoveTracks(id, positions)))

    override suspend fun createPlaylist(name: String, keys: List<TrackKey>): Outcome<PlaylistId> =
        applyEdit(PlaylistEdit.Create(name, keys))

    override suspend fun deletePlaylist(id: PlaylistId): Outcome<Unit> =
        toUnit(applyEdit(PlaylistEdit.Delete(id)))

    // ------------------------------------------------------------------ refreshes

    override suspend fun refreshPlaylists(): Outcome<Unit> {
        val call = networkCall { subsonic.playlists() }
        return when (call) {
            is Outcome.Failure -> call
            is Outcome.Success -> {
                val now: Long = nowMillis()
                val rows: List<PlaylistEntity> = call.value.mapNotNull {
                    SubsonicMappers.playlistEntity(it, now)
                }
                if (rows.isNotEmpty()) playlistDao.upsertAll(rows)
                Outcome.Ok
            }
        }
    }

    override suspend fun refreshPlaylist(id: PlaylistId): Outcome<Unit> {
        // A playlist the server has never seen cannot be refreshed from it. This is a success, not
        // an error: the local copy is the only copy and is exactly what the user expects to see.
        if (playlistDao.getPlaylist(id.value)?.localOnly == true) return Outcome.Ok
        val call = networkCall { subsonic.playlist(SubsonicIds.playlistId(id.value)) }
        return when (call) {
            is Outcome.Failure -> call
            is Outcome.Success -> {
                writePlaylist(call.value)
                Outcome.Ok
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private suspend fun writePlaylist(dto: PlaylistDto) {
        val now: Long = nowMillis()
        val row: PlaylistEntity = SubsonicMappers.playlistEntity(dto, now) ?: return
        playlistDao.upsert(row)
        playlistDao.replacePlaylistTracks(
            playlistId = row.playlistId,
            entries = SubsonicMappers.playlistTrackEntities(row.playlistId, dto.entry),
        )
    }

    /**
     * Creates a playlist.
     *
     * Offline, the row is minted locally with `local_only` set so the user can use it at once; the
     * queued `createPlaylist` carries the provisional id as its entity key, and replay adopts the
     * server's id onto the same row.
     */
    private suspend fun create(edit: PlaylistEdit.Create): Outcome<PlaylistId> {
        val now: Long = nowMillis()
        if (networkMonitor.current().isOnline) {
            val songIds: List<String> = resolveTrackIds(edit.trackKeys)
            val call = networkCall { subsonic.createPlaylist(name = edit.name, songIds = songIds) }
            when (call) {
                is Outcome.Success -> {
                    val id: PlaylistId = SubsonicIds.playlistIdOrNull(call.value.id)
                        ?: return Outcome.Failure(
                            NeedlerError.ProtocolViolation("createPlaylist returned no id"),
                        )
                    writePlaylist(call.value)
                    if (call.value.entry.isEmpty() && edit.trackKeys.isNotEmpty()) {
                        // The create call answers with the playlist but not always its entries; seed
                        // them locally so the screen is not empty until the next refresh.
                        playlistDao.replacePlaylistTracks(
                            playlistId = id.value,
                            entries = localEntries(id.value, edit.trackKeys, from = 0),
                        )
                    }
                    return Outcome.Success(id)
                }
                is Outcome.Failure -> if (!call.error.isRetryable) return call
            }
        }
        val localId: String = newLocalId()
        playlistDao.upsert(
            PlaylistEntity(
                playlistId = localId,
                name = edit.name,
                nameNormalised = SortKeys.normalise(edit.name),
                trackCount = edit.trackKeys.size,
                durationMs = null,
                owner = null,
                isPublic = false,
                comment = null,
                coverArtId = null,
                createdAt = now,
                changedAt = now,
                localOnly = true,
                updatedAt = now,
            ),
        )
        playlistDao.replacePlaylistTracks(localId, localEntries(localId, edit.trackKeys, from = 0))
        writeQueue.enqueue(
            operation = WriteOperation.EditPlaylist(edit),
            supersede = false,
            entityKeyOverride = localId,
        )
        return Outcome.Success(PlaylistId(localId))
    }

    /**
     * Applies one edit locally, then sends it or journals it.
     *
     * Local first, always: the user's list must respond to their finger, and a playlist that only
     * reordered once the server answered would feel broken on any connection worth calling slow.
     */
    private suspend fun mutate(
        id: PlaylistId,
        edit: PlaylistEdit,
        applyLocally: suspend () -> Unit,
    ): Outcome<PlaylistId> {
        applyLocally()
        val localOnly: Boolean = playlistDao.getPlaylist(id.value)?.localOnly == true
        if (!networkMonitor.current().isOnline || localOnly) {
            // A local-only playlist has no server id to address, so its edits wait behind the create
            // that will give it one. Sequence order is what makes that safe.
            writeQueue.enqueue(WriteOperation.EditPlaylist(edit), supersede = false)
            return Outcome.Success(id)
        }
        val sent: Outcome<Unit> = send(edit)
        return when (sent) {
            is Outcome.Success -> Outcome.Success(id)
            is Outcome.Failure -> if (sent.error.isRetryable) {
                writeQueue.enqueue(WriteOperation.EditPlaylist(edit), supersede = false)
                Outcome.Success(id)
            } else {
                sent
            }
        }
    }

    private suspend fun send(edit: PlaylistEdit): Outcome<Unit> = when (edit) {
        is PlaylistEdit.Create -> Outcome.Ok
        is PlaylistEdit.Rename -> networkCall {
            subsonic.updatePlaylist(SubsonicIds.playlistId(edit.id.value), name = edit.name)
        }
        is PlaylistEdit.AddTracks -> networkCall {
            subsonic.updatePlaylist(
                playlistId = SubsonicIds.playlistId(edit.id.value),
                songIdsToAdd = resolveTrackIds(edit.trackKeys),
            )
        }
        is PlaylistEdit.RemoveTracks -> networkCall {
            subsonic.updatePlaylist(
                playlistId = SubsonicIds.playlistId(edit.id.value),
                songIndexesToRemove = edit.positions,
            )
        }
        is PlaylistEdit.Reorder -> networkCall {
            subsonic.createPlaylist(
                playlistId = SubsonicIds.playlistId(edit.id.value),
                songIds = resolveTrackIds(edit.trackKeys),
            )
            Unit
        }
        is PlaylistEdit.Delete -> networkCall {
            subsonic.deletePlaylist(SubsonicIds.playlistId(edit.id.value))
        }
    }

    private suspend fun renameLocally(edit: PlaylistEdit.Rename) {
        val row: PlaylistEntity = playlistDao.getPlaylist(edit.id.value) ?: return
        playlistDao.upsert(
            row.copy(
                name = edit.name,
                nameNormalised = SortKeys.normalise(edit.name),
                updatedAt = nowMillis(),
            ),
        )
    }

    private suspend fun addLocally(edit: PlaylistEdit.AddTracks) {
        val existing: Int = playlistDao.countTracks(edit.id.value)
        playlistDao.upsertTracks(localEntries(edit.id.value, edit.trackKeys, from = existing))
        touch(edit.id.value, existing + edit.trackKeys.size)
    }

    /**
     * Removal is by **index**, which the protocol chose and this must mirror.
     *
     * Positions are renumbered afterwards so the local order stays dense; an offline removal replayed
     * later can still hit the wrong row if the playlist moved server-side, which is the conflict the
     * protocol gives no way to detect and the reason replay is last-write-wins.
     */
    private suspend fun removeLocally(edit: PlaylistEdit.RemoveTracks) {
        val rows: List<PlaylistTrackEntity> = playlistDao.getTracksOf(edit.id.value)
        val drop: Set<Int> = edit.positions.toSet()
        val kept: List<PlaylistTrackEntity> = rows
            .filterIndexed { index, _ -> !drop.contains(index) }
            .mapIndexed { index, row -> row.copy(position = index) }
        playlistDao.replacePlaylistTracks(edit.id.value, kept)
        touch(edit.id.value, kept.size)
    }

    private suspend fun reorderLocally(edit: PlaylistEdit.Reorder) {
        playlistDao.replacePlaylistTracks(
            playlistId = edit.id.value,
            entries = localEntries(edit.id.value, edit.trackKeys, from = 0),
        )
        touch(edit.id.value, edit.trackKeys.size)
    }

    private suspend fun touch(playlistId: String, trackCount: Int) {
        val row: PlaylistEntity = playlistDao.getPlaylist(playlistId) ?: return
        val now: Long = nowMillis()
        playlistDao.upsert(row.copy(trackCount = trackCount, changedAt = now, updatedAt = now))
    }

    /**
     * Playlist entries point at tracks by the stable key and never by `file_id`, and the table has no
     * foreign key to `track`: a delta sync that briefly drops an album must not silently empty a
     * user's playlist.
     */
    private fun localEntries(
        playlistId: String,
        keys: List<TrackKey>,
        from: Int,
    ): List<PlaylistTrackEntity> = keys.mapIndexed { index, key ->
        PlaylistTrackEntity(
            playlistId = playlistId,
            position = from + index,
            releaseGroupMbid = key.releaseGroupMbid.value,
            discNo = key.discNumber,
            trackNo = key.trackNumber,
            sourceFileId = null,
        )
    }

    private suspend fun resolveTrackIds(keys: List<TrackKey>): List<String> = keys.mapNotNull { key ->
        val row: TrackEntity = trackDao.getTrack(
            releaseGroupMbid = key.releaseGroupMbid.value,
            discNo = key.discNumber,
            trackNo = key.trackNumber,
        ) ?: return@mapNotNull null
        if (!EntityMappers.hasPlayableFile(row)) return@mapNotNull null
        SubsonicIds.trackId(row.fileId!!)
    }

    private fun toUnit(outcome: Outcome<PlaylistId>): Outcome<Unit> = when (outcome) {
        is Outcome.Success -> Outcome.Ok
        is Outcome.Failure -> outcome
    }

    public companion object {
        /** Prefix of a provisional id, so a local-only playlist is obvious in a log. */
        public const val LOCAL_ID_PREFIX: String = "local-"
    }
}

private val WriteOperationTypeDb.isPlaylistEdit: Boolean
    get() = this == WriteOperationTypeDb.PLAYLIST_CREATE ||
        this == WriteOperationTypeDb.PLAYLIST_RENAME ||
        this == WriteOperationTypeDb.PLAYLIST_ADD_TRACKS ||
        this == WriteOperationTypeDb.PLAYLIST_REMOVE_TRACKS ||
        this == WriteOperationTypeDb.PLAYLIST_REORDER ||
        this == WriteOperationTypeDb.PLAYLIST_DELETE
