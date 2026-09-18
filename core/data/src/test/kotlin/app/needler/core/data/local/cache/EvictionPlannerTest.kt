package app.needler.core.data.local.cache

import app.needler.core.data.local.TrackKeyDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LRU eviction selection.
 *
 * Pure JVM: no Room, no Android, no clock. Every candidate's "last played" is an explicit constant,
 * so these tests assert the policy rather than the passage of time.
 */
public class EvictionPlannerTest {

    // ---------------------------------------------------------------- nothing to do

    @Test
    public fun `unlimited budget never evicts`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 5_000, unpinnedBytes = 50_000),
            budgetBytes = CacheBudget.UNLIMITED,
            candidates = listOf(candidate("a", sizeBytes = 50_000, lastPlayedAt = 1)),
        )

        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.freedBytes)
        assertNull(plan.warning)
    }

    @Test
    public fun `a zero budget is treated as unlimited rather than as evict everything`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 900),
            budgetBytes = 0L,
            candidates = listOf(candidate("a", sizeBytes = 900, lastPlayedAt = 1)),
        )

        assertTrue(plan.isEmpty)
    }

    @Test
    public fun `usage within budget produces an empty plan`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 200, unpinnedBytes = 300),
            budgetBytes = 1_000,
            candidates = listOf(candidate("a", sizeBytes = 300, lastPlayedAt = 5)),
        )

        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.targetBytes)
        assertEquals(500L, plan.resultingTotalBytes)
        assertNull(plan.warning)
    }

    @Test
    public fun `usage exactly at the budget is not over it`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 400, unpinnedBytes = 600),
            budgetBytes = 1_000,
            candidates = listOf(candidate("a", sizeBytes = 600, lastPlayedAt = 5)),
        )

        assertTrue(plan.isEmpty)
    }

    // ---------------------------------------------------------------- LRU order

    @Test
    public fun `evicts least recently played first and stops once the target is met`() {
        val candidates: List<EvictionCandidate> = listOf(
            candidate("newest", sizeBytes = 100, lastPlayedAt = 900),
            candidate("oldest", sizeBytes = 100, lastPlayedAt = 100),
            candidate("middle", sizeBytes = 100, lastPlayedAt = 500),
        )

        // 1,200 held, 1,000 allowed: 200 bytes must go, which is two rows.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 900, unpinnedBytes = 300),
            budgetBytes = 1_000,
            candidates = candidates,
        )

        assertEquals(listOf("oldest", "middle"), plan.victims.map { it.key.releaseGroupMbid })
        assertEquals(200L, plan.freedBytes)
        assertEquals(200L, plan.targetBytes)
        assertEquals(1_000L, plan.resultingTotalBytes)
        assertNull(plan.warning)
    }

    @Test
    public fun `never-played rows evict before anything that has been played`() {
        val candidates: List<EvictionCandidate> = listOf(
            candidate("playedLongAgo", sizeBytes = 100, lastPlayedAt = 1),
            candidate("neverPlayed", sizeBytes = 100, lastPlayedAt = 0, downloadedAt = 50),
        )

        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 200),
            budgetBytes = 100,
            candidates = candidates,
        )

        assertEquals(listOf("neverPlayed"), plan.victims.map { it.key.releaseGroupMbid })
    }

    @Test
    public fun `ties break on download time then on the track key so the choice is reproducible`() {
        val candidates: List<EvictionCandidate> = listOf(
            candidate("c", sizeBytes = 10, lastPlayedAt = 0, downloadedAt = 200),
            candidate("a", sizeBytes = 10, lastPlayedAt = 0, downloadedAt = 100),
            candidate("b", sizeBytes = 10, lastPlayedAt = 0, downloadedAt = 100),
        )

        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 30),
            budgetBytes = 5,
            candidates = candidates,
        )

        assertEquals(listOf("a", "b", "c"), plan.victims.map { it.key.releaseGroupMbid })
    }

    @Test
    public fun `the plan does not depend on the order the candidates arrive in`() {
        val candidates: List<EvictionCandidate> = listOf(
            candidate("one", sizeBytes = 100, lastPlayedAt = 300),
            candidate("two", sizeBytes = 100, lastPlayedAt = 200),
            candidate("three", sizeBytes = 100, lastPlayedAt = 100),
            candidate("four", sizeBytes = 100, lastPlayedAt = 400),
        )
        val reversed: List<EvictionCandidate> = candidates.reversed()

        val first: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 400),
            budgetBytes = 250,
            candidates = candidates,
        )
        val second: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 400),
            budgetBytes = 250,
            candidates = reversed,
        )

        assertEquals(
            first.victims.map { it.key.canonical },
            second.victims.map { it.key.canonical },
        )
        assertEquals(listOf("three", "two"), first.victims.map { it.key.releaseGroupMbid })
    }

    // ---------------------------------------------------------------- pins

    @Test
    public fun `pinned bytes count towards usage but are never offered for eviction`() {
        // Only unpinned rows are ever passed in: the query that produces candidates filters pins
        // out. The plan must therefore free what it can and stay within its target.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 800, unpinnedBytes = 400),
            budgetBytes = 1_000,
            candidates = listOf(
                candidate("old", sizeBytes = 200, lastPlayedAt = 10),
                candidate("new", sizeBytes = 200, lastPlayedAt = 20),
            ),
        )

        assertEquals(listOf("old"), plan.victims.map { it.key.releaseGroupMbid })
        assertEquals(200L, plan.freedBytes)
        assertEquals(1_000L, plan.resultingTotalBytes)
    }

    @Test
    public fun `pins exceeding the budget warn instead of being evicted`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 5_000, unpinnedBytes = 300),
            budgetBytes = 1_000,
            candidates = listOf(
                candidate("old", sizeBytes = 200, lastPlayedAt = 10),
                candidate("new", sizeBytes = 100, lastPlayedAt = 20),
            ),
        )

        assertEquals(CacheWarning.PINS_EXCEED_BUDGET, plan.warning)
        // Nothing is evicted. Clearing the whole unpinned tier would free 300 against a 4,300
        // shortfall, so it would still be over budget - it would only cost the user their
        // recently-played offline tracks for no gain.
        assertTrue(plan.victims.isEmpty())
        assertEquals(0L, plan.freedBytes)
        assertEquals(5_300L, plan.resultingTotalBytes)
        // The shortfall is still reported, so the UI can say how far over the budget is.
        assertEquals(4_300L, plan.targetBytes)
        assertTrue(plan.budgetStillExceeded)
    }

    @Test
    public fun `pins over budget warn even when there is nothing cached to evict`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 2_000, unpinnedBytes = 0),
            budgetBytes = 1_000,
            candidates = emptyList(),
        )

        assertTrue(plan.isEmpty)
        assertEquals(CacheWarning.PINS_EXCEED_BUDGET, plan.warning)
    }

    @Test
    public fun `pins at exactly the budget are not reported as exceeding it`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 1_000, unpinnedBytes = 50),
            budgetBytes = 1_000,
            candidates = listOf(candidate("a", sizeBytes = 50, lastPlayedAt = 1)),
        )

        assertEquals(listOf("a"), plan.victims.map { it.key.releaseGroupMbid })
        assertNull(plan.warning)
    }

    // ---------------------------------------------------------------- incoming bytes

    @Test
    public fun `room is made for an incoming download before it is written`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 900),
            budgetBytes = 1_000,
            candidates = listOf(
                candidate("old", sizeBytes = 300, lastPlayedAt = 1),
                candidate("new", sizeBytes = 600, lastPlayedAt = 2),
            ),
            incomingBytes = 400,
        )

        // 900 held plus 400 incoming against a 1,000 budget: 300 must go first.
        assertEquals(300L, plan.targetBytes)
        assertEquals(listOf("old"), plan.victims.map { it.key.releaseGroupMbid })
    }

    @Test
    public fun `an incoming download that cannot be made to fit is reported, not half-evicted`() {
        val plan: EvictionPlan = EvictionPlanner.planForIncoming(
            usage = usage(pinnedBytes = 900, unpinnedBytes = 100),
            budgetBytes = 1_000,
            candidates = listOf(candidate("only", sizeBytes = 100, lastPlayedAt = 1)),
            incomingBytes = 500,
        )

        assertEquals(CacheWarning.BUDGET_STILL_EXCEEDED, plan.warning)
        assertEquals(100L, plan.freedBytes)
        assertEquals(500L, plan.targetBytes)
        assertTrue(plan.budgetStillExceeded)
    }

    @Test
    public fun `fitsAfterEviction answers on pinned bytes alone`() {
        val usage: CacheUsage = usage(pinnedBytes = 800, unpinnedBytes = 200)

        assertTrue(EvictionPlanner.fitsAfterEviction(usage, budgetBytes = 1_000, incomingBytes = 200))
        assertFalse(EvictionPlanner.fitsAfterEviction(usage, budgetBytes = 1_000, incomingBytes = 300))
        assertTrue(
            EvictionPlanner.fitsAfterEviction(
                usage,
                budgetBytes = CacheBudget.UNLIMITED,
                incomingBytes = Long.MAX_VALUE / 2,
            ),
        )
    }

    @Test
    public fun `a truncated candidate scan reports that the budget was not met`() {
        // The DAO's scan limit can cut the candidate list short. The planner must say so rather
        // than reporting success, because a caller that looped would find the same rows again.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 10_000),
            budgetBytes = 1_000,
            candidates = listOf(candidate("one", sizeBytes = 500, lastPlayedAt = 1)),
        )

        assertEquals(9_000L, plan.targetBytes)
        assertEquals(500L, plan.freedBytes)
        assertEquals(CacheWarning.BUDGET_STILL_EXCEEDED, plan.warning)
    }

    private fun usage(pinnedBytes: Long, unpinnedBytes: Long): CacheUsage = CacheUsage(
        pinnedBytes = pinnedBytes,
        unpinnedBytes = unpinnedBytes,
        pinnedTrackCount = if (pinnedBytes > 0) 1 else 0,
        unpinnedTrackCount = if (unpinnedBytes > 0) 1 else 0,
    )

    private fun candidate(
        mbid: String,
        sizeBytes: Long,
        lastPlayedAt: Long,
        downloadedAt: Long? = null,
        discNo: Int = 1,
        trackNo: Int = 1,
    ): EvictionCandidate = EvictionCandidate(
        key = TrackKeyDb(releaseGroupMbid = mbid, discNo = discNo, trackNo = trackNo),
        filePath = "/audio/" + mbid + "-" + discNo + "-" + trackNo + ".flac",
        sizeBytes = sizeBytes,
        lastPlayedAt = lastPlayedAt,
        downloadedAt = downloadedAt,
    )
}
