package app.needler.core.data.local.cache

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.projection.CacheUsageRow
import app.needler.core.data.local.projection.EvictionCandidateRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Cache accounting: what is on the device, how it splits between the two tiers, and what to evict to
 * keep the device above its free-space floor.
 *
 * This is the only class that reads the cache index for accounting purposes, so the storage rules
 * live in exactly one place. It owns no policy of its own - the policy is [EvictionPlanner], which is
 * pure and unit-tested - and it owns no filesystem access either: free space arrives through
 * [DeviceFreeSpace] and file deletion through a deleter the caller supplies. That keeps this class
 * testable and stops two layers from both believing they are responsible for unlinking files.
 *
 * ## The two tiers
 *
 * Pinned rows are the **downloaded** tier and are never evicted here, for any reason. Every eviction
 * query filters them out, so a plan cannot name one even by mistake; they leave the device only when
 * the user unpins an album or asks for [removeAll].
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
    private val deviceFreeSpace: DeviceFreeSpace,
) {

    /**
     * Free space this device must keep, asked of [deviceFreeSpace] rather than held as a constant.
     *
     * The floor scales with the volume, so it is a property of the device and not of this class.
     * Tests set it by substituting a [DeviceFreeSpace]; there is no override parameter, because two
     * ways of saying what the floor is would be two things to keep in step.
     */
    private fun floorBytes(): Long = deviceFreeSpace.floorBytes()

    /** Usage split by tier, as the Storage section of screen 12 shows it. */
    public fun observeUsage(): Flow<CacheUsage> =
        audioCacheDao.observeUsage().map { it.toUsage() }

    /**
     * Usage together with the device's free space, which is the only thing that bounds the cache.
     *
     * Free space is re-read on every emission rather than sampled once: it moves as other apps write,
     * and a Storage screen showing a stale figure would invite the user to clear things that are not
     * the problem.
     */
    public fun observeStatus(): Flow<CacheStatus> =
        audioCacheDao.observeUsage().map { row ->
            CacheStatus(
                usage = row.toUsage(),
                deviceFreeBytes = deviceFreeSpace.freeBytes(),
                floorBytes = floorBytes(),
            )
        }

    public suspend fun usage(): CacheUsage = audioCacheDao.getUsage().toUsage()

    public suspend fun status(): CacheStatus = CacheStatus(
        usage = usage(),
        deviceFreeBytes = deviceFreeSpace.freeBytes(),
        floorBytes = floorBytes(),
    )

    /**
     * Computes what would have to be evicted for the device to keep [floorBytes] free with
     * [incomingBytes] written.
     *
     * Nothing is deleted. The returned plan carries the two outcomes the caller must not swallow:
     * [EvictionPlan.skipsIncoming], meaning the bytes must not be written at all, and
     * [EvictionPlan.deviceStillLowOnSpace], meaning the user has to be told rather than the app
     * quietly deleting downloads.
     *
     * The candidate query is bounded by [scanLimit] so a cache of tens of thousands of tracks does
     * not load every row to free a few hundred megabytes. The limit is deliberately generous: a
     * truncated scan can only make the plan more conservative, but a badly under-sized one would skip
     * writes it could have made room for.
     */
    public suspend fun planEviction(
        incomingBytes: Long = 0L,
        scanLimit: Int = DEFAULT_SCAN_LIMIT,
    ): EvictionPlan {
        val usage: CacheUsage = usage()
        val freeBytes: Long = deviceFreeSpace.freeBytes()
        // Read once, so the test below and the plan cannot be made against different floors.
        val floor: Long = floorBytes()

        // Skip the candidate query entirely in the common case: the device has room and the write
        // fits, so no row would have been chosen from it anyway.
        val needsPlanning: Boolean = !FreeSpaceFloor.isUnknown(freeBytes) &&
            floor + maxOf(0L, incomingBytes) - freeBytes > 0L
        val candidates: List<EvictionCandidate> = if (needsPlanning) {
            audioCacheDao.getEvictionCandidates(scanLimit).map { it.toCandidate() }
        } else {
            emptyList()
        }

        return EvictionPlanner.plan(
            usage = usage,
            freeSpaceBytes = freeBytes,
            candidates = candidates,
            incomingBytes = incomingBytes,
            floorBytes = floor,
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
     *
     * The caller must honour [EvictionPlan.skipsIncoming] on the returned plan and abandon the write
     * - the result of this call says what was freed, not that there is now room.
     */
    public suspend fun evictToFit(
        incomingBytes: Long = 0L,
        deleteFile: (String) -> Boolean,
    ): Pair<EvictionPlan, EvictionResult> {
        val plan: EvictionPlan = planEviction(incomingBytes = incomingBytes)
        val result: EvictionResult = applyEviction(plan = plan, deleteFile = deleteFile)
        return plan to result
    }

    /**
     * True when [incomingBytes] can be retained without taking the device below the floor, assuming
     * the whole cached tier may be evicted for them.
     *
     * False means the caller must stream without retaining: only the user removing downloads, or
     * other apps' files going away, would help.
     */
    public suspend fun canStore(incomingBytes: Long): Boolean =
        EvictionPlanner.fitsAfterEviction(
            usage = usage(),
            freeSpaceBytes = deviceFreeSpace.freeBytes(),
            incomingBytes = incomingBytes,
            floorBytes = floorBytes(),
        )

    /**
     * Clears the cached-while-listening tier and leaves every download in place: "Clear cached
     * music".
     *
     * The safe half of the clearing workflow. These bytes are re-fetchable and were never explicitly
     * requested, so losing them costs the user a re-stream and nothing else - which is why this is a
     * separate action from [removeAll] rather than a confirmation step inside it.
     */
    public suspend fun clearCached(deleteFile: (String) -> Boolean): EvictionResult {
        val rows: List<EvictionCandidate> =
            audioCacheDao.getAllUnpinnedCandidates().map { it.toCandidate() }
        return applyEviction(plan = planForRows(rows), deleteFile = deleteFile)
    }

    /**
     * Removes every cached audio row, downloads included - "Remove all from device".
     *
     * The only path on which pinned bytes are deleted, and it exists because the user asked for it in
     * so many words. The metadata mirror is untouched, by requirement: clearing it would leave the
     * app unable to browse. Files are deleted through [deleteFile] before their rows, as in
     * [applyEviction].
     */
    public suspend fun removeAll(deleteFile: (String) -> Boolean): EvictionResult {
        val rows: List<EvictionCandidate> = audioCacheDao.getAllRowsForRemoval().map { it.toCandidate() }
        return applyEviction(plan = planForRows(rows), deleteFile = deleteFile)
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
     * Moves an album's cached audio between tiers: what pinning does to bytes that were already
     * cached from streaming, which is how a pin of an album you have been playing costs no download.
     *
     * This is **not** how a download is removed. "Remove from device" deletes the rows and the files
     * through `NeedlerDatabase.removeDownloadedAlbum`, because demoting to the cached tier frees
     * nothing now, and freeing space now is the entire reason the user pressed it.
     */
    public suspend fun setAlbumPinned(releaseGroupMbid: String, pinned: Boolean) {
        audioCacheDao.setAlbumPinned(releaseGroupMbid = releaseGroupMbid, pinned = pinned)
    }

    /**
     * A plan that deletes exactly [rows]: the shape a user-requested clear takes.
     *
     * It reuses [EvictionPlan] so both clears go through the same delete-bytes-then-rows path, and it
     * carries no warning - a clear the user asked for cannot fail to meet a target it never had.
     */
    private fun planForRows(rows: List<EvictionCandidate>): EvictionPlan {
        val bytes: Long = rows.sumOf { it.sizeBytes }
        return EvictionPlan(
            victims = rows,
            freedBytes = bytes,
            targetBytes = bytes,
            resultingTotalBytes = 0L,
            resultingFreeBytes = deviceFreeSpace.freeBytes() + bytes,
            skipsIncoming = false,
            floorBytes = floorBytes(),
            warning = null,
        )
    }

    public companion object {
        /**
         * How many candidate rows one eviction scan loads. At a few megabytes per row of audio this
         * is far more than any single pass needs, and the query is index-covered.
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
