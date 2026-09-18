package app.needler.core.data.local.cache

import app.needler.core.domain.model.StorageUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache accounting: the numbers behind the Storage screen, and the free-space arithmetic the
 * eviction pass depends on.
 *
 * The screen shows what is on the device split into Downloaded and Cached while listening, so the
 * user can see what a cleanup would actually free, and it shows how much room the device has left.
 * There is no limit to compare against: these tests fix that meaning too, because the absence of a
 * budget is a decision and not an oversight.
 */
public class CacheAccountingTest {

    private val gigabyte: Long = 1_073_741_824L

    @Test
    public fun `total usage is both tiers together`() {
        val usage = CacheUsage(
            pinnedBytes = 3 * gigabyte,
            unpinnedBytes = 1 * gigabyte,
            pinnedTrackCount = 120,
            unpinnedTrackCount = 40,
        )

        assertEquals(4 * gigabyte, usage.totalBytes)
        assertEquals(160, usage.trackCount)
    }

    @Test
    public fun `an empty cache reports zero of everything`() {
        assertEquals(0L, CacheUsage.Empty.totalBytes)
        assertEquals(0, CacheUsage.Empty.trackCount)
    }

    // ---------------------------------------------------------------- the floor

    @Test
    public fun `the floor is two gigabytes of device free space and is shared with the domain`() {
        // One constant, so the layer that plans evictions and the layer that reports usage to the
        // user cannot drift apart on what "low on space" means.
        assertEquals(2 * gigabyte, FreeSpaceFloor.MINIMUM_BYTES)
        assertEquals(StorageUsage.MINIMUM_FREE_SPACE_FLOOR_BYTES, FreeSpaceFloor.MINIMUM_BYTES)
    }

    @Test
    public fun `only a negative reading means unknown, because zero free space is real`() {
        assertTrue(FreeSpaceFloor.isUnknown(FreeSpaceFloor.UNKNOWN))
        assertTrue(FreeSpaceFloor.isUnknown(-100L))
        assertFalse(FreeSpaceFloor.isUnknown(0L))
        assertFalse(FreeSpaceFloor.isUnknown(1L))
    }

    // ---------------------------------------------------------------- status

    @Test
    public fun `a device above the floor is not low on space, however much Needler is holding`() {
        // 80 GB of music is fine on a device with room for it. That is the entire point of dropping
        // the budget: a limit would have evicted here for no reason at all.
        val status = CacheStatus(
            usage = CacheUsage(
                pinnedBytes = 40 * gigabyte,
                unpinnedBytes = 40 * gigabyte,
                pinnedTrackCount = 3_000,
                unpinnedTrackCount = 3_000,
            ),
            deviceFreeBytes = 60 * gigabyte,
        )

        assertFalse(status.deviceLowOnSpace)
        assertEquals(0L, status.freeSpaceShortfallBytes)
        assertEquals(58 * gigabyte, status.cacheHeadroomBytes)
    }

    @Test
    public fun `a device below the floor reports the shortfall and no headroom`() {
        val status = CacheStatus(
            usage = CacheUsage(
                pinnedBytes = 6 * gigabyte,
                unpinnedBytes = 1 * gigabyte,
                pinnedTrackCount = 200,
                unpinnedTrackCount = 30,
            ),
            deviceFreeBytes = gigabyte / 2,
        )

        assertTrue(status.deviceLowOnSpace)
        assertEquals(gigabyte + gigabyte / 2, status.freeSpaceShortfallBytes)
        assertEquals(0L, status.cacheHeadroomBytes)
        // Only the cached tier could ever be given back; the downloads are the user's to remove.
        assertEquals(1 * gigabyte, status.reclaimableBytes)
    }

    @Test
    public fun `free space exactly on the floor is not low, and offers no headroom`() {
        val status = CacheStatus(
            usage = CacheUsage.Empty,
            deviceFreeBytes = FreeSpaceFloor.MINIMUM_BYTES,
        )

        assertFalse(status.deviceLowOnSpace)
        assertEquals(0L, status.cacheHeadroomBytes)
    }

