package app.needler.core.data.local.cache

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.projection.EvictionCandidateRow
import app.needler.core.domain.model.StorageUsage

/**
 * On-device audio usage, split by tier exactly as the Storage screen shows it.
 *
 * The split is the point: the user must be able to see what a cleanup would actually free. The two
 * tiers are the domain's "Downloaded" ([pinnedBytes]) and "Cached while listening"
 * ([unpinnedBytes]); only the second can ever be freed by an eviction pass, because the first is
 * what the user explicitly asked to keep.
 */
public data class CacheUsage(
    val pinnedBytes: Long,
    val unpinnedBytes: Long,
    val pinnedTrackCount: Int,
    val unpinnedTrackCount: Int,
) {
    /** What the device is actually holding: the number the "Music kept on device" row shows. */
    public val totalBytes: Long get() = pinnedBytes + unpinnedBytes

    public val trackCount: Int get() = pinnedTrackCount + unpinnedTrackCount

    public companion object {
        public val Empty: CacheUsage = CacheUsage(
            pinnedBytes = 0L,
            unpinnedBytes = 0L,
            pinnedTrackCount = 0,
            unpinnedTrackCount = 0,
        )
    }
}

/**
 * Usage measured against the device's free space, which is the only bound the cache has.
 *
 * There is no budget to compare against: downloads are unlimited, and the cached tier is bounded by
 * [floorBytes] of free space on the volume the audio lives on.
 */
public data class CacheStatus(
    val usage: CacheUsage,
    /** Free bytes on the audio volume, or [FreeSpaceFloor.UNKNOWN] when the probe failed. */
    val deviceFreeBytes: Long,
    /**
     * The floor this volume is held to, from [FreeSpaceFloor.forVolume]. Defaults to the minimum,
     * which is what an unmeasurable volume gets.
     */
    val floorBytes: Long = FreeSpaceFloor.MINIMUM_BYTES,
) {
    /** True when free space could not be read. The policy is then suspended; see [FreeSpaceFloor]. */
    public val freeSpaceUnknown: Boolean get() = FreeSpaceFloor.isUnknown(deviceFreeBytes)

    /**
     * True when the device has less room than the floor.
     *
     * A **statement about the device**, not about a limit the app imposes. Downloads are never
     * evicted because of it: the UI warns and offers per-album removal instead.
     */
    public val deviceLowOnSpace: Boolean
        get() = !freeSpaceUnknown && deviceFreeBytes < floorBytes

    /** How far below the floor the device is. Zero when above it or when free space is unknown. */
    public val freeSpaceShortfallBytes: Long
        get() = if (deviceLowOnSpace) floorBytes - deviceFreeBytes else 0L

    /**
     * How many more bytes may be cached right now without dipping below the floor, before any
     * eviction. Zero once the device is at or under the floor.
     */
    public val cacheHeadroomBytes: Long
        get() = when {
            freeSpaceUnknown -> Long.MAX_VALUE
            deviceFreeBytes <= floorBytes -> 0L
            else -> deviceFreeBytes - floorBytes
        }

    /**
     * Bytes that a "Clear cached music" would free, and the most any eviction pass can ever free.
     * Pinned bytes are absent by design.
     */
    public val reclaimableBytes: Long get() = usage.unpinnedBytes
}

/**
 * The free-space floor that bounds the cached-while-listening tier, and the sentinel for a failed
 * reading.
 *
 * The floor replaced the old user-set budget outright. A budget asked the user to predict how much
 * music they wanted to keep, which nobody can do, and then punished a wrong guess by evicting music
 * on a device with 200 GB spare. A free-space floor measures the only thing that actually matters -
 * how much room the device has left - needs no preference, and self-corrects when the user deletes
 * photos or buys a bigger phone.
 *
 * ## Why the floor is not one number
 *
 * The floor is `max(`[MINIMUM_BYTES]`, `[VOLUME_PERCENT]`% of the volume)`, because a flat figure is
 * wrong at both ends of the range of devices Needler runs on. Two gigabytes is about a sixteenth of
 * a 32 GB phone - a floor that large would keep the cache nearly empty - while on a 1 TB device it
 * is noise, and the system starts complaining about low storage long before the cache respects it.
 * A percentage tracks what the platform itself considers low, and the minimum keeps a small device
 * from setting a floor too small to survive one system update.
 *
 * [forVolume] is the whole computation and it is pure, so the policy can be tested without a device
 * and `EvictionPlanner` can go on taking the floor as a plain parameter.
 */
