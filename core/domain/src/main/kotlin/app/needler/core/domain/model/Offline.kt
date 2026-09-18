package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * A pinned album: content the user asked to keep on the device.
 *
 * Pins are the first offline tier and they have **no limit of any kind**. The user chose them
 * explicitly, so nothing in the app ever evicts one: not a cache pass, not a low-disk condition, not
 * a setting. When pinned downloads themselves fill the device the app reports it - see
 * [StorageUsage.deviceLowOnSpace] - and the user removes what they no longer want by hand.
 */
public data class Pin(
    val releaseGroupMbid: ReleaseGroupMbid,
    val pinnedAt: Instant,
    val source: PinSource,
    val download: OfflineDownloadState,
)

/** Why an album is pinned. */
public enum class PinSource {
    /** The user tapped "Pull local". */
    MANUAL,

    /**
     * Auto-pinned because "Keep pulled albums on device" is on and this device successfully pulled the
     * album, so newly acquired music is already offline next time.
     */
    AUTO_PULLED,
}

/**
 * Progress of getting a pinned album's audio onto the device.
 *
 * Downloads use per-track `Range` GETs against `download?id=`, which resume after interruption. The
 * album-zip endpoint is deliberately unused: it cannot resume and gives no per-track progress.
 */
public sealed interface OfflineDownloadState {
    /** Pinned, nothing fetched yet. */
    public data object Queued : OfflineDownloadState

    /** Fetching. Counts are per track so the UI can show "4 of 12". */
    public data class Downloading(
        val tracksComplete: Int,
        val tracksTotal: Int,
        val downloadedBytes: Long? = null,
        val totalBytes: Long? = null,
    ) : OfflineDownloadState {
        /** 0f..1f, or null when the server gave no totals. */
        public val fraction: Float?
            get() = if (tracksTotal > 0) tracksComplete.toFloat() / tracksTotal.toFloat() else null
    }

    /**
     * Held because "Download to device on Wi-Fi only" is on and the connection is metered.
     *
     * Note this setting governs *downloading audio to the device*, not placing a pull: a pull costs the
     * phone one small request, since the server does the acquiring over its own connection.
     */
    public data object WaitingForUnmeteredNetwork : OfflineDownloadState

    /** Every track of the album is on the device. This is what draws the green check on artwork. */
    public data object Complete : OfflineDownloadState

    /** Some tracks are on the device and the rest failed or were evicted as stale. */
    public data class Partial(
        val tracksComplete: Int,
        val tracksTotal: Int,
    ) : OfflineDownloadState

    /** Download failed. [error] distinguishes an admin-disabled download from a transport failure. */
    public data class Failed(
        val error: NeedlerError,
    ) : OfflineDownloadState
}

/**
 * Real on-device usage, split by tier, plus how much room the device itself has left.
 *
 * ## Why there is no budget here any more
 *
 * There is no user-facing storage limit. The two tiers are bounded by completely different things,
 * and neither of them is a number the user picks:
 *
 *  * **Downloaded** ([downloadedBytes]) is what the user explicitly asked to keep. It has no limit
 *    and is never evicted automatically. The user sees the figure and removes albums by hand.
 *  * **Cached while listening** ([cachedBytes]) fills up silently as a side effect of streaming, so
 *    it cannot be unbounded or the app quietly eats the device. It is bounded by [deviceFreeBytes]
 *    against [freeSpaceFloorBytes] instead: bytes are only retained while retaining them leaves
 *    the device at least that much room.
 *
 * A device-free-space floor is the right bound because it measures the thing the user actually cares
 * about - "is my phone full" - and it needs no preference, no default to argue about and no
 * migration when the user moves from a 64 GB phone to a 512 GB one.
 *
 * [artworkBytes] is reported separately because artwork has its own small LRU and is worth showing
 * as a distinct line: it is usually tiny, and a user hunting for gigabytes should not spend a tap on
 * it.
 */
