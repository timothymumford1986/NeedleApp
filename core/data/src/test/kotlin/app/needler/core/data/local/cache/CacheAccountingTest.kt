package app.needler.core.data.local.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cache accounting: the numbers behind the Storage section of screen 12, and the budget arithmetic
 * the eviction pass depends on.
 *
 * Screen 12 shows "Music kept on device 2.1 GB" against a "Device storage limit 4 GB", and the
 * requirements add that usage must be split into pinned and cached "so the user can see what a
 * cleanup would actually free". These tests fix both meanings.
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

    @Test
    public fun `over-budget bytes count the pinned tier, since the limit is on total usage`() {
        val status = CacheStatus(
            usage = CacheUsage(
                pinnedBytes = 3 * gigabyte,
                unpinnedBytes = 2 * gigabyte,
                pinnedTrackCount = 1,
                unpinnedTrackCount = 1,
            ),
            budgetBytes = 4 * gigabyte,
        )

        assertEquals(1 * gigabyte, status.overBudgetBytes)
        assertEquals(0L, status.headroomBytes)
        assertFalse(status.pinnedExceedBudget)
    }

    @Test
    public fun `headroom is what is left before the limit`() {
        val status = CacheStatus(
            usage = CacheUsage(
                pinnedBytes = 1 * gigabyte,
                unpinnedBytes = 1 * gigabyte,
                pinnedTrackCount = 1,
                unpinnedTrackCount = 1,
            ),
            budgetBytes = 4 * gigabyte,
        )

        assertEquals(2 * gigabyte, status.headroomBytes)
        assertEquals(0L, status.overBudgetBytes)
    }

    @Test
    public fun `pins alone over the budget are flagged as a warning`() {
        val status = CacheStatus(
            usage = CacheUsage(
                pinnedBytes = 6 * gigabyte,
                unpinnedBytes = 0,
                pinnedTrackCount = 200,
                unpinnedTrackCount = 0,
            ),
            budgetBytes = 4 * gigabyte,
        )

        assertTrue(status.pinnedExceedBudget)
        assertEquals(2 * gigabyte, status.overBudgetBytes)
        // The warning does not become an eviction: the planner has nothing it may touch.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = status.usage,
            budgetBytes = status.budgetBytes,
            candidates = emptyList(),
        )
        assertTrue(plan.isEmpty)
        assertEquals(CacheWarning.PINS_EXCEED_BUDGET, plan.warning)
    }

    @Test
    public fun `an unlimited budget is never over budget and has unbounded headroom`() {
        val status = CacheStatus(
            usage = CacheUsage(
                pinnedBytes = 40 * gigabyte,
                unpinnedBytes = 40 * gigabyte,
                pinnedTrackCount = 1,
                unpinnedTrackCount = 1,
            ),
            budgetBytes = CacheBudget.UNLIMITED,
        )

        assertTrue(status.isUnlimited)
        assertEquals(0L, status.overBudgetBytes)
        assertEquals(Long.MAX_VALUE, status.headroomBytes)
        assertFalse(status.pinnedExceedBudget)
    }

    @Test
    public fun `every non-positive budget means unlimited`() {
        assertTrue(CacheBudget.isUnlimited(CacheBudget.UNLIMITED))
        assertTrue(CacheBudget.isUnlimited(0L))
        assertTrue(CacheBudget.isUnlimited(-100L))
        assertFalse(CacheBudget.isUnlimited(1L))
    }

    @Test
    public fun `the budget presets are the six the requirements list`() {
        assertEquals(
            listOf(
                1 * gigabyte,
                2 * gigabyte,
                4 * gigabyte,
                8 * gigabyte,
                16 * gigabyte,
                CacheBudget.UNLIMITED,
            ),
            CacheBudget.PRESETS,
        )
        assertEquals(4 * gigabyte, CacheBudget.DEFAULT_BYTES)
    }

    @Test
    public fun `an eviction plan reports what a cleanup would free`() {
        val plan = EvictionPlan(
            victims = emptyList(),
            freedBytes = 0,
            targetBytes = 0,
            resultingTotalBytes = 2 * gigabyte,
            warning = null,
        )

        assertTrue(plan.isEmpty)
        assertFalse(plan.budgetStillExceeded)
        assertEquals(2 * gigabyte, plan.resultingTotalBytes)
    }

    @Test
    public fun `an eviction result starts empty and accumulates nothing by default`() {
        assertEquals(0L, EvictionResult.Empty.freedBytes)
        assertTrue(EvictionResult.Empty.evictedKeys.isEmpty())
        assertTrue(EvictionResult.Empty.failedPaths.isEmpty())
    }
}