public object FreeSpaceFloor {

    /**
     * The least free space any device is held to: 2 GB. Single source of truth with the domain
     * constant, so the policy cannot drift between the layer that plans evictions and the layer that
     * reports usage.
     *
     * Also the answer for a volume whose size could not be read: falling back to the minimum keeps
     * some protection, where falling back to zero would remove the floor entirely on exactly the
     * devices that are least well understood.
     */
    public const val MINIMUM_BYTES: Long = StorageUsage.MINIMUM_FREE_SPACE_FLOOR_BYTES

    /**
     * The share of the volume the floor scales to once the volume is large enough for it to exceed
     * [MINIMUM_BYTES], which happens at 100 GB.
     *
     * Two percent is deliberately small: the floor protects the system's headroom, it is not a
     * reserve for the app, and every byte of it is a byte the cache may not use.
     */
    public const val VOLUME_PERCENT: Int = 2

    /**
     * "Free space could not be read." Distinct from zero, which is a real and very alarming reading.
     *
     * A failed probe **suspends** the policy rather than guessing: guessing low would evict a user's
     * recently-played music on a device that is perfectly healthy, and guessing high would let the
     * cache fill a device. Neither is defensible from a reading nobody trusts, and a probe that fails
     * on app-private internal storage is a bug to fix rather than a reason to delete anything.
     *
     * It is a sentinel for the *free* figure only. There is no unknown floor: see [forVolume].
     */
    public const val UNKNOWN: Long = -1L

    public fun isUnknown(freeSpaceBytes: Long): Boolean = freeSpaceBytes < 0L

    /**
     * The floor for a volume of [totalBytes]: the larger of [MINIMUM_BYTES] and [VOLUME_PERCENT] of
     * the volume.
     *
     * A total of zero or less means the volume could not be measured, which answers [MINIMUM_BYTES] -
     * never zero. Unlike a failed *free-space* reading, an unknown volume size does not have to
     * suspend the policy: the free figure is still trustworthy, and holding the device to the
     * minimum is the conservative reading of it.
     *
     * The percentage is taken as `totalBytes / 100 * VOLUME_PERCENT` rather than
     * `totalBytes * VOLUME_PERCENT / 100`, which would overflow on a large volume. The rounding this
     * loses is at most a couple of bytes.
     */
    public fun forVolume(totalBytes: Long): Long {
        if (totalBytes <= 0L) return MINIMUM_BYTES
        return maxOf(MINIMUM_BYTES, totalBytes / 100L * VOLUME_PERCENT)
    }
}

/**
 * One unpinned cached track that may be evicted, with everything the planner needs to order it and
 * everything the caller needs to delete it.
 *
 * Pinned rows are never represented as candidates - the query that produces these filters them out
 * - so no code path can construct an eviction plan that touches a download.
 */
public data class EvictionCandidate(
    val key: TrackKeyDb,
    val filePath: String,
    val sizeBytes: Long,
    /** Epoch milliseconds of the last play; 0 means never played, which evicts first. */
    val lastPlayedAt: Long,
    val downloadedAt: Long?,
)

/**
 * What an eviction pass would do, computed before anything is deleted.
 *
 * Separating the plan from its execution is what makes the policy testable without a database and
 * lets the caller log "this will free 1.2 GB" before touching the disk.
 */