public data class StorageUsage(
    /** The Downloaded tier: bytes of pinned albums. Never evicted automatically. */
    val downloadedBytes: Long,
    /** The Cached-while-listening tier: re-fetchable bytes retained as a side effect of streaming. */
    val cachedBytes: Long,
    /** Artwork at display sizes, on its own small LRU. */
    val artworkBytes: Long,
    /** Free space on the volume the audio directory lives on, as the device reports it now. */
    val deviceFreeBytes: Long,
    /**
     * The floor this device is actually held to, which is **not** a fixed number.
     *
     * It is [MINIMUM_FREE_SPACE_FLOOR_BYTES] or a small percentage of the volume, whichever is
     * larger, computed by the layer that measures the volume - a flat 2 GB is a sixteenth of a small
     * phone and a rounding error on a 1 TB one, so the same constant cannot serve both. It is carried
     * on the snapshot rather than read from a constant so that everything the screen says about
     * "low on space" is derived from one measurement of one device.
     *
     * Defaults to the minimum, which is the honest answer when the volume could not be measured.
     */
    val freeSpaceFloorBytes: Long = MINIMUM_FREE_SPACE_FLOOR_BYTES,
) {
    /** Audio only: what the "Music kept on device" row shows before the artwork line. */
    public val audioBytes: Long get() = downloadedBytes + cachedBytes

    /** Everything Needler is holding on the device. */
    public val totalBytes: Long get() = downloadedBytes + cachedBytes + artworkBytes

    /**
     * True when the device has less than [freeSpaceFloorBytes] free.
     *
     * This is a **statement about the device, not about a limit the app imposes**. It is what
     * replaced the old "pins exceed the budget" flag: pinned downloads can legitimately fill a phone,
     * and when they do the correct response is to tell the user they are low on space and offer to
     * remove albums - never to evict a download they chose to keep.
     */
    public val deviceLowOnSpace: Boolean get() = deviceFreeBytes < freeSpaceFloorBytes

    /**
     * How far below the floor the device is, in bytes; zero when it is above it.
     *
     * Useful only for phrasing the warning ("free about 700 MB"). Clearing the whole cached tier may
     * not close it, because the occupant is usually the downloaded tier or other apps' files.
     */
    public val freeSpaceShortfallBytes: Long
        get() = if (deviceFreeBytes < freeSpaceFloorBytes) {
            freeSpaceFloorBytes - deviceFreeBytes
        } else {
            0L
        }

    /**
     * Bytes a "Clear cached music" would free. Safe to offer behind a single tap: these bytes are
     * re-fetchable and were never explicitly requested, unlike the downloaded tier.
     */
    public val reclaimableWithoutLossBytes: Long get() = cachedBytes

    public companion object {
        private const val GIGABYTE: Long = 1_073_741_824L

        /**
         * The least free space any device is ever held to: 2 GB.
         *
         * The floor exists so the cached tier can never be the reason a phone runs out of room: the
         * system needs headroom for updates, photos and its own housekeeping, and a music app that
         * silently consumes the last gigabyte is a bug however cheap those bytes were to fetch. Two
         * gigabytes is about one system update plus working room, and it is deliberately not a
         * setting - a floor the user can lower is a floor that stops protecting them.
         *
         * This is the **minimum**, not the whole rule. The effective floor is computed per device
         * from the size of the volume the audio lives on, because 2 GB is a sixteenth of a 32 GB
         * phone and noise on a 1 TB one; see [freeSpaceFloorBytes], which carries the answer for the
         * device this snapshot describes. The computation lives where the volume is measured, in
         * `app.needler.core.data.local.cache.FreeSpaceFloor`, so the domain exposes the bound it
         * guarantees and nothing more.
         */
        public const val MINIMUM_FREE_SPACE_FLOOR_BYTES: Long = 2 * GIGABYTE

        /** Nothing on the device, and nothing known about the volume yet. */
        public val Empty: StorageUsage = StorageUsage(
            downloadedBytes = 0L,
            cachedBytes = 0L,
            artworkBytes = 0L,
            deviceFreeBytes = 0L,
            freeSpaceFloorBytes = MINIMUM_FREE_SPACE_FLOOR_BYTES,
        )
    }
}

