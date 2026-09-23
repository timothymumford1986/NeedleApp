// kotlinx.datetime.Instant became a deprecated typealias for kotlin.time.Instant in
// kotlinx-datetime 0.7.0, so every Instant that reaches this screen from :core:domain is really
// the stdlib one. Opting in here costs a warning if the marker is ever dropped, and avoids a
// build break while it is still there.
@file:OptIn(ExperimentalTime::class)

package app.needler.settings

import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Every string the Settings screen renders from a number.
 *
 * These are pure functions for the same reason `LibraryFormat` in `:feature:library` is: formatting
 * is where a screen quietly lies, and a pure function is the part of a screen that can be tested
 * without rendering anything at all. They are duplicated here rather than shared because
 * `LibraryFormat` is `internal` to its own module and this one is in `:app`; the two are small,
 * and a `:core:design` formatting object is the right place for them if a third caller appears.
 *
 * Three rules run through all of them, and each is a lesson REQUIREMENTS.md draws explicitly:
 *
 *  * **Unknown is not zero.** REQUIREMENTS.md makes the point about the staleness check - "Unknown
 *    is not evidence" - and it is just as true of a storage line. A library that has never synced
 *    reads "Never", not "just now".
 *  * **A size the user is about to act on must be honest.** REQUIREMENTS.md: "a 'remove' that
 *    leaves the usage figure unchanged is the one thing that would make this whole screen
 *    untrustworthy". So a figure under a kilobyte reads in bytes rather than rounding down to
 *    "0 KB", which would let a removal claim to free nothing.
 *  * **Coarse is kinder than precise.** "Last synced" exists to reassure, not to measure, so it
 *    steps in minutes, hours and days and never ticks.
 */
internal object SettingsFormat {

    // ---- sizes --------------------------------------------------------------

    /**
     * `2.1 GB`, the figure screen 12 draws against "Music kept on device".
     *
     * Binary units under decimal names, which is what Android's own storage screen does and
     * therefore what a user comparing the two numbers will expect. One decimal below ten of a unit
     * and none above it: `1.4 GB` is a useful distinction and `42.3 GB` is noise.
     *
     * A negative count - which no caller should produce, but which a miscounted cache index could -
     * reads as `0 B` rather than as a negative size.
     */
    fun bytes(byteCount: Long): String {
        if (byteCount <= 0L) return "0 B"
        if (byteCount < UNIT) return byteCount.toString() + " B"
        var scaled: Double = byteCount.toDouble()
        var unitIndex = -1
        while (scaled >= UNIT && unitIndex < UNITS.lastIndex) {
            scaled /= UNIT
            unitIndex++
        }
        val unit: String = UNITS[unitIndex]
        return if (scaled < 10.0) {
            val tenths: Int = (scaled * 10.0).roundToInt()
            (tenths / 10).toString() + "." + (tenths % 10) + " " + unit
        } else {
            scaled.roundToInt().toString() + " " + unit
        }
    }

    // ---- relative time ------------------------------------------------------

    /**
     * `2 min ago`, exactly as screen 12 draws it against "Last synced".
     *
     * Deliberately not the `47m ago` form the library header uses on screens 09 and 13: the pack
     * spells this one out, and Settings has the room. Both are coarse for the same reason - a
     * ticking "2 min 14 s" would redraw the row every second for nobody's benefit.
     *
     * Returns null for an unknown time, which the caller renders as "Never". A mirror that has
     * never synced has no sync time, and saying "just now" would be a lie about the one figure on
     * this screen the user is most likely to check after a failed connection.
     */
    fun relativeTime(then: Instant?, now: Instant): String? {
        val at: Instant = then ?: return null
        val seconds: Long = (now - at).inWholeSeconds
        return when {
            // A clock that moved backwards - a manual time change, a device that resynced NTP - is
            // not a reason to render a negative age at the user.
            seconds < 60L -> "just now"
            // "min" and "hr" are abbreviations and do not take an s, which is also how the pack
            // writes them: screen 12 draws "2 min ago", not "2 mins ago".
            seconds < 3_600L -> (seconds / 60L).toString() + " min ago"
            seconds < 86_400L -> (seconds / 3_600L).toString() + " hr ago"
            seconds < 2_592_000L -> plural(seconds / 86_400L, "day") + " ago"
            else -> "over a month ago"
        }
    }

    // ---- words --------------------------------------------------------------

    /** `1 album` / `12 albums`. */
    fun plural(count: Long, noun: String): String =
        if (count == 1L) "$count $noun" else "$count ${noun}s"

    /**
     * `ListenBrainz`, or `ListenBrainz and Last.fm`.
     *
     * Used for the scrobble row's label. REQUIREMENTS.md: the toggle "must be labelled from this
     * rather than hard-coding a destination as screen 12 does", and the server can have both
     * targets configured at once.
     */
    fun andList(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(separator = ", ") + " and " + items.last()
    }

    private const val UNIT: Long = 1_024L
    private val UNITS: List<String> = listOf("KB", "MB", "GB", "TB", "PB")
}
