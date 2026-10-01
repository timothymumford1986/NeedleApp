package app.needler.core.domain.diagnostics

/**
 * Byte counts and durations, rendered for a log line a person reads.
 *
 * ## Why not reuse the Settings screen's formatter
 *
 * `:app`'s `SettingsFormat` renders the same two kinds of figure and cannot be called from here:
 * `:core:domain` sits below every UI module, which is the point of it. Duplicating a handful of
 * arithmetic is the cheaper half of that trade, and the two are allowed to diverge - a log line wants
 * `5.1 GB` beside a track key on one fixed-width row, while the Storage section wants a figure that
 * lines up with the one above it.
 *
 * ## Why no `String.format`
 *
 * `String.format("%.1f", …)` reads the default locale, so on a device set to most of Europe the same
 * code writes `5,1 GB`. A diagnostics file is grepped and quoted in bug reports, so its numbers must
 * not depend on where the reporter lives. The arithmetic below is locale-free by construction.
 */
public object DiagnosticsFormat {

    /**
     * `953 B`, `4.7 MB`, `5.1 GB` - one decimal place from a kibibyte upwards.
     *
     * Kibibytes, not kilobytes, because every other size in this app - the free-space floor, the
     * volume figures `StatFs` reports, the sizes the Storage section shows - is a power of two, and a
     * log line that disagreed with the screen by 7% would be read as a second bug.
     *
     * A negative value renders as `unknown`, which is how `FreeSpaceFloor.UNKNOWN` arrives: a failed
     * free-space reading is a fact worth printing rather than a number to invent.
     */
    public fun bytes(value: Long): String {
        if (value < 0L) return "unknown"
        if (value < UNIT) return value.toString() + " B"

        // The largest unit this value reaches, so 4 700 000 reads as MB rather than as 4590 KB.
        var divisor: Long = UNIT
        var index = 0
        while (index < UNITS.lastIndex && value >= divisor * UNIT) {
            divisor *= UNIT
            index += 1
        }

        // Whole and tenths are taken separately, from the remainder, rather than from `value * 10`:
        // the latter overflows a Long above about 900 PB, and a formatter that is wrong only on
        // absurd input is a formatter nobody checks.
        var whole: Long = value / divisor
        var tenths: Long = ((value % divisor) * 10L + divisor / 2L) / divisor
        if (tenths >= 10L) {
            whole += 1L
            tenths = 0L
        }
        return whole.toString() + "." + tenths.toString() + " " + UNITS[index]
    }

    /**
     * `112 ms`, `2.4 s`, `3m 12s` - the three scales a sync actually lands on.
     *
     * A delta against an unchanged library is the performance budget REQUIREMENTS.md writes in
     * milliseconds, so milliseconds are printed exactly; a full sync of a real library takes minutes,
     * and `194000 ms` is not a number anyone reads correctly at a glance.
     */
    public fun duration(millis: Long): String {
        if (millis < 0L) return "unknown"
        if (millis < MILLIS_PER_SECOND) return millis.toString() + " ms"
        if (millis < MILLIS_PER_MINUTE) {
            val tenths: Long = (millis + 50L) / 100L
            return (tenths / 10L).toString() + "." + (tenths % 10L).toString() + " s"
        }
        val minutes: Long = millis / MILLIS_PER_MINUTE
        val seconds: Long = (millis % MILLIS_PER_MINUTE) / MILLIS_PER_SECOND
        return minutes.toString() + "m " + seconds.toString() + "s"
    }

    /** `1 album` / `4 albums` - the noun stays singular when the count is one. */
    public fun plural(count: Int, noun: String): String =
        count.toString() + " " + noun + (if (count == 1) "" else "s")

    private const val UNIT: Long = 1_024L

    private val UNITS: List<String> = listOf("KB", "MB", "GB", "TB")

    private const val MILLIS_PER_SECOND: Long = 1_000L

    private const val MILLIS_PER_MINUTE: Long = 60_000L
}
