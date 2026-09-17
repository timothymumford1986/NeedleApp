package app.needler.core.data.local.cache

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.projection.CacheUsageRow
import app.needler.core.data.local.projection.EvictionCandidateRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Cache accounting: what is on the device, how it splits between the two tiers, and what to evict
 * for a given budget.
 *
 * This is the only class that reads the cache index for accounting purposes, so the budget rules
 * live in exactly one place. It owns no policy of its own - the policy is
 * [EvictionPlanner], which is pure and unit-tested - and it owns no filesystem access either: the
 * caller supplies a deleter. That keeps this class testable and stops two layers from both
 * believing they are responsible for unlinking files.
 *
 * ## Order of operations when evicting
 *
 * Bytes first, row second. If the process dies between the two, the result is a row pointing at a
 * missing file, which the next play attempt notices and re-downloads. The other order leaves a file
 * nothing knows about, which only an uninstall reclaims - and a leak of gigabytes is worse than one
 * re-download.
 */
public class CacheIndex(
    private val audioCacheDao: AudioCacheDao,
) {

    /** Usage split by tier, as the Storage section of screen 12 shows it. */
    public fun observeUsage(): Flow<CacheUsage> =
        audioCacheDao.observeUsage().map { it.toUsage() }

    /** Usage measured against [budgetBytes], including the pins-exceed-budget warning flag. */
    public fun observeStatus(budgetBytes: Long): Flow<CacheStatus> =
        audioCacheDao.observeUsage().map { CacheStatus(usage = it.toUsage(), budgetBytes = budgetBytes) }

    public suspend fun usage(): CacheUsage = audioCacheDao.getUsage().toUsage()

    public suspend fun status(budgetBytes: Long): CacheStatus =
        CacheStatus(usage = usage(), budgetBytes = budgetBytes)

    /**
     * Computes what would have to be evicted for usage plus [incomingBytes] to fit [budgetBytes].
     *
     * Nothing is deleted. The returned plan carries the warning that distinguishes "evicted as
     * planned" from "pins alone exceed the budget", which the caller must surface rather than
     * swallow.
     *
     * The candidate query is bounded by [scanLimit] so a cache of tens of thousands of tracks does
     * not load every row to free a few hundred megabytes. The limit is deliberately generous: an
     * under-sized scan would silently plan a short eviction, which looks exactly like a pin problem.
     */
    public suspend fun planEviction(
        budgetBytes: Long,
        incomingBytes: Long = 0L,
        scanLimit: Int = DEFAULT_SCAN_LIMIT,
    ): EvictionPlan {
        val usage: CacheUsage = usage()
        if (CacheBudget.isUnlimited(budgetBytes)) return EvictionPlan.nothingToDo(usage.totalBytes)
        if (usage.totalBytes + maxOf(0L, incomingBytes) <= budgetBytes) {
            return EvictionPlanner.plan(
                usage = usage,
                budgetBytes = budgetBytes,
                candidates = emptyList(),
                incomingBytes = incomingBytes,
            )
        }
        val candidates: List<EvictionCandidate> =
            audioCacheDao.getEvictionCandidates(scanLimit).map { it.toCandidate() }
        return EvictionPlanner.plan(
            usage = usage,
            budgetBytes = budgetBytes,
            candidates = candidates,
            incomingBytes = incomingBytes,
        )
    }

    /**
     * Applies a plan: deletes each victim's bytes through [deleteFile], then drops the rows whose
     * files are gone.
     *
     * @param deleteFile deletes one path and returns true when the file is no longer on disk -
     *   which includes the case where it was already missing. Returning false keeps the row, so the
     *   bytes stay accounted for and the next pass tries again.
     */
    public suspend fun applyEviction(
        plan: EvictionPlan,
        deleteFile: (String) -> Boolean,
    ): EvictionResult {
        if (plan.isEmpty) return EvictionResult.Empty

        val evicted: MutableList<TrackKeyDb> = ArrayList(plan.victims.size)
        val failed: MutableList<String> = ArrayList()
        var freed: Long = 0L

        for (victim in plan.victims) {
            val deleted: Boolean = try {
                deleteFile(victim.filePath)
            } catch (error: SecurityException) {
                // Nothing here may log a path or an exception message that could carry one at a
                // level that leaves the device; the caller's diagnostics log is the place for that.
                false
            }
            if (deleted) {
                evicted.add(victim.key)
                freed += victim.sizeBytes
            } else {
                failed.add(victim.filePath)
            }
        }

        // Rows are deleted in batches: SQLite's default bound-parameter limit is 999, and an
        // eviction pass over a full cache can easily exceed that.
        evicted.chunked(DELETE_BATCH_SIZE).forEach { batch ->
            audioCacheDao.deleteByCanonicalKeys(batch.map { it.canonical })
        }

        return EvictionResult(evictedKeys = evicted, freedBytes = freed, failedPaths = failed)
    }

    /**
     * Plans and applies in one call: the form the downloader and the streaming cache want before
     * writing new bytes.
     */
    public suspend fun evictToFit(
        budgetBytes: Long,
        incomingBytes: Long = 0L,
        deleteFile: (String) -> Boolean,
    ): Pair<EvictionPlan, EvictionResult> {
        val plan: EvictionPlan = planEviction(budgetBytes = budgetBytes, incomingBytes = incomingBytes)
        val result: EvictionResult = applyEviction(plan = plan, deleteFile = deleteFile)
        return plan to result
    }

    /**
     * True when [incomingBytes] can be stored without exceeding [budgetBytes], assuming the whole
     * unpinned tier may be evicted. False means only unpinning something would help, so the caller
     * must warn instead of downloading.
     */
    public suspend fun canStore(budgetBytes: Long, incomingBytes: Long): Boolean =
        EvictionPlanner.fitsAfterEviction(
            usage = usage(),
            budgetBytes = budgetBytes,
            incomingBytes = incomingBytes,
        )

    /**
     * Removes every cached audio row, pinned included - "Remove all from device".
     *
     * The metadata mirror is untouched, by requirement: clearing it would leave the app unable to
     * browse. Files are deleted through [deleteFile] before their rows, as in [applyEviction].
     */
    public suspend fun removeAll(deleteFile: (String) -> Boolean): EvictionResult {
        val rows: List<EvictionCandidate> = audioCacheDao.getAllRowsForRemoval().map { it.toCandidate() }
        val plan = EvictionPlan(
            victims = rows,
            freedBytes = rows.sumOf { it.sizeBytes },
            targetBytes = rows.sumOf { it.sizeBytes },
            resultingTotalBytes = 0L,
            warning = null,
        )
        return applyEviction(plan = plan, deleteFile = deleteFile)
    }

    /** Records a play, which is what moves a track to the back of the eviction queue. */
    public suspend fun recordPlay(key: TrackKeyDb, playedAt: Long) {
        audioCacheDao.markPlayed(
            releaseGroupMbid = key.releaseGroupMbid,
            discNo = key.discNo,
            trackNo = key.trackNo,
            playedAt = playedAt,
        )
    }

    /**
     * Moves an album's cached audio between tiers. Pinning exempts it from the budget and from
     * eviction; unpinning hands it back to the LRU without deleting anything.
     */
    public suspend fun setAlbumPinned(releaseGroupMbid: String, pinned: Boolean) {
        audioCacheDao.setAlbumPinned(releaseGroupMbid = releaseGroupMbid, pinned = pinned)
    }

    public companion object {
        /**
         * How many candidate rows one eviction scan loads. At a few hundred kilobytes per row of
         * audio this is far more than any single pass needs, and the query is index-covered.
         */
        public const val DEFAULT_SCAN_LIMIT: Int = 5_000

        /** Kept under SQLite's 999-parameter limit with room to spare. */
        private const val DELETE_BATCH_SIZE: Int = 400
    }
}

private fun CacheUsageRow.toUsage(): CacheUsage = CacheUsage(
    pinnedBytes = pinnedBytes,
    unpinnedBytes = unpinnedBytes,
    pinnedTrackCount = pinnedTracks,
    unpinnedTrackCount = unpinnedTracks,
)

private fun EvictionCandidateRow.toCandidate(): EvictionCandidate = EvictionCandidate(
    key = key,
    filePath = filePath,
    sizeBytes = sizeBytes,
    lastPlayedAt = lastPlayedAt,
    downloadedAt = downloadedAt,
)
