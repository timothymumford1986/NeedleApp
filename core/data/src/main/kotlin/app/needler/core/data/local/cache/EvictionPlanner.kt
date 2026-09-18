package app.needler.core.data.local.cache

/**
 * Decides which cached tracks to evict to keep the device above its free-space floor. Pure,
 * deterministic, no Room, no Android, no `StatFs`: free space arrives as a parameter.
 *
 * ## The policy, and why it looks like this
 *
 * There is no user-facing storage limit. The two offline tiers are bounded by different things:
 *
 *  * **Downloaded (pinned)** albums have **no limit at all**. The user chose them, so nothing here
 *    ever evicts one - not when the device is nearly full, not when a download is in flight, never.
 *    Pinned rows are not even offered as candidates, so no plan can name one as a victim.
 *  * **Cached while listening** fills up silently as a side effect of streaming, so it cannot be
 *    unbounded or the app quietly eats the device. It is bounded by **device free space**: keep the
 *    caller's `floorBytes` free, which is at least [FreeSpaceFloor.MINIMUM_BYTES] and scales with
 *    the volume - see [FreeSpaceFloor.forVolume]. The number arrives as a parameter precisely so
 *    that this object never has to know which device it is running on.
 *
 * Bounding the silent tier by free space rather than by a number the user picks is the whole change.
 * A budget asked the user to predict how much music they wanted kept, and then evicted music on a
 * device with 200 GB spare when they guessed low. Free space measures the thing that actually
 * matters, needs no preference, and self-corrects when the device's contents change underneath it.
 *
 * ## The arithmetic
 *
 * Free space is the device's current figure, so bytes already cached are not free and evicting a
 * byte returns a byte:
 *
 * ```
 * housekeepingTarget = max(0, floor - freeSpace)          bring the device back to the floor
 * incomingTarget     = incoming - (freeSpace + freed - floor)   room for what is about to be written
 * ```
 *
 * The two stages behave differently on purpose:
 *
 *  1. **Housekeeping is best effort.** If the device is below the floor, every byte freed is real
 *     room recovered, so the pass frees what it can and reports [CacheWarning.DEVICE_LOW_ON_SPACE]
 *     if that was not enough. It never reaches into the downloaded tier to close the gap.
 *  2. **Making room for an incoming write is all or nothing.** If the floor cannot be met even after
 *     the whole evictable tier is gone, the bytes are **skipped**, not forced in, and nothing extra
 *     is evicted for them: evicting further would cost the user recently-played music *and* still
 *     leave the write unable to fit. The track streams as normal; it is simply not retained. See
 *     [EvictionPlan.skipsIncoming].
 *
 * A failed free-space reading ([FreeSpaceFloor.UNKNOWN]) suspends the policy entirely rather than
 * guessing in either direction. See [FreeSpaceFloor.UNKNOWN] for why.
 *
 * ## Determinism
 *
 * The planner sorts its own input rather than trusting the query's ORDER BY, and the sort is total:
 * last played, then downloaded-at, then the canonical track key. Two runs over the same cache
 * therefore choose exactly the same victims however the rows arrived, which is what makes this
 * testable and what stops a pass from thrashing a different arbitrary tail on every call.
 */
public object EvictionPlanner {

