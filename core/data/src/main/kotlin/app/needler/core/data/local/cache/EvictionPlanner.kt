package app.needler.core.data.local.cache

/**
 * Decides which cached tracks to evict for a given budget. Pure, deterministic, no Room, no Android.
 *
 * ## The policy, and where it comes from
 *
 * REQUIREMENTS.md, "Budget and storage":
 *
 *  * "The limit is user-set, defaulting to 4 GB." - the limit is on on-device audio.
 *  * "Pinned content is exempt from the limit and from LRU eviction. If pins alone exceed the
 *    budget, warn rather than silently evicting what the user asked to keep."
 *  * "Eviction scans unpinned rows by `last_played` ascending until usage fits the budget."
 *
 * Read together, the budget caps *total* on-device audio, and the only tier eviction may touch is
 * the unpinned one. So:
 *
 * ```
 * bytesToFree = max(0, pinnedBytes + unpinnedBytes + incomingBytes - budget)
 * ```
 *
 * and the plan takes unpinned rows in LRU order until it has freed that much. Two consequences are
 * worth stating out loud, because they are the cases the requirements gloss over:
 *
 *  1. **When pins alone exceed the budget, the plan evicts the whole unpinned tier** and reports
 *     [CacheWarning.PINS_EXCEED_BUDGET]. It cannot do better without touching pins, which is
 *     forbidden. Dropping the played-track cache is recoverable - those bytes are re-fetchable and
 *     were never explicitly requested - whereas deleting a pin is the one thing the user said not
 *     to do. The warning is what makes the situation visible rather than silent.
 *  2. **Pinned bytes are never counted as evictable**, so a plan can be short of its target. The
 *     caller must not loop: a second pass would find the same candidates and free nothing.
 *
 * ## Determinism
 *
 * The planner sorts its own input rather than trusting the query's ORDER BY, and the sort is total:
 * last played, then downloaded-at, then the canonical track key. Two runs over the same cache
 * therefore choose exactly the same victims, which is what makes this testable and what stops an
 * eviction pass from thrashing a different arbitrary tail on every call.
 */
public object EvictionPlanner {

    /**
     * Plans an eviction pass.
     *
     * @param usage current usage, split by tier.
     * @param budgetBytes the user's limit, or [CacheBudget.UNLIMITED].
     * @param candidates unpinned rows only. Order is irrelevant; this function sorts them.
     * @param incomingBytes bytes about to be written - a download that is about to start. Counted
     *   against the budget so room is made *before* the write rather than after it has overshot.
     */
    public fun plan(
        usage: CacheUsage,
        budgetBytes: Long,
        candidates: List<EvictionCandidate>,
        incomingBytes: Long = 0L,
    ): EvictionPlan {
        if (CacheBudget.isUnlimited(budgetBytes)) {
            return EvictionPlan.nothingToDo(usage.totalBytes)
        }

        val projectedTotal: Long = usage.totalBytes + maxOf(0L, incomingBytes)
        val bytesToFree: Long = projectedTotal - budgetBytes

        val pinsExceedBudget: Boolean = usage.pinnedBytes > budgetBytes
        if (bytesToFree <= 0L) {
            // Within budget. A pin-heavy cache can still be worth warning about, but there is
            // nothing to evict, so the plan is empty.
            return EvictionPlan.nothingToDo(
                totalBytes = usage.totalBytes,
                warning = if (pinsExceedBudget) CacheWarning.PINS_EXCEED_BUDGET else null,
            )
        }

        val ordered: List<EvictionCandidate> = candidates.sortedWith(LRU_ORDER)
        val victims: MutableList<EvictionCandidate> = ArrayList()
        var freed: Long = 0L
        for (candidate in ordered) {
            if (freed >= bytesToFree) break
            victims.add(candidate)
            freed += candidate.sizeBytes
        }

        val warning: CacheWarning? = when {
            pinsExceedBudget -> CacheWarning.PINS_EXCEED_BUDGET
            freed < bytesToFree -> CacheWarning.BUDGET_STILL_EXCEEDED
            else -> null
        }

        return EvictionPlan(
            victims = victims,
            freedBytes = freed,
            targetBytes = bytesToFree,
            resultingTotalBytes = usage.totalBytes - freed,
            warning = warning,
        )
    }

    /**
     * Plans the eviction needed before writing [incomingBytes] of new audio.
     *
     * Used by the downloader and by the streaming cache: make room first, so the budget is a limit
     * rather than a line the cache crosses and then retreats behind.
     */
    public fun planForIncoming(
        usage: CacheUsage,
        budgetBytes: Long,
        candidates: List<EvictionCandidate>,
        incomingBytes: Long,
    ): EvictionPlan = plan(
        usage = usage,
        budgetBytes = budgetBytes,
        candidates = candidates,
        incomingBytes = incomingBytes,
    )

    /**
     * True when [incomingBytes] can be written without exceeding the budget once [plan] has been
     * applied. False means even evicting the whole unpinned tier leaves no room, which happens only
     * when pins fill the budget.
     */
    public fun fitsAfterEviction(
        usage: CacheUsage,
        budgetBytes: Long,
        incomingBytes: Long,
    ): Boolean {
        if (CacheBudget.isUnlimited(budgetBytes)) return true
        // Everything unpinned is evictable; pinned bytes are not.
        return usage.pinnedBytes + maxOf(0L, incomingBytes) <= budgetBytes
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
