package app.needler.core.data.repository

import app.needler.core.data.background.BackgroundWorkScheduler
import app.needler.core.data.local.NeedlerDatabase
import app.needler.core.data.local.cache.CacheIndex
import app.needler.core.data.local.cache.CacheStatus
import app.needler.core.data.local.cache.DeviceFreeSpace
import app.needler.core.data.local.cache.EvictionResult
import app.needler.core.data.local.cache.FreeSpaceFloor
import app.needler.core.data.local.cache.RemovedAudio
import app.needler.core.data.local.dao.AlbumDao
import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.dao.PinDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.AlbumEntity
import app.needler.core.data.local.entity.AlbumStateDb
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import app.needler.core.data.platform.ArtworkCacheSize
import app.needler.core.data.local.projection.CacheUsageRow
import app.needler.core.data.local.projection.EvictionCandidateRow
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.settings.NeedlerSettingsStore
import app.needler.core.data.settings.StorageSettings
import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EvictionReport
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.StoragePreferences
import app.needler.core.domain.model.StorageUsage
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.repository.PinRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

/**
 * On-device audio: the albums the user pinned, and the LRU cache of what was played.
 *
 * One store serves both tiers with `audio_cache.pinned` deciding eviction, because storing pinned
 * downloads separately would double disk use for no benefit. The two tiers are bounded by completely
 * different things and that difference is the whole policy:
 *
 *  * **Downloaded** content has no limit and is **never evicted automatically**. The eviction
 *    candidate query filters pinned rows out entirely, so no code path can even construct a plan
 *    that names one as a victim.
 *  * **Cached while listening** is bounded by device free space, never by a setting. Bytes are kept
 *    only while keeping them leaves the device above its floor.
 *
 * Nothing here downloads. Pinning records the intent and leaves the row in
 * [OfflineDownloadState.Queued]; the per-track `Range` GETs that fill it are a `WorkManager` job,
 * because they must survive the screen and the process.
 */
