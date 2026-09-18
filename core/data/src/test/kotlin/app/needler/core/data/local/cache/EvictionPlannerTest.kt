package app.needler.core.data.local.cache

import app.needler.core.data.local.TrackKeyDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Eviction selection under the free-space policy.
 *
 * Pure JVM: no Room, no Android, no `StatFs`, no clock. Free space and the floor are both explicit
 * constants, so these tests assert the policy rather than the state of the machine they run on. The
 * floor is [FLOOR] rather than the production 2 GB so the arithmetic stays readable.
 */
public class EvictionPlannerTest {

    private val floor: Long = FLOOR

    // ---------------------------------------------------------------- nothing to do

    @Test
    public fun `an unreadable free-space figure suspends the policy instead of guessing`() {
        // Guessing low would evict a healthy device's music; guessing high would let the cache fill
        // a device. Neither is defensible from a reading nobody trusts.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 5_000, unpinnedBytes = 50_000),
            freeSpaceBytes = FreeSpaceFloor.UNKNOWN,
            candidates = listOf(candidate("a", sizeBytes = 50_000, lastPlayedAt = 1)),
            incomingBytes = 10_000,
            floorBytes = floor,
        )

        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.freedBytes)
        assertFalse(plan.skipsIncoming)
        assertFalse(plan.deviceStillLowOnSpace)
        assertNull(plan.warning)
    }

    @Test
    public fun `a device with room to spare evicts nothing`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 200, unpinnedBytes = 300),
            freeSpaceBytes = 5_000,
            candidates = listOf(candidate("a", sizeBytes = 300, lastPlayedAt = 5)),
            floorBytes = floor,
        )

        assertTrue(plan.isEmpty)
        assertEquals(0L, plan.targetBytes)
        assertEquals(500L, plan.resultingTotalBytes)
        assertEquals(5_000L, plan.resultingFreeBytes)
        assertNull(plan.warning)
    }

    @Test
    public fun `free space exactly at the floor is not below it`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 400, unpinnedBytes = 600),
            freeSpaceBytes = floor,
            candidates = listOf(candidate("a", sizeBytes = 600, lastPlayedAt = 5)),
            floorBytes = floor,
        )

        assertTrue(plan.isEmpty)
        assertFalse(plan.deviceStillLowOnSpace)
        assertNull(plan.warning)
    }

    @Test
    public fun `zero free space is a real reading, not the unknown sentinel`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 400),
            freeSpaceBytes = 0L,
            candidates = listOf(candidate("a", sizeBytes = 400, lastPlayedAt = 5)),
            floorBytes = floor,
        )

        assertEquals(listOf("a"), plan.victims.map { it.key.releaseGroupMbid })
        assertEquals(floor, plan.targetBytes)
    }

    // ---------------------------------------------------------------- LRU order

    @Test
    public fun `evicts least recently played first and stops once the floor is reached`() {
        val candidates: List<EvictionCandidate> = listOf(
            candidate("newest", sizeBytes = 100, lastPlayedAt = 900),
            candidate("oldest", sizeBytes = 100, lastPlayedAt = 100),
            candidate("middle", sizeBytes = 100, lastPlayedAt = 500),
        )

        // 800 free against a 1,000 floor: 200 bytes must come back, which is two rows.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 900, unpinnedBytes = 300),
            freeSpaceBytes = 800,
            candidates = candidates,
            floorBytes = floor,
        )

        assertEquals(listOf("oldest", "middle"), plan.victims.map { it.key.releaseGroupMbid })
        assertEquals(200L, plan.freedBytes)
        assertEquals(200L, plan.targetBytes)
        assertEquals(1_000L, plan.resultingTotalBytes)
        assertEquals(1_000L, plan.resultingFreeBytes)
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
            freeSpaceBytes = 900,
            candidates = candidates,
            floorBytes = floor,
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
            freeSpaceBytes = 975,
            candidates = candidates,
            floorBytes = floor,
        )

        assertEquals(listOf("a", "b", "c"), plan.victims.map { it.key.releaseGroupMbid })
    }

    @Test
    public fun `the plan is deterministic however the candidates are shuffled`() {
        // The planner sorts its own input rather than trusting the query's ORDER BY, so an eviction
        // pass cannot thrash a different arbitrary tail on every call.
        val candidates: List<EvictionCandidate> = listOf(
            candidate("one", sizeBytes = 100, lastPlayedAt = 300),
            candidate("two", sizeBytes = 100, lastPlayedAt = 200),
            candidate("three", sizeBytes = 100, lastPlayedAt = 100),
            candidate("four", sizeBytes = 100, lastPlayedAt = 400),
            candidate("five", sizeBytes = 100, lastPlayedAt = 0, downloadedAt = 900),
            candidate("six", sizeBytes = 100, lastPlayedAt = 0, downloadedAt = 100),
        )
        val expected: List<String> = listOf("six", "five", "three")

        repeat(20) { seed ->
            val plan: EvictionPlan = EvictionPlanner.plan(
                usage = usage(pinnedBytes = 0, unpinnedBytes = 600),
                freeSpaceBytes = 750,
                candidates = candidates.shuffled(Random(seed)),
                floorBytes = floor,
            )

            assertEquals(expected, plan.victims.map { it.key.releaseGroupMbid })
            assertEquals(300L, plan.freedBytes)
        }
    }

    // ---------------------------------------------------------------- downloads are untouchable

    @Test
    public fun `pinned bytes are never victims even when the device is nearly full`() {
        // The device is on its knees and every byte Needler holds is a download. Downloads are what
        // the user explicitly asked to keep, so the plan is empty and the situation is reported: the
        // UI warns and offers removal, and the app deletes nothing on its own.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 50_000, unpinnedBytes = 0),
            freeSpaceBytes = 10,
            candidates = emptyList(),
            floorBytes = floor,
        )

        assertTrue(plan.victims.isEmpty())
        assertEquals(0L, plan.freedBytes)
        assertEquals(50_000L, plan.resultingTotalBytes)
        assertEquals(CacheWarning.DEVICE_LOW_ON_SPACE, plan.warning)
        assertTrue(plan.deviceStillLowOnSpace)
        assertTrue(plan.targetUnmet)
    }

    @Test
    public fun `a nearly full device gives up its cached tier but keeps every download`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 40_000, unpinnedBytes = 250),
            freeSpaceBytes = 100,
            candidates = listOf(
                candidate("cachedOld", sizeBytes = 150, lastPlayedAt = 10),
                candidate("cachedNew", sizeBytes = 100, lastPlayedAt = 20),
            ),
            floorBytes = floor,
        )

        // Everything evictable goes - it is all real room recovered - and the downloads stay put.
        assertEquals(
            listOf("cachedOld", "cachedNew"),
            plan.victims.map { it.key.releaseGroupMbid },
        )
        assertEquals(250L, plan.freedBytes)
        assertEquals(40_000L, plan.resultingTotalBytes)
        assertEquals(CacheWarning.DEVICE_LOW_ON_SPACE, plan.warning)
        assertTrue(plan.deviceStillLowOnSpace)
    }

    // ---------------------------------------------------------------- incoming bytes

    @Test
    public fun `room is made for an incoming write before it happens`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 900),
            freeSpaceBytes = 1_000,
            candidates = listOf(
                candidate("old", sizeBytes = 300, lastPlayedAt = 1),
                candidate("new", sizeBytes = 600, lastPlayedAt = 2),
            ),
            incomingBytes = 300,
            floorBytes = floor,
        )

        // Free space is exactly on the floor, so all 300 incoming bytes must be freed first.
        assertEquals(300L, plan.targetBytes)
        assertEquals(listOf("old"), plan.victims.map { it.key.releaseGroupMbid })
        assertFalse(plan.skipsIncoming)
        assertNull(plan.warning)
    }

    @Test
    public fun `an incoming write inside the headroom needs no eviction at all`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 900),
            freeSpaceBytes = 1_400,
            candidates = listOf(candidate("old", sizeBytes = 300, lastPlayedAt = 1)),
            incomingBytes = 400,
            floorBytes = floor,
        )

        assertTrue(plan.isEmpty)
        assertFalse(plan.skipsIncoming)
    }

    @Test
    public fun `caching is skipped rather than over-evicting when the floor cannot be met`() {
        // 1,200 free, a 1,000 floor and a 700-byte track: 500 bytes must be freed, but the whole
        // cached tier is only 200. Evicting it would cost the user their recently-played music AND
        // still leave the write unable to fit, so nothing is evicted and the bytes are simply not
        // retained. The track still streams.
        val plan: EvictionPlan = EvictionPlanner.planForIncoming(
            usage = usage(pinnedBytes = 30_000, unpinnedBytes = 200),
            freeSpaceBytes = 1_200,
            candidates = listOf(
                candidate("old", sizeBytes = 100, lastPlayedAt = 1),
                candidate("new", sizeBytes = 100, lastPlayedAt = 2),
            ),
            incomingBytes = 700,
            floorBytes = floor,
        )

        assertTrue(plan.skipsIncoming)
        assertTrue(plan.victims.isEmpty())
        assertEquals(0L, plan.freedBytes)
        assertEquals(500L, plan.targetBytes)
        assertEquals(CacheWarning.INCOMING_NOT_CACHED, plan.warning)
        // The device itself was never low: nothing needed fixing, the write just did not fit.
        assertFalse(plan.deviceStillLowOnSpace)
        assertEquals(1_200L, plan.resultingFreeBytes)
    }

    @Test
    public fun `a skipped write still keeps the housekeeping eviction that fixed the device`() {
        // Two different jobs in one pass: the device is 200 bytes below the floor, which is worth
        // fixing whatever happens to the write, and the write itself is hopeless and gets skipped.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 30_000, unpinnedBytes = 400),
            freeSpaceBytes = 800,
            candidates = listOf(
                candidate("oldest", sizeBytes = 300, lastPlayedAt = 1),
                candidate("newer", sizeBytes = 100, lastPlayedAt = 9),
            ),
            incomingBytes = 5_000,
            floorBytes = floor,
        )

        assertEquals(listOf("oldest"), plan.victims.map { it.key.releaseGroupMbid })
        assertEquals(300L, plan.freedBytes)
        assertEquals(1_100L, plan.resultingFreeBytes)
        assertFalse(plan.deviceStillLowOnSpace)
        assertTrue(plan.skipsIncoming)
        assertEquals(CacheWarning.INCOMING_NOT_CACHED, plan.warning)
    }

    @Test
    public fun `a truncated candidate scan skips the write rather than half-evicting for it`() {
        // The DAO's scan limit can cut the candidate list short. A short list can only make the plan
        // more conservative - it skips a write it might have made room for - never evict something
        // it should not.
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 10_000),
            freeSpaceBytes = 1_000,
            candidates = listOf(candidate("one", sizeBytes = 100, lastPlayedAt = 1)),
            incomingBytes = 5_000,
            floorBytes = floor,
        )

        assertEquals(5_000L, plan.targetBytes)
        assertEquals(0L, plan.freedBytes)
        assertTrue(plan.skipsIncoming)
        assertEquals(CacheWarning.INCOMING_NOT_CACHED, plan.warning)
    }

    @Test
    public fun `negative incoming bytes are treated as none`() {
        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = usage(pinnedBytes = 0, unpinnedBytes = 100),
            freeSpaceBytes = 1_000,
            candidates = listOf(candidate("a", sizeBytes = 100, lastPlayedAt = 1)),
            incomingBytes = -500,
            floorBytes = floor,
        )

        assertTrue(plan.isEmpty)
        assertFalse(plan.skipsIncoming)
    }

    // ---------------------------------------------------------------- fitsAfterEviction

    @Test
    public fun `fitsAfterEviction counts the cached tier and never the downloads`() {
        val usage: CacheUsage = usage(pinnedBytes = 40_000, unpinnedBytes = 200)

        // 900 free plus 200 evictable is 1,100: a 100-byte track fits, a 200-byte one does not.
        assertTrue(EvictionPlanner.fitsAfterEviction(usage, 900, incomingBytes = 100, floorBytes = floor))
        assertFalse(EvictionPlanner.fitsAfterEviction(usage, 900, incomingBytes = 200, floorBytes = floor))
        // The 40,000 pinned bytes never enter the calculation, however full the device is.
        assertFalse(EvictionPlanner.fitsAfterEviction(usage, 10, incomingBytes = 1, floorBytes = floor))
    }

    @Test
    public fun `fitsAfterEviction answers yes when free space is unknown`() {
        assertTrue(
            EvictionPlanner.fitsAfterEviction(
                usage = usage(pinnedBytes = 0, unpinnedBytes = 0),
                freeSpaceBytes = FreeSpaceFloor.UNKNOWN,
                incomingBytes = Long.MAX_VALUE / 2,
                floorBytes = floor,
            ),
        )
    }

    @Test
    public fun `the production floor is two gigabytes and needs no setting to say so`() {
        assertEquals(2L * 1_073_741_824L, FreeSpaceFloor.MINIMUM_BYTES)
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

    private companion object {
        /** A readable stand-in for the production 2 GB floor. */
        const val FLOOR: Long = 1_000L
    }
}