public data class EvictionPlan(
    /** Rows to delete, in the order they were chosen: least recently played first. */
    val victims: List<EvictionCandidate>,
    /** Bytes the plan would free. */
    val freedBytes: Long,
    /** Bytes that had to be freed for the device to sit at or above the floor. */
    val targetBytes: Long,
    /** On-device audio once the plan has been applied. */
    val resultingTotalBytes: Long,
    /**
     * Device free space once the plan has been applied, before any incoming bytes are written.
     * Incoming bytes are excluded because when they would not fit they are not written at all - see
     * [skipsIncoming].
     */
    val resultingFreeBytes: Long,
    /**
     * True when the incoming bytes must **not** be cached: keeping them would breach the floor and
     * eviction cannot make room.
     *
     * Skipping is the correct outcome, not a failure. Streaming still works; the track simply is not
     * retained. Evicting further would cost the user recently-played music and still leave the write
     * unable to fit.
     */
    val skipsIncoming: Boolean,
    val floorBytes: Long,
    val warning: CacheWarning?,
) {
    public val isEmpty: Boolean get() = victims.isEmpty()

    /**
     * True when the device is still below the floor after the plan. Only downloads and other apps'
     * files can cause this, and the app evicts neither.
     *
     * An unknown free-space reading answers false: an unmeasurable device is not a low one, and
     * warning the user off a number nobody could read would be worse than saying nothing.
     */
    public val deviceStillLowOnSpace: Boolean
        get() = !FreeSpaceFloor.isUnknown(resultingFreeBytes) && resultingFreeBytes < floorBytes

    /** True when the plan could not free everything it aimed to. */
    public val targetUnmet: Boolean get() = freedBytes < targetBytes

    public companion object {
        /** A pass with nothing to do: the device is above the floor and the write, if any, fits. */
        public fun nothingToDo(
            totalBytes: Long,
            freeBytes: Long,
            floorBytes: Long = FreeSpaceFloor.MINIMUM_BYTES,
            warning: CacheWarning? = null,
        ): EvictionPlan = EvictionPlan(
            victims = emptyList(),
            freedBytes = 0L,
            targetBytes = 0L,
            resultingTotalBytes = totalBytes,
            resultingFreeBytes = freeBytes,
            skipsIncoming = false,
            floorBytes = floorBytes,
            warning = warning,
        )
    }
}

/** Why an eviction pass could not simply keep the device above its free-space floor. */
public enum class CacheWarning {
    /**
     * Everything evictable has been evicted (or evicting would not have helped) and the device is
     * still below the floor.
     *
     * What remains is downloaded albums and whatever else is on the device. Downloads are never
     * evicted, for any reason, so the only useful action is to report this: the UI tells the user
     * they are low on space and offers per-album removal.
     */
    DEVICE_LOW_ON_SPACE,

    /**
     * The bytes about to be written were not cached, because keeping them would have taken the device
     * below the floor and eviction could not make room.
     *
     * Not an error. The stream plays; it is simply not retained for next time.
     */
    INCOMING_NOT_CACHED,
}

/** The outcome of actually applying an [EvictionPlan]. */
public data class EvictionResult(
    val evictedKeys: List<TrackKeyDb>,
    val freedBytes: Long,
    /**
     * Files that could not be deleted. Their rows are kept, so the next pass retries them rather
     * than losing track of bytes that are still on disk.
     */
    val failedPaths: List<String>,
) {
    public companion object {
        public val Empty: EvictionResult = EvictionResult(
            evictedKeys = emptyList(),
            freedBytes = 0L,
            failedPaths = emptyList(),
        )
    }
}

/**
 * Audio rows that have just been dropped from the index, and the files that go with them.
 *
 * Returned by the clearing paths on
 * [app.needler.core.data.local.NeedlerDatabase] - most importantly
 * [app.needler.core.data.local.NeedlerDatabase.removeDownloadedAlbum] - because file deletion cannot
 * happen inside a Room transaction. The rows go in the transaction; the caller unlinks [filePaths]
 * afterwards. Anything it misses becomes an orphaned file that only an uninstall reclaims.
 *
 * [bytes] rides along rather than being recomputed from disk for one reason: the index is the only
 * thing that still knows what those rows weighed once they have been deleted, and removing a
 * download has to be able to report what it freed. It is the index's accounting, so a file already
 * missing from disk is still counted - which is correct, because that is the figure the Storage
 * screen was showing the user a moment ago.
 */
public data class RemovedAudio(
    val filePaths: List<String>,
    val bytes: Long,
) {
    /** How many tracks left the device: one file per row. */
    public val trackCount: Int get() = filePaths.size

    public val isEmpty: Boolean get() = filePaths.isEmpty()

    public companion object {
        /** Nothing was on the device - an album pinned but never downloaded, say. */
        public val Empty: RemovedAudio = RemovedAudio(filePaths = emptyList(), bytes = 0L)

        /**
         * Sums rows read **before** they were deleted. Reading them first is not an optimisation:
         * after the delete there is no record of what was on disk or what it weighed.
         */
        public fun of(rows: List<EvictionCandidateRow>): RemovedAudio {
            if (rows.isEmpty()) return Empty
            return RemovedAudio(
                filePaths = rows.map { it.filePath },
                bytes = rows.sumOf { it.sizeBytes },
            )
        }
    }
}
