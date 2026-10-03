package app.needler.wear.store

/**
 * How much room the watch's store has, and the bound it is held to.
 *
 * ## The bound, and why it is a free-space floor rather than a budget
 *
 * REQUIREMENTS.md "Storage, and why there is no budget" settles the shape of this for the phone and
 * the argument transfers whole: "a budget asked the user to predict how much music they wanted kept,
 * then punished a low guess by evicting music from a phone with 200 GB spare. Free space measures the
 * thing the user actually cares about - *is my phone full* - needs no preference and no default to
 * argue about, and self-corrects when they move from a 64 GB device to a 512 GB one." Every clause of
 * that is true of a watch, and more sharply: watches ship with anything from 4 GB to 32 GB, so a
 * preset the user picked on one is wrong on the next.
 *
 * So the bound is a floor of free space on the volume the store sits on. What is *not* borrowed from
 * the phone is the consequence: see [canAccept].
 *
 * ## Why the floor is not the phone's floor
 *
 * The phone's is `max(2 GB, 2% of the volume)`, and 2 GB is defended there as "about one system update
 * plus working room". On an 8 GB watch that is a quarter of the device, which would leave the store
 * unusable; and a Wear system image is a fraction of a phone's. So the minimum comes down to
 * [MINIMUM_FREE_BYTES] and the share goes up to [VOLUME_PERCENT], which keeps the proportional half of
 * the argument intact - the floor still scales with the device rather than being one number that is
 * wrong at both ends of the range.
 *
 * The numbers are named constants with this note attached rather than a preference, for the reason the
 * phone's are: "a floor the user can lower is a floor that has stopped protecting them."
 *
 * ## Why a failed reading suspends the policy
 *
 * [isUnknown] mirrors REQUIREMENTS.md exactly: "A reading that fails **suspends** the policy rather
 * than guessing: guessing low deletes a healthy device's music, and guessing high fills the device."
 * Here suspending means refusing the transfer, which is the conservative direction on a watch - the
 * bytes are still on the phone, and the worst case is an album that does not arrive until the next
 * pass, rather than a watch with no room left for its own system update.
 *
 * Note which way that differs from the phone: the phone suspends by *not evicting*, because its bytes
 * are already written. The watch suspends by *not accepting*, because its bytes have not arrived yet.
 * Same rule, opposite side of the write.
 *
 * @param usableBytes bytes the store may actually write, as [java.io.File.getUsableSpace] reports
 *   them - which is the figure after the reserved blocks a non-root process cannot touch, and
 *   therefore the honest figure for an app-private write.
 * @param totalBytes the volume's size, or 0 when it could not be read.
 */
data class WearStoreSpace(
    val usableBytes: Long,
    val totalBytes: Long,
) {

    /** The floor this volume is held to. See the class note for why it is not one flat number. */
    val floorBytes: Long get() = floorFor(totalBytes)

    /**
     * True when free space could not be read at all.
     *
     * Distinguished from a genuinely full volume by the volume's own size: a total of zero means the
     * probe failed, where a total above zero with nothing usable means the watch really is full.
     * Without that pair the two would be one reading, and they call for opposite explanations to the
     * user.
     */
    val isUnknown: Boolean get() = totalBytes <= 0L

    /**
     * How many more bytes the store may hold before it would breach the floor.
     *
     * Zero once the watch is at or under the floor, and zero for an unreadable volume - the store
     * accepts nothing it cannot account for.
     */
    val headroomBytes: Long
        get() = when {
            isUnknown -> 0L
            usableBytes <= floorBytes -> 0L
            else -> usableBytes - floorBytes
        }

    /**
     * Whether [incomingBytes] may be written.
     *
     * **Nothing is evicted to make room, ever.** The watch's tier is entirely REQUIREMENTS.md's
     * **Device** tier - "kept because the user asked for it; never evicted automatically" - so
     * the floor refuses the incoming bytes instead of choosing a victim. REQUIREMENTS.md takes exactly
     * this decision for the phone in the one case where its own eviction cannot help: "the incoming
     * bytes are **skipped, not forced in**, and nothing extra is evicted for them."
     *
     * The reason it is the *only* rule here, rather than the last resort it is on the phone, is that a
     * re-fetch to a watch is not an 800 ms stream. It is minutes over Bluetooth and it needs the phone
     * to be in range, awake, and still holding the album. Eviction on a wrist is close to permanent,
     * and something close to permanent must not happen behind the user's back.
     *
     * A refusal is therefore something to *say*, not something to work around: the on-watch screen
     * reports it, and the user removes an album they no longer want - which is the same lever
     * REQUIREMENTS.md leaves the user on a full phone.
     */
    fun canAccept(incomingBytes: Long): Boolean {
        if (incomingBytes <= 0L) return false
        return incomingBytes <= headroomBytes
    }

    companion object {

        /**
         * The least free space a watch is held to: 512 MB.
         *
         * A Wear OS system update is a couple of hundred megabytes, and the platform wants working room
         * beyond it. Half a gigabyte is that with a margin, and it is small enough that a 4 GB watch
         * can still hold a couple of albums - which a 2 GB floor would not allow at all.
         */
        const val MINIMUM_FREE_BYTES: Long = 512L * 1024L * 1024L

        /**
         * The share of the volume the floor scales to once the volume is large enough for it to exceed
         * [MINIMUM_FREE_BYTES], which happens at about 5 GB.
         *
         * Ten percent rather than the phone's two, because a watch's storage is shared with far less
         * and its owner notices a full device through a failed system update rather than through
         * photographs. It is headroom for the platform, not a reserve for Needler.
         */
        const val VOLUME_PERCENT: Int = 10

        /**
         * The floor for a volume of [totalBytes]: the larger of [MINIMUM_FREE_BYTES] and
         * [VOLUME_PERCENT] of it.
         *
         * A total of zero or less answers [MINIMUM_FREE_BYTES] rather than zero, so an unmeasurable
         * volume keeps some protection. Note that an unmeasurable volume is refused outright by
         * [isUnknown] anyway; the floor is still defined for it so that nothing downstream has to
         * handle an absent one.
         *
         * The share is taken as `totalBytes / 100 * VOLUME_PERCENT` rather than
         * `totalBytes * VOLUME_PERCENT / 100`, which would overflow on a large volume. The rounding
         * this loses is at most a couple of bytes - the same arithmetic, and the same reason, as
         * `FreeSpaceFloor.forVolume` on the phone.
         */
        fun floorFor(totalBytes: Long): Long {
            if (totalBytes <= 0L) return MINIMUM_FREE_BYTES
            return maxOf(MINIMUM_FREE_BYTES, totalBytes / 100L * VOLUME_PERCENT)
        }

        /** A volume nothing could be read from. Refuses everything; see [isUnknown]. */
        val Unknown: WearStoreSpace = WearStoreSpace(usableBytes = 0L, totalBytes = 0L)
    }
}
