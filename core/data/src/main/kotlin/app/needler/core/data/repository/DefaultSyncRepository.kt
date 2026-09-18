package app.needler.core.data.repository

import app.needler.core.data.local.NeedlerDatabase
import app.needler.core.data.local.dao.SyncStateDao
import app.needler.core.data.local.entity.SyncStateEntity
import app.needler.core.data.mapper.WireTime
import app.needler.core.data.sync.AlbumSyncer
import app.needler.core.data.sync.LibrarySyncEngine
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.data.writequeue.WriteQueueFlusher
import app.needler.core.domain.model.AlbumSyncReport
import app.needler.core.domain.model.FullSyncReason
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.SyncPhase
import app.needler.core.domain.model.SyncReport
import app.needler.core.domain.model.SyncState
import app.needler.core.domain.model.WriteQueueEntry
import app.needler.core.domain.model.WriteQueueFlushReport
import app.needler.core.domain.repository.PlaylistRepository
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.domain.repository.SyncRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration

/**
 * Keeping the mirror current, and replaying what was written while offline.
 *
 * The mirror is never evicted - only refreshed by sync, and only dropped when the server identity
 * changes, because MBIDs are global while `file_id` values and playlist IDs are not.
 *
 * Syncs are serialised behind one lock. Two passes at once would interleave their writes to `album`
 * and `track` and race each other's revision update, and the foreground trigger, the manual button
 * and the background worker can all fire within a second of each other.
 */
public class DefaultSyncRepository(
    private val database: NeedlerDatabase,
    private val syncStateDao: SyncStateDao,
    private val syncEngine: LibrarySyncEngine,
    private val albumSyncer: AlbumSyncer,
    private val writeQueue: WriteQueue,
    private val writeQueueFlusher: WriteQueueFlusher,
    private val playlistRepository: PlaylistRepository,
    private val favouriteRepository: FavouriteRepository,
    private val deleteFile: (String) -> Boolean = { path -> java.io.File(path).delete() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SyncRepository {

    private val phase: MutableStateFlow<SyncPhase> = MutableStateFlow(SyncPhase.IDLE)
    private val lastError: MutableStateFlow<NeedlerError?> = MutableStateFlow(null)
    private val syncLock: Mutex = Mutex()

    override fun observeSyncState(): Flow<SyncState> = combine(
        syncStateDao.observeSyncState(),
        phase,
        lastError,
    ) { row: SyncStateEntity?, currentPhase: SyncPhase, error: NeedlerError? ->
        toState(row, currentPhase, error)
    }

    override suspend fun currentSyncState(): SyncState =
        toState(syncStateDao.getSyncState(), phase.value, lastError.value)

    /**
     * Runs a delta only if the mirror is older than [maxAge]. The foreground trigger is 15 minutes.
     */
    override suspend fun syncIfStale(maxAge: Duration): Outcome<SyncReport> {
        val row: SyncStateEntity? = syncStateDao.getSyncState()
        val lastDelta: Long? = row?.lastDeltaSyncAt
        if (lastDelta != null && nowMillis() - lastDelta < maxAge.inWholeMilliseconds) {
            return Outcome.Success(SyncReport(phase = SyncPhase.IDLE, libraryUnchanged = true))
        }
        return deltaSync()
    }

    override suspend fun deltaSync(force: Boolean): Outcome<SyncReport> = syncLock.withLock {
        phase.value = SyncPhase.DELTA
        try {
            // The queue goes first. A star or a playlist edit made offline must reach the server
            // before the sync reads that server's answer back, or the sync overwrites the user's
            // change with the state it was made against.
            writeQueueFlusher.flush()

            val result: Outcome<SyncReport> = syncEngine.deltaSync(force)
            when (result) {
                is Outcome.Failure -> lastError.value = result.error
                is Outcome.Success -> {
                    lastError.value = null
                    if (!result.value.libraryUnchanged) {
                        // Playlists and stars are not part of the library revision, so a delta that
                        // found work is the right moment to re-read them; one that found none is not.
                        playlistRepository.refreshPlaylists()
                        favouriteRepository.refreshFavourites()
                    }
                }
            }
            result
        } finally {
            phase.value = SyncPhase.IDLE
        }
    }

    override suspend fun fullSync(reason: FullSyncReason): Outcome<SyncReport> = syncLock.withLock {
        phase.value = SyncPhase.FULL
        try {
            val result: Outcome<SyncReport> = syncEngine.fullSync(reason)
            when (result) {
                is Outcome.Failure -> lastError.value = result.error
                is Outcome.Success -> {
                    lastError.value = null
                    playlistRepository.refreshPlaylists()
                    favouriteRepository.refreshFavourites()
                }
            }
            result
        } finally {
            phase.value = SyncPhase.IDLE
        }
    }

    override suspend fun syncAlbum(mbid: ReleaseGroupMbid): Outcome<AlbumSyncReport> =
        albumSyncer.syncAlbum(mbid)

    override suspend fun refreshScanStatus(): Outcome<Unit> = syncEngine.refreshScanStatus()

    // --------------------------------------------------------------- write queue

    override fun observeWriteQueue(): Flow<List<WriteQueueEntry>> = writeQueue.observe()

    override suspend fun flushWriteQueue(): Outcome<WriteQueueFlushReport> =
        Outcome.Success(writeQueueFlusher.flush())

    override suspend fun dropWriteQueueEntry(sequence: Long): Outcome<Unit> {
        writeQueue.delete(sequence)
        return Outcome.Ok
    }

    /**
     * Drops everything that is server-specific and schedules a full sync.
     *
     * MBIDs are global, but `file_id` values, playlist IDs and cached audio are not, so a changed
     * server identity invalidates all of them. The clear runs in a transaction that **returns the
     * file paths rather than deleting them**: files cannot be removed inside a Room transaction, and
     * afterwards there is no record of what was on disk - a caller that ignored the return value
     * would leave orphaned gigabytes behind.
     */
    override suspend fun resetForServerIdentityChange(): Outcome<Unit> = syncLock.withLock {
        val paths: List<String> = database.clearForServerChange(
            newServerIdentity = null,
            now = nowMillis(),
        )
        for (path in paths) {
            runCatching { deleteFile(path) }
        }
        lastError.value = null
        phase.value = SyncPhase.IDLE
        Outcome.Ok
    }

    private fun toState(
        row: SyncStateEntity?,
        currentPhase: SyncPhase,
        error: NeedlerError?,
    ): SyncState = SyncState(
        libraryRevision = row?.libraryRevision,
        lastFullSyncAt = WireTime.fromEpochMillis(row?.lastFullSyncAt),
        lastDeltaSyncAt = WireTime.fromEpochMillis(row?.lastDeltaSyncAt),
        lastScanAt = WireTime.fromEpochMillis(row?.lastScanAt),
        phase = currentPhase,
        // Offline is normal rather than alarming, so it is reported as the last error and nothing
        // more: the mirror is still the read path and the app is still fully usable.
        lastError = error,
    )
}
