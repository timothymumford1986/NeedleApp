package app.needler.wear.ui

/**
 * The few numbers the watch has to turn into words.
 *
 * Pure and separate so it can be asserted in a unit test, which is the same reason
 * `feature/library`'s `LibraryFormat` and `:widget`'s `WidgetFormat` exist. The watch's sizes are the
 * only figures in this app that a user makes a decision on - whether an album will fit - so they are
 * worth getting right rather than passing through a platform formatter and hoping.
 */
object WearFormat {

    /**
     * A byte count as the on-watch screen shows it.
     *
     * Decimal units, not binary, and deliberately: Android's own storage settings, and every phone
     * specification anyone has ever read, count a gigabyte as a thousand million bytes. A watch that
     * disagreed with its own system settings screen about how much room it had would be the one place
     * the user could not check the figure.
     *
     * No decimal below a gigabyte. "48 MB" is the number somebody decides on; "48.3 MB" is noise on a
     * screen an inch across. A gigabyte keeps one decimal, because the difference between 1 GB and
     * 1.9 GB is a whole album.
     *
     * A negative figure answers zero rather than a minus sign: it can only come from arithmetic on an
     * unreadable volume, and a negative size on screen reads as a bug rather than as "unknown".
     */
    fun bytes(value: Long): String {
        if (value <= 0L) return "0 MB"
        if (value < MEGABYTE) return "under 1 MB"
        if (value < GIGABYTE) {
            val megabytes: Long = value / MEGABYTE
            return megabytes.toString() + " MB"
        }
        // One decimal, computed in tenths so nothing depends on a locale's floating-point formatting -
        // a watch in a comma-decimal locale must not render "1,2 GB" beside a "1.2 GB" from elsewhere.
        val tenths: Long = value / (GIGABYTE / 10L)
        val whole: Long = tenths / 10L
        val fraction: Long = tenths % 10L
        return whole.toString() + "." + fraction.toString() + " GB"
    }

    /**
     * How much of an album is on the watch, e.g. `9 of 12`.
     *
     * The phone's figure is how many tracks it has downloaded, not how many the album has - see
     * [app.needler.wear.sync.WearOfferedAlbum.trackCount] - so this reads as a share of what is
     * obtainable. Zero obtainable answers the held count alone, which is the state an album is in once
     * the phone has stopped offering it: "3 tracks" is true, where "3 of 0" is nonsense.
     */
    fun trackShare(held: Int, offered: Int): String {
        val safeHeld: Int = held.coerceAtLeast(0)
        if (offered <= 0) {
            return safeHeld.toString() + if (safeHeld == 1) " track" else " tracks"
        }
        return safeHeld.toString() + " of " + offered.toString()
    }

    private const val MEGABYTE: Long = 1_000_000L

    private const val GIGABYTE: Long = 1_000_000_000L
}
