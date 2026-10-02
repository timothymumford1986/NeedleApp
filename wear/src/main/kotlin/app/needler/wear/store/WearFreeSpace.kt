package app.needler.wear.store

import java.io.File

/**
 * Reads how much room the volume holding the watch's store has left.
 *
 * ## Why this is an interface at all
 *
 * The same reason `DeviceFreeSpace` is one on the phone: a decision that depends on a filesystem
 * reading cannot be asserted unless the reading is an argument. There the stated reason is that
 * "`EvictionPlanner` must stay pure and deterministic, so free space is passed *into* it as a plain
 * number rather than read from inside it". Here it is [WearAudioStore.ingestTrack], whose room check
 * took its verdict from the build machine until this seam existed: `WearAudioStoreTest` runs over a
 * JUnit `TemporaryFolder`, so `usableSpace` measured the developer's own drive, and thirteen
 * assertions about the commit order silently became assertions about how full that drive was. They
 * passed with 84.8 GB free and failed with 48.4 GB, on the same code, because
 * [WearStoreSpace.VOLUME_PERCENT] of a 498 GB volume is 49.8 GB. A test that passes on CI and fails
 * on a full laptop is reporting the laptop.
 *
 * The seam is therefore the *reading*, not the policy. [WearStoreSpace] still decides everything, and
 * [FileWearFreeSpace] is still what the app runs.
 *
 * ## One reading rather than the phone's two
 *
 * `DeviceFreeSpace` splits `freeBytes()` from `floorBytes()`, because the planner downstream of it
 * wants plain numbers and the floor is a property of the volume. [WearStoreSpace] already carries the
 * pair and derives [WearStoreSpace.floorBytes] from it, so this hands back the pair whole - and it
 * has to: [WearStoreSpace.isUnknown] tells a probe that failed from a volume that is genuinely full
 * by whether the total is zero, and a seam that answered one number could not express the difference
 * between the two cases that call for opposite explanations to the user. The phone gives the same
 * reason for keeping its own two halves on one interface - "one measurement answers both".
 *
 * The alternative considered and rejected was a seam that handed the store a pre-built
 * [WearStoreSpace] at construction, which is a smaller change and reads the same at the call site. It
 * would cache the figure for the lifetime of the store, and REQUIREMENTS.md is explicit that free
 * space "is read fresh every time, because a stale figure is precisely how a cache overshoots" - the
 * store holds one instance per process, so that cache would live as long as the app.
 *
 * Implementations must never throw, and must not cache. A volume that cannot be measured answers
 * [WearStoreSpace.Unknown], which suspends the policy rather than guessing at it.
 */
fun interface WearFreeSpace {

    /** The volume's room as it stands now. Called on every room check, and never cached. */
    fun read(): WearStoreSpace

    companion object {

        /**
         * A fixed reading of a volume of a known size, for tests and for callers that have measured.
         *
         * Both figures are stated because the floor scales with the total: a fixture that gave only
         * the usable bytes would be asserting against whichever floor the default happened to pick,
         * which is the class of accident this interface exists to end. Named and shaped after the
         * phone's `DeviceFreeSpace.ofVolume` rather than inventing a second idiom for the same job.
         */
        fun ofVolume(usableBytes: Long, totalBytes: Long): WearFreeSpace =
            WearFreeSpace { WearStoreSpace(usableBytes = usableBytes, totalBytes = totalBytes) }

        /**
         * A volume nothing could be read from, which refuses everything.
         *
         * The reader's counterpart to [WearStoreSpace.Unknown], and the only way to assert the
         * suspended policy on purpose: the real probe produces it from a `SecurityException` or a path
         * that names no partition, and a test on a working filesystem can arrange neither.
         */
        val Unreadable: WearFreeSpace = WearFreeSpace { WearStoreSpace.Unknown }
    }
}

/**
 * The real reading: `usableSpace` and `totalSpace` on the volume the audio directory sits on.
 *
 * This is what [WearAudioStore] uses unless a caller says otherwise, so the shipped behaviour is the
 * behaviour that was here before the seam - the same two calls, on the same directory, taken at the
 * same moment in [WearAudioStore.ingestTrack].
 *
 * ## Why `usableSpace` and not `StatFs`
 *
 * It is the same measurement - both end at `statfs` - and it needs no Android import, which is what
 * lets [WearStoreSpace] and every decision that depends on it be unit-tested on the JVM. `usableSpace`
 * is also the stricter of the two figures the platform offers, being free blocks minus the reserve a
 * non-root process may not use, which is the right one for an app-private write. The phone's
 * `StatFsDeviceFreeSpace` chooses `availableBytes` over `freeBytes` for exactly that reason.
 *
 * ## Why the audio directory, with the root behind it
 *
 * The directory the files actually go into is the only reading that answers the question the policy
 * asks, for the reason the phone gives: app-private storage is usually the data partition but need
 * not be, and an adopted-storage or multi-user device can put it elsewhere.
 *
 * The fallback is a bug fix, not tidiness. `usableSpace` answers 0 for a path that names no
 * partition, so measuring the audio directory before the first ingest reported an unreadable volume,
 * which [WearStoreSpace.canAccept] refuses outright. That made the first transfer to a fresh watch
 * fail with `NoRoom` and *stay* failing, because the directory that would have fixed the reading is
 * created after the room check, not before it. The root is on the same volume and always exists, so it
 * is the honest answer while the directory is absent. The phone solves the same problem the same way,
 * walking up to the nearest existing ancestor.
 *
 * ## Fresh every time
 *
 * Nothing here is remembered between calls, for the reason REQUIREMENTS.md gives for the phone: free
 * space moves under the app as other apps write, and "a stale figure is precisely how a cache
 * overshoots".
 *
 * @param root the store's directory, which is the app's `filesDir` in the app and a temporary folder
 *   in a test. [WearAudioStore.AUDIO_DIRECTORY] is resolved against it, so the two always agree about
 *   which directory is being measured.
 */
class FileWearFreeSpace(private val root: File) : WearFreeSpace {

    override fun read(): WearStoreSpace {
        val directory: File =
            File(root, WearAudioStore.AUDIO_DIRECTORY).takeIf { it.isDirectory } ?: root
        return try {
            WearStoreSpace(usableBytes = directory.usableSpace, totalBytes = directory.totalSpace)
        } catch (failure: SecurityException) {
            WearStoreSpace.Unknown
        }
    }
}
