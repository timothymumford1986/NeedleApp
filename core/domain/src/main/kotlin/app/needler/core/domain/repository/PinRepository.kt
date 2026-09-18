package app.needler.core.domain.repository

import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EvictionReport
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
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * On-device audio: downloaded albums the user pinned, and the LRU cache of what was played.
 *
 * The two tiers obey different rules, and the difference is the whole policy:
 *
 *  * **Downloaded** content has no limit and is never evicted automatically. The user asked for it;
 *    only the user takes it away, through [unpinAlbum] or [removeAllFromDevice].
 *  * **Cached while listening** is bounded by device free space, not by a setting - the app keeps
 *    [app.needler.core.domain.model.StorageUsage.freeSpaceFloorBytes] of the device free, which is at
 *    least [app.needler.core.domain.model.StorageUsage.MINIMUM_FREE_SPACE_FLOOR_BYTES] and scales up
 *    with the volume. See [enforceFreeSpaceFloor].
 *
 * One store serves both tiers, with [CachedAudio.pinned] deciding eviction; storing pinned downloads
 * separately would double disk use for no benefit. Everything lives in app-private internal storage,
 * so no permissions are needed and it all disappears on uninstall.
 *
 * Every lookup here is keyed on [TrackKey]. There is deliberately no function that takes a
 * `file_id`: see [app.needler.core.domain.model.FileId] for why keying the cache on it would silently
 * serve users the pre-upgrade, lower-quality copy for ever.
 */
public interface PinRepository {

    /** Every pinned album. */
    public fun observePins(): Flow<List<Pin>>

    public fun observePin(mbid: ReleaseGroupMbid): Flow<Pin?>

    /** Download progress for one pinned album, for the row's progress indicator. */
    public fun observeDownloadState(mbid: ReleaseGroupMbid): Flow<OfflineDownloadState>

    /**
     * Real usage for the Storage screen: downloaded, cached, artwork, and the device's free space.
     *
     * The free-space figure is part of the same snapshot deliberately. "Low on space" is a statement
     * about the device, so a screen that reported usage without it could only compare against a limit
     * - and there is no limit any more.
     */
    public fun observeStorageUsage(): Flow<StorageUsage>

    /**
     * Downloaded albums with the bytes each one occupies, largest first, so the Storage screen can
     * list them and remove them one at a time.
     *
     * This list is the *only* way downloaded bytes ever go away, short of
     * [removeAllFromDevice]: nothing in the app evicts a download, however full the device gets.
     */
    public fun observeDownloadedAlbums(): Flow<List<DownloadedAlbum>>

    public fun observeStoragePreferences(): Flow<StoragePreferences>

    /**
     * Pins an album and starts downloading it.
     *
     * The caller must have checked [app.needler.core.domain.model.ServerCapabilities.libraryDownloadAllowed]
     * first - `PinAlbumForOfflineUseCase` does - because library download is admin-gated and the pin
     * affordance is hidden entirely when it is off. A pin placed anyway fails with
     * [app.needler.core.domain.model.NeedlerError.DownloadForbidden].
     */
    public suspend fun pinAlbum(
        mbid: ReleaseGroupMbid,
        source: PinSource = PinSource.MANUAL,
    ): Outcome<Unit>

    /**
     * Unpins an album and **deletes its bytes**: the "remove from device" action.
     *
     * The deletion is the point, not a side effect. There is no storage limit any more, so this is
     * the only lever the user has on a full device; demoting the album into the cached tier instead
     * would free nothing now, leave the Storage figure unchanged, and hand the bytes to an LRU pass
     * that may never run. The rows and the files both go, immediately.
     *
     * Returns what was actually freed so the UI can say so ("removed 12 tracks, 480 MB"). A pin whose
     * download never landed removes nothing and reports zero, which is a success, not an error.
     */
    public suspend fun unpinAlbum(mbid: ReleaseGroupMbid): Outcome<RemovedDownload>

    /** Restarts a failed or partial pinned download. */
    public suspend fun retryPinnedDownload(mbid: ReleaseGroupMbid): Outcome<Unit>

    /** The cached row for one track, or null when nothing is on the device. */
    public suspend fun getCachedAudio(key: TrackKey): CachedAudio?

    public fun observeCachedAudio(key: TrackKey): Flow<CachedAudio?>

    /**
     * Updates the LRU timestamp for a track that just played. Unpinned rows are evicted by this value
     * ascending.
     */
    public suspend fun recordPlayed(key: TrackKey, at: Instant)

    /**
     * Deletes cached bytes for one track.
     *
     * [reason] is recorded for the diagnostics log; a `416` and a post-upgrade staleness eviction look
     * identical on disk but mean very different things when a bug report arrives.
     */
    public suspend fun evictCachedAudio(key: TrackKey, reason: CacheEvictionReason): Outcome<Unit>

    /**
     * Discards cached bytes whose fetch handle no longer matches the server's, and re-downloads
     * immediately when the track is pinned.
     *
     * This is the concrete form of the quality-upgrade rule: DroppedNeedle replaces files in place, so
     * a cached copy whose `file_id`, size, duration or format has moved is the older, worse copy.
     * Returns true when bytes were discarded.
     */
    public suspend fun invalidateIfStale(key: TrackKey, current: TrackFetchHandle): Boolean

    /**
     * Runs one LRU pass over the cached-while-listening tier so the device keeps
     * [app.needler.core.domain.model.StorageUsage.freeSpaceFloorBytes] free.
     *
     * Pinned rows are never candidates. If the floor is still unmet once everything evictable has
     * gone, the report comes back with [EvictionReport.floorStillUnmet] set: the remaining occupants
     * are downloads and other apps' files, so the UI warns and offers removal rather than the app
     * deleting what the user asked to keep.
     */
    public suspend fun enforceFreeSpaceFloor(): Outcome<EvictionReport>

    /**
     * True when [bytes] can be retained without taking the device below the free-space floor, once
     * the cached tier has given up everything it can.
     *
     * False is not an error: the track still streams, its bytes simply are not kept. The caller must
     * skip the write rather than evicting further, because evicting past the point where the floor is
     * reachable costs the user recently-played music and still does not make room.
     */
    public suspend fun canCacheBytes(bytes: Long): Boolean

    /**
     * "Clear cached music": drops the cached-while-listening tier and leaves every download alone.
     *
     * Safe by construction - those bytes are re-fetchable and were never explicitly requested - which
     * is why it is a separate action from [removeAllFromDevice] and needs no dire confirmation.
     */
    public suspend fun clearCachedAudio(): Outcome<EvictionReport>

    /** "Keep pulled albums on device": auto-pin whatever this device successfully pulls. */
    public suspend fun setKeepPulledAlbumsOnDevice(enabled: Boolean): Outcome<Unit>

    /**
     * "Download to device on Wi-Fi only", defaulting to on.
     *
     * This governs downloading audio, not placing a pull: a pull costs the phone one small request
     * because the server acquires over its own connection.
     */
    public suspend fun setDownloadToDeviceOnWifiOnly(enabled: Boolean): Outcome<Unit>

    /**
     * "Remove all from device": clears audio and artwork.
     *
     * It must never touch the metadata mirror, which would leave the app unable to browse at all.
     */
    public suspend fun removeAllFromDevice(): Outcome<EvictionReport>
}
