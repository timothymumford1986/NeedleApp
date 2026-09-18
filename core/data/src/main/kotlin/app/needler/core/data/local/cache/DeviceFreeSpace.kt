package app.needler.core.data.local.cache

import android.os.StatFs
import java.io.File

/**
 * Reads how much room the device has left on the volume the audio cache lives on, and how much room
 * that volume is required to keep.
 *
 * This exists as an interface for one reason: [EvictionPlanner] must stay pure and deterministic, so
 * free space is passed *into* it as a plain number rather than read from inside it. Everything that
 * has to touch Android to obtain that number lives behind here, and tests substitute a constant.
 *
 * [floorBytes] is here too, and not on the planner, because the floor is a property of the volume:
 * it scales with the volume's total size, and the only place that size is to hand is the same
 * `StatFs` call that reports the free figure. Keeping the two together means one measurement answers
 * both and the planner still receives plain numbers.
 *
 * Implementations must never throw. A device that cannot be measured returns
 * [FreeSpaceFloor.UNKNOWN], which suspends the eviction policy rather than guessing.
 */
public fun interface DeviceFreeSpace {

    /** Free bytes available to this app, or [FreeSpaceFloor.UNKNOWN] when it could not be read. */
    public fun freeBytes(): Long

    /**
     * Free space this volume must keep: see [FreeSpaceFloor.forVolume].
     *
     * Defaults to [FreeSpaceFloor.MINIMUM_BYTES], which is the answer whenever the volume's size is
     * not known - a fixed test reading, or a `StatFs` call that failed. There is deliberately no
     * "unknown floor": an unmeasurable volume is held to the minimum rather than to nothing, because
     * a floor of zero would let the cache fill precisely the devices least well understood.
     */
    public fun floorBytes(): Long = FreeSpaceFloor.MINIMUM_BYTES

    public companion object {
        /**
         * A fixed reading, for tests and for callers that have already measured. The floor is the
         * minimum, since a bare byte count says nothing about how big the volume is.
         */
        public fun of(bytes: Long): DeviceFreeSpace = DeviceFreeSpace { bytes }

        /** A fixed reading with an explicit floor, for tests that work in small numbers. */
        public fun of(freeBytes: Long, floorBytes: Long): DeviceFreeSpace =
            object : DeviceFreeSpace {
                override fun freeBytes(): Long = freeBytes

                override fun floorBytes(): Long = floorBytes
            }

        /** A fixed reading of a volume of a known size, which sets the floor the real device would. */
        public fun ofVolume(freeBytes: Long, totalBytes: Long): DeviceFreeSpace =
            of(freeBytes = freeBytes, floorBytes = FreeSpaceFloor.forVolume(totalBytes))
    }
}

/**
 * The real reading: `StatFs` on the volume holding the audio directory.
 *
 * ## Why the audio directory and not "the device"
 *
 * Audio lives in app-private internal storage, which on most devices is the data partition but need
 * not be - an adopted-storage or multi-user device can put it somewhere else entirely. Measuring the
 * directory the files actually go into is the only reading that answers the question the policy
 * asks: will writing these bytes leave *this volume* short of room.
 *
 * `availableBytes` is used rather than `freeBytes` because it reports what an unprivileged app may
 * actually use, excluding the reserve the filesystem keeps for root. Planning against the larger
 * number would let the cache push a device into the region where writes start failing.
 *
 * The reading is taken fresh on every call and never cached: free space moves under the app as other
 * apps write, and a stale figure is exactly how a cache overshoots.
 *
 * ## Why the floor is computed here
 *
 * The floor scales with the volume - `max(2 GB, 2% of it)` - and `totalBytes` comes from the very
 * same `StatFs`, so this is the one place in the app where both halves of the answer are to hand at
 * once. Computing it anywhere else would mean either passing the volume size around or reading the
 * filesystem from a layer that has no business touching it.
 */
public class StatFsDeviceFreeSpace(
    private val audioDirectory: File,
) : DeviceFreeSpace {

    override fun freeBytes(): Long = statFs()?.availableBytes ?: FreeSpaceFloor.UNKNOWN

    /**
     * The floor for this volume, or [FreeSpaceFloor.MINIMUM_BYTES] when it cannot be measured.
     *
     * A failed probe falls back to the minimum rather than to [FreeSpaceFloor.UNKNOWN]: the *free*
     * reading is what the policy cannot proceed without, and it suspends itself when that fails. An
     * unknown volume size only costs the scaling, and the minimum is the safe reading of it.
     */
    override fun floorBytes(): Long {
        val total: Long = statFs()?.totalBytes ?: return FreeSpaceFloor.MINIMUM_BYTES
        return FreeSpaceFloor.forVolume(total)
    }

    /**
     * A fresh `StatFs` on the volume the audio lives on, or null when it could not be taken.
     *
     * Never cached, for the reason in the class KDoc, and never allowed to throw: the two failures
     * `StatFs` has are a path it cannot stat and a path it may not read, and neither is a reason to
     * bring the app down over a number.
     */
    private fun statFs(): StatFs? {
        val measurable: File = existingAncestorOf(audioDirectory) ?: return null
        return try {
            StatFs(measurable.absolutePath)
        } catch (error: IllegalArgumentException) {
            // StatFs throws this for a path it cannot stat. Nothing is logged here that could carry a
            // path off the device; the caller's diagnostics log is the place for that.
            null
        } catch (error: SecurityException) {
            null
        }
    }

    /**
     * The nearest existing directory at or above [start].
     *
     * The audio directory may not exist yet on a fresh install, and `StatFs` refuses a path that is
     * not there. Any existing ancestor sits on the same volume, so it gives the same answer without
     * the probe having to create directories as a side effect of reading a number.
     */
    private fun existingAncestorOf(start: File): File? {
        var current: File? = start
        while (current != null) {
            if (current.exists()) return current
            current = current.parentFile
        }
        return null
    }
}