public class DefaultPinRepository(
    private val database: NeedlerDatabase,
    private val pinDao: PinDao,
    private val audioCacheDao: AudioCacheDao,
    private val albumDao: AlbumDao,
    private val trackDao: TrackDao,
    private val cacheIndex: CacheIndex,
    private val deviceFreeSpace: DeviceFreeSpace,
    private val settingsStore: NeedlerSettingsStore,
    private val artworkCacheSize: ArtworkCacheSize,
    private val deleteFile: (String) -> Boolean = { path -> java.io.File(path).delete() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * Starts and stops the `WorkManager` job that actually fetches the bytes.
     *
     * Still nothing here downloads: this class records intent and asks for the job. The distinction
     * is the reason pinning is fast and survives the screen - the per-track `Range` GETs have to
     * outlive both, so they cannot be repository work. Defaults to
     * [BackgroundWorkScheduler.None] so the storage-policy tests construct this class without a
     * `WorkManager`.
     */
    private val workScheduler: BackgroundWorkScheduler = BackgroundWorkScheduler.None,
) : PinRepository {

    // ---------------------------------------------------------------------- reads

    override fun observePins(): Flow<List<Pin>> =
        pinDao.observePinnedAlbums().map { rows -> rows.map(EntityMappers::pin) }

    override fun observePin(mbid: ReleaseGroupMbid): Flow<Pin?> =
        pinDao.observePin(mbid.value).map { row -> row?.let(EntityMappers::pin) }

    override fun observeDownloadState(mbid: ReleaseGroupMbid): Flow<OfflineDownloadState> =
        pinDao.observePin(mbid.value).map { row ->
            row?.let(EntityMappers::offlineDownloadState) ?: OfflineDownloadState.Queued
        }

    /**
     * Real usage, split by tier, plus how much room the device has left.
     *
     * The free-space figure belongs in the same snapshot deliberately: "low on space" is a statement
     * about the device, so a screen that reported usage without it could only compare against a
     * limit - and there is no limit any more. The floor travels with it for the same reason, because
     * it is computed from this device's volume rather than read from a constant.
     */
    override fun observeStorageUsage(): Flow<StorageUsage> =
        audioCacheDao.observeUsage().map { row: CacheUsageRow ->
            val free: Long = deviceFreeSpace.freeBytes()
            StorageUsage(
                downloadedBytes = row.pinnedBytes,
                cachedBytes = row.unpinnedBytes,
                artworkBytes = artworkCacheSize.bytes(),
                // An unmeasurable volume reports zero free rather than a guess. Guessing low deletes
                // a healthy device's music and guessing high fills it, so the eviction pass suspends
                // itself instead - see `canCacheBytes`.
                deviceFreeBytes = if (FreeSpaceFloor.isUnknown(free)) 0L else free,
                freeSpaceFloorBytes = deviceFreeSpace.floorBytes(),
            )
        }

    override fun observeDownloadedAlbums(): Flow<List<DownloadedAlbum>> =
        pinDao.observeDownloadedAlbums().map { rows -> rows.map(EntityMappers::downloadedAlbum) }

    override fun observeStoragePreferences(): Flow<StoragePreferences> =
        settingsStore.storage.map { settings: StorageSettings ->
            StoragePreferences(
                keepPulledAlbumsOnDevice = settings.keepPulledAlbumsOnDevice,
                downloadToDeviceOnWifiOnly = settings.downloadToDeviceOnWifiOnly,
            )
        }

    override suspend fun getCachedAudio(key: TrackKey): CachedAudio? = audioCacheDao.get(
        releaseGroupMbid = key.releaseGroupMbid.value,
        discNo = key.discNumber,
        trackNo = key.trackNumber,
    )?.let(EntityMappers::cachedAudio)

    override fun observeCachedAudio(key: TrackKey): Flow<CachedAudio?> =
        audioCacheDao.observeAlbumCacheState(key.releaseGroupMbid.value)
            .map { rows ->
                rows.firstOrNull { it.key.discNo == key.discNumber && it.key.trackNo == key.trackNumber }
            }
            .map { row ->
                if (row == null) null else getCachedAudio(key)
            }

    // --------------------------------------------------------------------- writes

    /**
     * Pins an album and records the intent to download it.
     *
     * The caller must already have checked `ServerCapabilities.libraryDownloadAllowed` - library
     * download is admin-gated and the affordance is hidden entirely when it is off - so a pin placed
     * anyway fails with [NeedlerError.DownloadForbidden] rather than a `403` much later.
     */
    override suspend fun pinAlbum(mbid: ReleaseGroupMbid, source: PinSource): Outcome<Unit> {
        val album: AlbumEntity = albumDao.getAlbum(mbid.value)
            ?: return Outcome.Failure(NeedlerError.NotFound("album " + mbid.value))
        if (!album.state.isInLibrary) {
            return Outcome.Failure(
                NeedlerError.Rejected(message = "album " + mbid.value + " is not owned; pull it first"),
            )
        }
        val now: Long = nowMillis()
        val trackCount: Int = trackDao.getAlbumTracks(mbid.value).size
        val existing: PinEntity? = pinDao.getPin(mbid.value)
        pinDao.upsert(
            PinEntity(
                releaseGroupMbid = mbid.value,
                pinnedAt = existing?.pinnedAt ?: now,
                source = EntityMappers.pinSourceDb(source),
                downloadState = DownloadStateDb.QUEUED,
                tracksComplete = audioCacheDao.getAlbumRows(mbid.value).count { it.complete },
                tracksTotal = trackCount,
                downloadedBytes = null,
                totalBytes = album.sizeBytes,
                error = null,
                updatedAt = now,
            ),
        )
        // Bytes already cached from listening are promoted rather than re-fetched: one store, one
        // flag. That is the whole reason the pinned and cached tiers share a table.
        audioCacheDao.setAlbumPinned(mbid.value, pinned = true)
        albumDao.setState(mbid.value, AlbumStateDb.PINNED, inLibrary = true, updatedAt = now)
        // The pin is intent; this is what turns it into bytes on the device.
        workScheduler.scheduleAlbumDownload(mbid.value)
        return Outcome.Ok
    }

    /**
     * Unpins and **deletes the bytes**: the "remove from device" action.
     *
     * The deletion is the point, not a side effect. With no storage limit left in the product this is
     * the user's only lever on a full device, so demoting the album into the cached tier would free
     * nothing now, leave the Storage figure unchanged, and hand the bytes to an LRU pass that might
     * never run. The rows and the files both go, and the figure reported back is what actually left
     * the disk.
     */
    override suspend fun unpinAlbum(mbid: ReleaseGroupMbid): Outcome<RemovedDownload> {
        // The transaction collects the paths before dropping the rows, because afterwards there is no
        // record of what was on disk - and files cannot be deleted inside a Room transaction.
        // The job goes first. A download still running would re-create rows for the album whose
        // files this call is about to unlink, and the Storage figure would go back up on its own.
        workScheduler.cancelAlbumDownload(mbid.value)
        val removed: RemovedAudio = database.removeDownloadedAlbum(mbid.value)
        var freed = 0L
        var files = 0
        for (path in removed.filePaths) {
            if (runCatching { deleteFile(path) }.getOrDefault(false)) files++
        }
        freed = removed.bytes
        val now: Long = nowMillis()
        albumDao.setState(mbid.value, AlbumStateDb.OWNED, inLibrary = true, updatedAt = now)
        return Outcome.Success(
            if (removed.isEmpty) {
                // A pin whose download never landed removes nothing and reports zero. That is a
                // success, not an error.
                RemovedDownload.nothing(mbid)
            } else {
                RemovedDownload(
                    releaseGroupMbid = mbid,
                    removedTracks = files,
                    freedBytes = freed,
                )
            },
        )
    }

    override suspend fun retryPinnedDownload(mbid: ReleaseGroupMbid): Outcome<Unit> {
        val pin: PinEntity = pinDao.getPin(mbid.value)
            ?: return Outcome.Failure(NeedlerError.NotFound("pin " + mbid.value))
        pinDao.setDownloadProgress(
            releaseGroupMbid = mbid.value,
            downloadState = DownloadStateDb.QUEUED,
            tracksComplete = audioCacheDao.getAlbumRows(mbid.value).count { it.complete },
            tracksTotal = pin.tracksTotal,
            downloadedBytes = pin.downloadedBytes,
            totalBytes = pin.totalBytes,
            error = null,
            updatedAt = nowMillis(),
        )
        workScheduler.scheduleAlbumDownload(mbid.value)
        return Outcome.Ok
    }

    override suspend fun recordPlayed(key: TrackKey, at: Instant) {
        cacheIndex.recordPlay(EntityMappers.trackKeyDb(key), at.toEpochMilliseconds())
    }

    /**
     * Deletes cached bytes for one track.
     *
     * The reason is recorded for the diagnostics log: a `416` and a post-upgrade staleness eviction
     * look identical on disk and mean very different things when a bug report arrives.
     */
    override suspend fun evictCachedAudio(
        key: TrackKey,
        reason: CacheEvictionReason,
    ): Outcome<Unit> {
        val row: AudioCacheEntity = audioCacheDao.get(
            releaseGroupMbid = key.releaseGroupMbid.value,
            discNo = key.discNumber,
            trackNo = key.trackNumber,
        ) ?: return Outcome.Ok
        runCatching { deleteFile(row.filePath) }
        audioCacheDao.delete(key.releaseGroupMbid.value, key.discNumber, key.trackNumber)
        if (row.pinned) markPinPartial(key.releaseGroupMbid)
        return Outcome.Ok
    }

    /**
     * Discards cached bytes whose fingerprint no longer matches the server's.
     *
     * The comparison is against the fingerprint **snapshotted on the `audio_cache` row at download
     * time**, never against the `track` row - sync has already overwritten that with the very values
     * being compared. A field counts as changed only when both sides carry a value, so a server that
     * stops reporting durations does not condemn the whole offline library.
     */
    override suspend fun invalidateIfStale(key: TrackKey, current: TrackFetchHandle): Boolean {
        val stale: Boolean = audioCacheDao.isStale(
            releaseGroupMbid = key.releaseGroupMbid.value,
            discNo = key.discNumber,
            trackNo = key.trackNumber,
            fileId = current.fileId.value.takeIf { it != EntityMappers.MISSING_FILE_ID },
            sizeBytes = current.sizeBytes,
            durationMs = current.durationMs,
            format = current.format?.name?.lowercase(),
        )
        if (!stale) return false
        evictCachedAudio(key, CacheEvictionReason.STALE_AFTER_QUALITY_UPGRADE)
        return true
    }

    /**
     * One LRU pass over the cached-while-listening tier.
     *
     * Pinned rows are never candidates. When the floor is still unmet with everything evictable gone,
     * the report says so: what remains is downloads and other apps' files, and the app evicts
     * neither - the UI warns and offers removal instead.
     */
    override suspend fun enforceFreeSpaceFloor(): Outcome<EvictionReport> {
        val (plan, result) = cacheIndex.evictToFit(incomingBytes = 0L, deleteFile = deleteFile)
        return Outcome.Success(
            EvictionReport(
                evictedTracks = result.evictedKeys.map(EntityMappers::trackKey),
                freedBytes = result.freedBytes,
                floorStillUnmet = plan.deviceStillLowOnSpace,
            ),
        )
    }

    /**
     * True when [bytes] can be retained without taking the device below the floor.
     *
     * False is not an error: the track still streams, its bytes are simply not kept. The caller must
     * skip the write rather than evicting further, because evicting past the point where the floor is
     * reachable costs the user recently played music *and* still does not make room.
     */
    override suspend fun canCacheBytes(bytes: Long): Boolean {
        val status: CacheStatus = cacheIndex.status()
        // An unmeasurable volume suspends the policy rather than guessing.
        if (status.freeSpaceUnknown) return true
        return cacheIndex.canStore(bytes)
    }

    override suspend fun clearCachedAudio(): Outcome<EvictionReport> {
        val result: EvictionResult = cacheIndex.clearCached(deleteFile)
        return Outcome.Success(
            EvictionReport(
                evictedTracks = result.evictedKeys.map(EntityMappers::trackKey),
                freedBytes = result.freedBytes,
                floorStillUnmet = deviceFreeSpace.freeBytes() in 0 until deviceFreeSpace.floorBytes(),
            ),
        )
    }

    override suspend fun setKeepPulledAlbumsOnDevice(enabled: Boolean): Outcome<Unit> {
        settingsStore.setKeepPulledAlbumsOnDevice(enabled)
        return Outcome.Ok
    }

    override suspend fun setDownloadToDeviceOnWifiOnly(enabled: Boolean): Outcome<Unit> {
        settingsStore.setDownloadToDeviceOnWifiOnly(enabled)
        return Outcome.Ok
    }

    /**
     * "Remove all from device": clears audio and artwork, and **never the metadata mirror**.
     *
     * Removing the mirror would leave the app unable to browse at all. The download records go with
     * the bytes, or the downloader would immediately fetch everything again.
     */
    override suspend fun removeAllFromDevice(): Outcome<EvictionReport> {
        // The keys and their accounted sizes are read **before** the transaction drops the rows:
        // afterwards there is no record of what was on disk, and a caller holding only file paths
        // could not say what the removal freed.
        val doomed: List<EvictionCandidateRow> = audioCacheDao.getAllRowsForRemoval()
        // Every download job is stopped before the rows go, for the same reason as in unpinAlbum:
        // a job still in flight would immediately start putting back what the user just removed.
        doomed.map { it.key.releaseGroupMbid }.distinct().forEach { mbid ->
            workScheduler.cancelAlbumDownload(mbid)
        }
        val paths: List<String> = database.clearAllAudio()
        for (path in paths) {
            runCatching { deleteFile(path) }
        }
        artworkCacheSize.clear()
        return Outcome.Success(
            EvictionReport(
                evictedTracks = doomed.map { EntityMappers.trackKey(it.key) },
                // What the cache index accounted for these rows, which is what the Storage screen
                // listed against them.
                freedBytes = doomed.sumOf { it.sizeBytes },
                floorStillUnmet = false,
            ),
        )
    }

    // ------------------------------------------------------------------ internals

    /**
     * An eviction from a pinned album leaves it incomplete, so the green check has to go.
     *
     * Leaving the album marked complete after a staleness eviction is exactly how a silent downgrade
     * hides: the user sees a tick and hears the older file.
     */
    private suspend fun markPinPartial(mbid: ReleaseGroupMbid) {
        val pin: PinEntity = pinDao.getPin(mbid.value) ?: return
        val onDevice: Int = audioCacheDao.getAlbumRows(mbid.value).count { it.complete }
        pinDao.setDownloadProgress(
            releaseGroupMbid = mbid.value,
            downloadState = if (onDevice > 0) DownloadStateDb.PARTIAL else DownloadStateDb.QUEUED,
            tracksComplete = onDevice,
            tracksTotal = pin.tracksTotal,
            downloadedBytes = pin.downloadedBytes,
            totalBytes = pin.totalBytes,
            error = null,
            updatedAt = nowMillis(),
        )
    }
}