    /**
     * Plans an eviction pass.
     *
     * @param usage current usage, split by tier. Only the unpinned half is ever evictable.
     * @param freeSpaceBytes free space on the volume the audio lives on, or [FreeSpaceFloor.UNKNOWN].
     * @param candidates unpinned rows only. Order is irrelevant; this function sorts them. A list
     *   truncated by the caller's scan limit can only make the plan more conservative: it may skip an
     *   incoming write it could in principle have made room for, never evict something it should not.
     * @param incomingBytes bytes about to be written - a download or a stream being retained.
     *   Counted so room is made *before* the write rather than after the device has already dipped
     *   below the floor.
     * @param floorBytes free space to preserve. A parameter because the floor depends on the device:
     *   production passes [FreeSpaceFloor.forVolume] of the audio volume, which is
     *   [FreeSpaceFloor.MINIMUM_BYTES] on a small one and a percentage of a large one. The default is
     *   the minimum, which is also what tests working in small numbers override.
     */
    public fun plan(
        usage: CacheUsage,
        freeSpaceBytes: Long,
        candidates: List<EvictionCandidate>,
        incomingBytes: Long = 0L,
        floorBytes: Long = FreeSpaceFloor.MINIMUM_BYTES,
    ): EvictionPlan {
        if (FreeSpaceFloor.isUnknown(freeSpaceBytes)) {
            // Nothing is known about the device, so nothing is evicted and nothing is skipped.
            return EvictionPlan.nothingToDo(
                totalBytes = usage.totalBytes,
                freeBytes = freeSpaceBytes,
                floorBytes = floorBytes,
            )
        }

        val incoming: Long = maxOf(0L, incomingBytes)
        val fullTarget: Long = floorBytes + incoming - freeSpaceBytes
        if (fullTarget <= 0L) {
            // Above the floor with room for the write: nothing to do, and nothing to warn about.
            return EvictionPlan.nothingToDo(
                totalBytes = usage.totalBytes,
                freeBytes = freeSpaceBytes,
                floorBytes = floorBytes,
            )
        }

        val ordered: List<EvictionCandidate> = candidates.sortedWith(LRU_ORDER)
        val victims: MutableList<EvictionCandidate> = ArrayList()
        var freed: Long = 0L
        var next: Int = 0

        // Stage 1: bring the device back to the floor, best effort. Every byte freed here is real
        // room recovered on a device that is genuinely short of it, so a partial result is still
        // worth having - unlike stage 2, which is worth nothing unless it succeeds completely.
        val housekeepingTarget: Long = maxOf(0L, floorBytes - freeSpaceBytes)
        while (freed < housekeepingTarget && next < ordered.size) {
            val candidate: EvictionCandidate = ordered[next]
            victims.add(candidate)
            freed += candidate.sizeBytes
            next += 1
        }

        // Stage 2: make room for the incoming write, all or nothing.
        var skipsIncoming = false
        if (incoming > 0L) {
            val headroom: Long = freeSpaceBytes + freed - floorBytes
            val stillNeeded: Long = incoming - headroom
            if (stillNeeded > 0L) {
                val remaining: Long = (next until ordered.size).sumOf { ordered[it].sizeBytes }
                if (remaining < stillNeeded) {
                    // The floor cannot be met even by clearing the rest of the tier. Skip the write
                    // instead of evicting for a write that still would not fit: the user would lose
                    // recently-played music and gain nothing. Stage 1's victims are kept, because
                    // they were freed to fix the device, not to make room for these bytes.
                    skipsIncoming = true
                } else {
                    var extra: Long = 0L
                    while (extra < stillNeeded && next < ordered.size) {
                        val candidate: EvictionCandidate = ordered[next]
                        victims.add(candidate)
                        extra += candidate.sizeBytes
                        next += 1
                    }
                    freed += extra
                }
            }
        }

        val resultingFree: Long = freeSpaceBytes + freed
        val warning: CacheWarning? = when {
            skipsIncoming -> CacheWarning.INCOMING_NOT_CACHED
            resultingFree < floorBytes -> CacheWarning.DEVICE_LOW_ON_SPACE
            else -> null
        }

        return EvictionPlan(
            victims = victims,
            freedBytes = freed,
            targetBytes = fullTarget,
            resultingTotalBytes = usage.totalBytes - freed,
            resultingFreeBytes = resultingFree,
            skipsIncoming = skipsIncoming,
            floorBytes = floorBytes,
            warning = warning,
        )
    }

    /**
     * Plans the eviction needed before writing [incomingBytes] of new audio.
     *
     * Used by the downloader and by the streaming cache: make room first, so the floor is a floor
     * rather than a line the device drops below and then climbs back over.
     */
    public fun planForIncoming(
        usage: CacheUsage,
        freeSpaceBytes: Long,
        candidates: List<EvictionCandidate>,
        incomingBytes: Long,
        floorBytes: Long = FreeSpaceFloor.MINIMUM_BYTES,
    ): EvictionPlan = plan(
        usage = usage,
        freeSpaceBytes = freeSpaceBytes,
        candidates = candidates,
        incomingBytes = incomingBytes,
        floorBytes = floorBytes,
    )

    /**
     * True when [incomingBytes] can be retained without taking the device below the floor, assuming
     * the whole cached tier may be evicted for it.
     *
     * False means only the user deleting something would help - downloads, or files belonging to
     * other apps - so the caller skips the write rather than evicting. An unknown free-space reading
     * answers true: the policy is suspended, not inverted.
     */
    public fun fitsAfterEviction(
        usage: CacheUsage,
        freeSpaceBytes: Long,
        incomingBytes: Long,
        floorBytes: Long = FreeSpaceFloor.MINIMUM_BYTES,
    ): Boolean {
        if (FreeSpaceFloor.isUnknown(freeSpaceBytes)) return true
        // Everything unpinned is evictable; pinned bytes are not, at any level of disk pressure.
        return freeSpaceBytes + usage.unpinnedBytes - maxOf(0L, incomingBytes) >= floorBytes
    }

    /**
     * Least-recently-played first, with a total order so the choice is reproducible.
     *
     * `lastPlayedAt == 0` means never played and therefore sorts first: bytes nobody has listened to
     * are the cheapest thing in the cache to lose. `downloadedAt` breaks ties among never-played
     * rows so the oldest download goes first, and the canonical key breaks the remaining ties.
     */
    private val LRU_ORDER: Comparator<EvictionCandidate> = Comparator { left, right ->
        val byPlayed: Int = left.lastPlayedAt.compareTo(right.lastPlayedAt)
        if (byPlayed != 0) return@Comparator byPlayed
        val byDownloaded: Int = (left.downloadedAt ?: 0L).compareTo(right.downloadedAt ?: 0L)
        if (byDownloaded != 0) return@Comparator byDownloaded
        left.key.canonical.compareTo(right.key.canonical)
    }
}
