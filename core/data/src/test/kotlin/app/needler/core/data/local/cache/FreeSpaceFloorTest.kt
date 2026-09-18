package app.needler.core.data.local.cache

import app.needler.core.data.local.TrackKeyDb
import app.needler.core.domain.model.StorageUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The free-space floor, which is no longer one number.
 *
 * A flat 2 GB was wrong at both ends of the range of devices Needler runs on: a sixteenth of a 32 GB
 * phone, and noise on a 1 TB one. The floor is now `max(2 GB, 2% of the volume)`, computed where the
 * volume is measured and handed to [EvictionPlanner] as a plain parameter - these tests pin the
 * arithmetic, and in particular pin the two edges where getting it wrong is expensive: a tiny volume
 * must not drop below the minimum, and an unmeasurable one must not fall back to no floor at all.
 */
public class FreeSpaceFloorTest {

    private val gigabyte: Long = 1_073_741_824L

    @Test
    public fun `the minimum is two gigabytes and is shared with the domain`() {
        // One constant, so the layer that plans evictions and the layer that reports usage to the
        // user cannot drift apart on what "low on space" means.
        assertEquals(2 * gigabyte, FreeSpaceFloor.MINIMUM_BYTES)
        assertEquals(StorageUsage.MINIMUM_FREE_SPACE_FLOOR_BYTES, FreeSpaceFloor.MINIMUM_BYTES)
    }

    @Test
    public fun `a small volume is held to the two-gigabyte minimum`() {
        // 2% of a 32 GB phone is 655 MB, which is not enough room for one system update. The
        // minimum is what stops the percentage from becoming meaningless at the small end.
        val floor: Long = FreeSpaceFloor.forVolume(32 * gigabyte)

        assertEquals(FreeSpaceFloor.MINIMUM_BYTES, floor)
        assertTrue(floor > 32 * gigabyte / 100 * 2)
    }

    @Test
    public fun `a large volume is held to the percentage instead`() {
        // 2% of 1 TB is about 20 GB. Holding a device this size to 2 GB would let the cache run it
        // to the point where the system itself starts complaining about storage.
        val terabyte: Long = 1_024 * gigabyte
        val floor: Long = FreeSpaceFloor.forVolume(terabyte)

        assertEquals(terabyte / 100 * 2, floor)
        assertTrue(floor > FreeSpaceFloor.MINIMUM_BYTES)
        assertEquals(20L, floor / gigabyte)
    }

    @Test
    public fun `the percentage takes over exactly where it passes the minimum`() {
        // 2% of 100 GB is 2 GB, so that is the crossover. Either side of it the larger value wins,
        // which is the whole rule.
        val hundredGigabytes: Long = 100 * gigabyte

        assertEquals(FreeSpaceFloor.MINIMUM_BYTES, FreeSpaceFloor.forVolume(hundredGigabytes))
        assertEquals(
            FreeSpaceFloor.MINIMUM_BYTES,
            FreeSpaceFloor.forVolume(hundredGigabytes - gigabyte),
        )
        assertTrue(FreeSpaceFloor.forVolume(hundredGigabytes + 10 * gigabyte) > FreeSpaceFloor.MINIMUM_BYTES)
    }

    @Test
    public fun `an unknown volume size falls back to the minimum and never to zero`() {
        // A volume that could not be measured is the case where a floor matters most: a fallback of
        // zero would remove the bound entirely on exactly the devices least well understood. Only
        // the *free* reading may suspend the policy.
        assertEquals(FreeSpaceFloor.MINIMUM_BYTES, FreeSpaceFloor.forVolume(0L))
        assertEquals(FreeSpaceFloor.MINIMUM_BYTES, FreeSpaceFloor.forVolume(-1L))
        assertEquals(FreeSpaceFloor.MINIMUM_BYTES, FreeSpaceFloor.forVolume(FreeSpaceFloor.UNKNOWN))
    }

    @Test
    public fun `the percentage does not overflow on an implausibly large volume`() {
        // Multiplying before dividing would wrap Long at about 4.6 exabytes. Dividing first cannot.
        val floor: Long = FreeSpaceFloor.forVolume(Long.MAX_VALUE)

        assertTrue(floor > 0L)
        assertEquals(Long.MAX_VALUE / 100 * 2, floor)
    }

    // ---------------------------------------------------------------- the reading behind it

    @Test
    public fun `a fixed reading has no volume to scale to, so it gets the minimum`() {
        val reading: DeviceFreeSpace = DeviceFreeSpace.of(8 * gigabyte)

        assertEquals(8 * gigabyte, reading.freeBytes())
        assertEquals(FreeSpaceFloor.MINIMUM_BYTES, reading.floorBytes())
    }

    @Test
    public fun `a reading of a known volume carries that volume's floor`() {
        val reading: DeviceFreeSpace = DeviceFreeSpace.ofVolume(
            freeBytes = 30 * gigabyte,
            totalBytes = 512 * gigabyte,
        )

        assertEquals(30 * gigabyte, reading.freeBytes())
        assertEquals(512 * gigabyte / 100 * 2, reading.floorBytes())
        // A device with 30 GB spare is still short of a floor that scales: about 10 GB here.
        assertTrue(reading.floorBytes() < reading.freeBytes())
    }

    @Test
    public fun `a device below its scaled floor is low on space even with gigabytes spare`() {
        // The point of scaling: 5 GB free on a 1 TB device is low, and a flat 2 GB floor would have
        // called it healthy and let the cache keep growing.
        val terabyte: Long = 1_024 * gigabyte
        val status = CacheStatus(
            usage = CacheUsage(
                pinnedBytes = 200 * gigabyte,
                unpinnedBytes = 50 * gigabyte,
                pinnedTrackCount = 12_000,
                unpinnedTrackCount = 3_000,
            ),
            deviceFreeBytes = 5 * gigabyte,
            floorBytes = FreeSpaceFloor.forVolume(terabyte),
        )

        assertTrue(status.deviceLowOnSpace)
        assertEquals(0L, status.cacheHeadroomBytes)
        assertFalse(5 * gigabyte < FreeSpaceFloor.MINIMUM_BYTES)
    }

    @Test
    public fun `the planner takes the scaled floor as a parameter and stays pure`() {
        // EvictionPlanner never reads a device. The floor arrives as a number, so the same plan is
        // reproducible off-device - this is the property that keeps the policy testable.
        val terabyte: Long = 1_024 * gigabyte
        val candidate = EvictionCandidate(
            key = TrackKeyDb("mbid-a", 1, 1),
            filePath = "/audio/mbid-a/1/1.flac",
            sizeBytes = 4 * gigabyte,
            lastPlayedAt = 0L,
            downloadedAt = 1L,
        )

        val plan: EvictionPlan = EvictionPlanner.plan(
            usage = CacheUsage(
                pinnedBytes = 0L,
                unpinnedBytes = 4 * gigabyte,
                pinnedTrackCount = 0,
                unpinnedTrackCount = 1,
            ),
            freeSpaceBytes = 18 * gigabyte,
            candidates = listOf(candidate),
            floorBytes = FreeSpaceFloor.forVolume(terabyte),
        )

        // 18 GB free against a ~20 GB floor: the pass runs, where a flat 2 GB floor would have left
        // the device alone until it was genuinely in trouble.
        assertFalse(plan.isEmpty)
        assertEquals(4 * gigabyte, plan.freedBytes)
    }
}
