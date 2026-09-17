package app.needler.core.domain.repository

import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.EvictionReport
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.Pin
import app.needler.core.domain.model.PinSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.StorageBudget
import app.needler.core.domain.model.StoragePreferences
import app.needler.core.domain.model.StorageUsage
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * On-device audio: pins, the LRU cache of what was played, and the storage budget that bounds the
 * latter.
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

    /** Real usage, split into pinned, cached and artwork as screen 12 requires. */
    public fun observeStorageUsage(): Flow<StorageUsage>

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

    /** Unpins and deletes the on-device copy: the "remove from device" action. */
    public suspend fun unpinAlbum(mbid: ReleaseGroupMbid): Outcome<Unit>

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
     * Runs one LRU pass over unpinned rows until usage fits the budget.
     *
     * Pinned rows are never evicted. If pins alone exceed the budget the report comes back with
     * [EvictionReport.blockedByPins] set, and the UI warns rather than silently deleting what the user
     * asked to keep.
     */
    public suspend fun enforceBudget(): Outcome<EvictionReport>

    public suspend fun setStorageBudget(budget: StorageBudget): Outcome<Unit>

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
