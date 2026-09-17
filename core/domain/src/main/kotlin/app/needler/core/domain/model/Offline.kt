package app.needler.core.domain.model

import kotlinx.datetime.Instant

/**
 * A pinned album: content the user asked to keep on the device.
 *
 * Pins are the first offline tier. They are exempt from the storage budget and from LRU eviction: if
 * pins alone exceed the budget the app warns rather than silently evicting what the user explicitly
 * asked to keep.
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
 * The user's on-device audio budget.
 *
 * Pinned content is exempt from it, so this bounds the LRU cache of played tracks rather than total
 * usage.
 */
public sealed interface StorageBudget {
    public data class Limited(val bytes: Long) : StorageBudget {
        init {
            require(bytes > 0) { "Storage budget must be positive" }
        }
    }

    public data object Unlimited : StorageBudget

    public companion object {
        private const val GIGABYTE: Long = 1_073_741_824L

        /** The default drawn on screen 12. */
        public val Default: StorageBudget = Limited(4 * GIGABYTE)

        /** The choices offered: 1, 2, 4, 8, 16 GB and unlimited. */
        public val Presets: List<StorageBudget> = listOf(
            Limited(1 * GIGABYTE),
            Limited(2 * GIGABYTE),
            Limited(4 * GIGABYTE),
            Limited(8 * GIGABYTE),
            Limited(16 * GIGABYTE),
            Unlimited,
        )
    }
}

/**
 * Real on-device usage, split as screen 12 requires so a user can see what a cleanup would free.
 *
 * Artwork has its own small LRU budget and is reported separately.
 */
public data class StorageUsage(
    val pinnedBytes: Long,
    val cachedBytes: Long,
    val artworkBytes: Long,
    val budget: StorageBudget,
) {
    public val totalBytes: Long get() = pinnedBytes + cachedBytes + artworkBytes

    /** True when pins alone exceed the budget: warn, never evict pinned content. */
    public val pinnedExceedBudget: Boolean
        get() = when (val currentBudget: StorageBudget = budget) {
            is StorageBudget.Limited -> pinnedBytes > currentBudget.bytes
            StorageBudget.Unlimited -> false
        }

    /** Bytes of unpinned cache that must be evicted for usage to fit the budget. */
    public val bytesOverBudget: Long
        get() = when (val currentBudget: StorageBudget = budget) {
            is StorageBudget.Limited -> {
                val over: Long = pinnedBytes + cachedBytes - currentBudget.bytes
                if (over > 0) over else 0L
            }
            StorageBudget.Unlimited -> 0L
        }
}

/**
 * Storage and download preferences.
 *
 * [downloadToDeviceOnWifiOnly] is the corrected form of the design pack's "Pull on Wi-Fi only": what
 * consumes mobile data is downloading audio to the device, not asking the server to acquire an album.
 */
public data class StoragePreferences(
    val budget: StorageBudget = StorageBudget.Default,
    /** "Keep pulled albums on device": auto-pin anything this device successfully pulled. */
    val keepPulledAlbumsOnDevice: Boolean = false,
    val downloadToDeviceOnWifiOnly: Boolean = true,
)

/** The outcome of one LRU eviction pass over unpinned audio. */
public data class EvictionReport(
    val evictedTracks: List<TrackKey>,
    val freedBytes: Long,
    /** True when the budget still cannot be met because pinned content alone exceeds it. */
    val blockedByPins: Boolean = false,
)

/**
 * Why cached bytes were discarded. Recorded so the diagnostics log can explain a re-download.
 */
public enum class CacheEvictionReason {
    /** Budget pressure; unpinned rows evicted by last-played ascending. */
    LRU_BUDGET,

    /**
     * The server replaced the file: `file_id`, size, duration or format changed. The cached copy is
     * the older, usually lower-quality one and must go.
     */
    STALE_AFTER_QUALITY_UPGRADE,

    /** A `416` on a range request proved the cached length wrong. */
    RANGE_MISMATCH,

    /** The user unpinned the album or chose "Remove all from device". */
    USER_REQUESTED,
}