    @Test
    public fun `an unreadable free-space figure is never reported as low on space`() {
        val status = CacheStatus(
            usage = CacheUsage(
                pinnedBytes = 3 * gigabyte,
                unpinnedBytes = 0,
                pinnedTrackCount = 100,
                unpinnedTrackCount = 0,
            ),
            deviceFreeBytes = FreeSpaceFloor.UNKNOWN,
        )

        assertTrue(status.freeSpaceUnknown)
        assertFalse(status.deviceLowOnSpace)
        assertEquals(0L, status.freeSpaceShortfallBytes)
        // The policy is suspended, not inverted: nothing is evicted and nothing is blocked.
        assertEquals(Long.MAX_VALUE, status.cacheHeadroomBytes)
    }

    @Test
    public fun `pins filling the device are a warning and never an eviction`() {
        val status = CacheStatus(
            usage = CacheUsage(
                pinnedBytes = 60 * gigabyte,
                unpinnedBytes = 0,
                pinnedTrackCount = 4_000,
                unpinnedTrackCount = 0,
            ),
            deviceFreeBytes = gigabyte,
        )

        assertTrue(status.deviceLowOnSpace)
        assertEquals(0L, status.reclaimableBytes)

        // The warning does not become an eviction: the planner has nothing it may touch, and the
        // downloaded tier is off limits at any level of disk pressure.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = status.usage,
            freeSpaceBytes = status.deviceFreeBytes,
            candidates = emptyList(),
        )
        assertTrue(plan.isEmpty)
        assertEquals(CacheWarning.DEVICE_LOW_ON_SPACE, plan.warning)
        assertEquals(60 * gigabyte, plan.resultingTotalBytes)
    }

    // ---------------------------------------------------------------- plans and results

    @Test
    public fun `an eviction plan reports what a cleanup would free`() {
        val plan = EvictionPlan(
            victims = emptyList(),
            freedBytes = 0,
            targetBytes = 0,
            resultingTotalBytes = 2 * gigabyte,
            resultingFreeBytes = 8 * gigabyte,
            skipsIncoming = false,
            floorBytes = FreeSpaceFloor.MINIMUM_BYTES,
            warning = null,
        )

        assertTrue(plan.isEmpty)
        assertFalse(plan.targetUnmet)
        assertFalse(plan.deviceStillLowOnSpace)
        assertEquals(2 * gigabyte, plan.resultingTotalBytes)
    }

    @Test
    public fun `nothingToDo carries the free-space figure so the plan can still be interrogated`() {
        val plan: EvictionPlan = EvictionPlan.nothingToDo(
            totalBytes = 5 * gigabyte,
            freeBytes = 10 * gigabyte,
        )

        assertTrue(plan.isEmpty)
        assertFalse(plan.skipsIncoming)
        assertFalse(plan.deviceStillLowOnSpace)
        assertEquals(10 * gigabyte, plan.resultingFreeBytes)
    }

    @Test
    public fun `an eviction result starts empty and accumulates nothing by default`() {
        assertEquals(0L, EvictionResult.Empty.freedBytes)
        assertTrue(EvictionResult.Empty.evictedKeys.isEmpty())
        assertTrue(EvictionResult.Empty.failedPaths.isEmpty())
    }

    // ---------------------------------------------------------------- what the screen shows

    @Test
    public fun `the domain usage splits downloaded from cached and adds the free-space figure`() {
        val usage = StorageUsage(
            downloadedBytes = 3 * gigabyte,
            cachedBytes = 1 * gigabyte,
            artworkBytes = 120 * 1_048_576L,
            deviceFreeBytes = 5 * gigabyte,
        )

        assertEquals(4 * gigabyte, usage.audioBytes)
        assertEquals(4 * gigabyte + 120 * 1_048_576L, usage.totalBytes)
        assertFalse(usage.deviceLowOnSpace)
        // Clearing the cached tier is the only loss-free cleanup on offer.
        assertEquals(1 * gigabyte, usage.reclaimableWithoutLossBytes)
    }

    @Test
    public fun `the domain usage calls a full device low on space without calling it over a limit`() {
        val usage = StorageUsage(
            downloadedBytes = 40 * gigabyte,
            cachedBytes = 0,
            artworkBytes = 0,
            deviceFreeBytes = gigabyte,
        )

        assertTrue(usage.deviceLowOnSpace)
        assertEquals(gigabyte, usage.freeSpaceShortfallBytes)
        // Nothing here is reclaimable by the app: every byte is a download the user asked for.
        assertEquals(0L, usage.reclaimableWithoutLossBytes)
    }
}
