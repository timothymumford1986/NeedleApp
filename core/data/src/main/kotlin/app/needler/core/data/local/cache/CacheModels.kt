package app.needler.core.data.local.cache

import app.needler.core.data.local.TrackKeyDb

/**
 * On-device audio usage, split by tier exactly as screen 12 shows it.
 *
 * The split is the point: "Show real usage, as screen 12 does, split into pinned and cached so the
 * user can see what a cleanup would actually free." Only [unpinnedBytes] can ever be freed by an
 * eviction pass.
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

/** Usage measured against the user's budget. */
public data class CacheStatus(
    val usage: CacheUsage,
    /** The user's limit in bytes, or [CacheBudget.UNLIMITED]. */
    val budgetBytes: Long,
) {
    public val isUnlimited: Boolean get() = CacheBudget.isUnlimited(budgetBytes)

    /** Bytes by which total usage exceeds the budget. Zero when within it, or when unlimited. */
    public val overBudgetBytes: Long
        get() = if (isUnlimited) 0L else maxOf(0L, usage.totalBytes - budgetBytes)

    /**
     * True when pinned content alone exceeds the budget.
     *
     * This is a **warning, not an eviction trigger**: pinned bytes are what the user explicitly
     * asked to keep, and REQUIREMENTS.md says to warn rather than silently evict them.
     */
    public val pinnedExceedBudget: Boolean
        get() = !isUnlimited && usage.pinnedBytes > budgetBytes

    /**
     * How many more bytes of unpinned cache fit before the budget is reached. Never negative; zero
     * once pins alone fill the budget.
     */
    public val headroomBytes: Long
        get() = if (isUnlimited) Long.MAX_VALUE else maxOf(0L, budgetBytes - usage.totalBytes)
}

/** Budget helpers. The stored setting is a byte count, with one sentinel for "no limit". */
public object CacheBudget {

    /** Sentinel for the "Unlimited" choice on screen 12's storage-limit picker. */
    public const val UNLIMITED: Long = -1L

    private const val GIGABYTE: Long = 1_073_741_824L

    /** The default drawn on screen 12. */
    public const val DEFAULT_BYTES: Long = 4 * GIGABYTE

    /** The choices REQUIREMENTS.md lists: 1, 2, 4, 8, 16 GB and unlimited. */
    public val PRESETS: List<Long> = listOf(
        1 * GIGABYTE,
        2 * GIGABYTE,
        4 * GIGABYTE,
        8 * GIGABYTE,
        16 * GIGABYTE,
        UNLIMITED,
    )

    /** Treats every non-positive value as unlimited, so a corrupt zero cannot evict the cache. */
    public fun isUnlimited(budgetBytes: Long): Boolean = budgetBytes <= 0L
}

/**
 * One unpinned cached track that may be evicted, with everything the planner needs to order it and
 * everything the caller needs to delete it.
 *
 * Pinned rows are never represented as candidates - the query that produces these filters them out
 * - so no code path can construct an eviction plan that touches a pin.
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
 * lets the caller log or show "this will free 1.2 GB" before touching the disk.
 */
public data class EvictionPlan(
    /** Rows to delete, in the order they were chosen: least recently played first. */
    val victims: List<EvictionCandidate>,
    /** Bytes the plan would free. */
    val freedBytes: Long,
    /** Bytes that had to be freed for usage to fit the budget. */
    val targetBytes: Long,
    /** Total usage once the plan has been applied. */
    val resultingTotalBytes: Long,
    val warning: CacheWarning?,
) {
    public val isEmpty: Boolean get() = victims.isEmpty()

    /** True when the plan does not get usage within the budget, which only pins can cause. */
    public val budgetStillExceeded: Boolean get() = freedBytes < targetBytes

    public companion object {
        public fun nothingToDo(totalBytes: Long, warning: CacheWarning? = null): EvictionPlan =
            EvictionPlan(
                victims = emptyList(),
                freedBytes = 0L,
                targetBytes = 0L,
                resultingTotalBytes = totalBytes,
                warning = warning,
            )
    }
}

/** Why an eviction pass could not simply satisfy the budget. */
public enum class CacheWarning {
    /**
     * Pinned content alone exceeds the budget. Pins are exempt from eviction, so the budget cannot
     * be met by evicting; the user must be told rather than quietly losing downloads they chose.
     */
    PINS_EXCEED_BUDGET,

    /**
     * Everything evictable has been evicted and usage still exceeds the budget. In practice this
     * means the same thing as [PINS_EXCEED_BUDGET] and is reported when pinned bytes are at or
     * under the budget but the unpinned tier could not close the gap - for instance because a
     * download in flight is larger than the whole remaining allowance.
     */
    BUDGET_STILL_EXCEEDED,
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