/**
 * One downloaded album as the Storage screen lists it, so the user can remove the big ones first.
 *
 * [sizeBytes] is what is actually on disk for the album, not what the server says it weighs: a
 * part-downloaded album, or one partly evicted as stale, must show the bytes a removal would really
 * free.
 */
public data class DownloadedAlbum(
    val releaseGroupMbid: ReleaseGroupMbid,
    val title: String,
    val artistName: String,
    val sizeBytes: Long,
    val pinnedAt: Instant,
)

/**
 * What removing one download actually gave back to the device.
 *
 * Removing a download is the **only lever the user has on a full device**: there is no storage limit
 * to lower and nothing in the app evicts a download. So the action has to report what it freed, and
 * the figure has to be real - bytes that left the disk, not rows that changed tier - or the Storage
 * screen tells the user the removal did nothing while their device is still full.
 *
 * [freedBytes] is what the cache index accounted for the album, which is what the Storage screen
 * listed against it; [removedTracks] is how many files went with it.
 */
public data class RemovedDownload(
    val releaseGroupMbid: ReleaseGroupMbid,
    val removedTracks: Int,
    val freedBytes: Long,
) {
    public companion object {
        /** Nothing was on the device for this album: unpinning a pin whose download never landed. */
        public fun nothing(releaseGroupMbid: ReleaseGroupMbid): RemovedDownload = RemovedDownload(
            releaseGroupMbid = releaseGroupMbid,
            removedTracks = 0,
            freedBytes = 0L,
        )
    }
}

/**
 * Storage and download preferences.
 *
 * There is no storage-limit preference: the downloaded tier is unlimited by decision, and the cached
 * tier is bounded by the device's free-space floor (at least
 * [StorageUsage.MINIMUM_FREE_SPACE_FLOOR_BYTES]), which is invisible and self-managing.
 *
 * [downloadToDeviceOnWifiOnly] is the corrected form of the design pack's "Pull on Wi-Fi only": what
 * consumes mobile data is downloading audio to the device, not asking the server to acquire an album.
 */
public data class StoragePreferences(
    /** "Keep pulled albums on device": auto-pin anything this device successfully pulled. */
    val keepPulledAlbumsOnDevice: Boolean = false,
    val downloadToDeviceOnWifiOnly: Boolean = true,
)

/**
 * The outcome of one pass over the cached-while-listening tier.
 *
 * Only that tier is ever touched, so a pass can legitimately fall short of the free-space floor: see
 * [floorStillUnmet].
 */
public data class EvictionReport(
    val evictedTracks: List<TrackKey>,
    val freedBytes: Long,
    /**
     * True when the device is still below [StorageUsage.freeSpaceFloorBytes] after the pass.
     *
     * What remains is downloaded albums and whatever else is on the device, and the app evicts
     * neither. The UI warns and offers removal; it must never read this flag as licence to delete a
     * download.
     */
    val floorStillUnmet: Boolean = false,
) {
    public companion object {
        public val Empty: EvictionReport = EvictionReport(evictedTracks = emptyList(), freedBytes = 0L)
    }
}

/**
 * Why cached bytes were discarded. Recorded so the diagnostics log can explain a re-download.
 */
public enum class CacheEvictionReason {
    /**
     * Free-space pressure: unpinned rows evicted by last-played ascending so the device keeps at
     * least [StorageUsage.freeSpaceFloorBytes] free. Pinned rows are never discarded for this.
     */
    LRU_FREE_SPACE,

    /**
     * The server replaced the file: `file_id`, size, duration or format changed. The cached copy is
     * the older, usually lower-quality one and must go.
     */
    STALE_AFTER_QUALITY_UPGRADE,

    /** A `416` on a range request proved the cached length wrong. */
    RANGE_MISMATCH,

    /** The user unpinned the album, cleared the cached tier, or chose "Remove all from device". */
    USER_REQUESTED,
}
