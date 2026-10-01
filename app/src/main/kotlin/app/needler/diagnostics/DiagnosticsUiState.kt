package app.needler.diagnostics

import android.net.Uri
import app.needler.core.network.DiagnosticsSource
import app.needler.core.network.NetworkLogLevel

/**
 * Everything the Diagnostics screen renders.
 *
 * REQUIREMENTS.md "Observability", in full: "A local, user-viewable diagnostics log covering the last
 * session: request URLs with secrets redacted, status codes, sync summaries and playback errors. It
 * must be shareable as a file for bug reports, and it must never leave the device automatically."
 *
 * That sentence names four things this type has to be able to say, and one it has to make impossible.
 *
 * ## Why the lines are already strings
 *
 * The buffer holds `app.needler.core.network.DiagnosticsEntry`, and this state holds
 * [DiagnosticsLine], which is that entry with its clock formatted, its severity turned into a word
 * and its source turned into a label. The mapping happens once, in [diagnosticsLine], rather than in
 * the composable — so the whole presentation of a log line is a pure function that a unit test can
 * assert on, and so a list the user is scrolling is not re-formatting four hundred timestamps on
 * every frame.
 *
 * ## Why the screen is stateless and this type is a snapshot
 *
 * The buffer is written from OkHttp's threads while the user reads it. Exposing it as a `Flow` would
 * mean the list reordered and re-measured on every request in flight, under a scrolling thumb, which
 * is the one thing that would make a four-hundred-line log unreadable. So this is a snapshot with a
 * [stale] flag and a refresh action, which is also the honest shape: a log is a record of what
 * happened, not a live view.
 *
 * ## Why sharing is a request rather than an action
 *
 * [pendingShare] is set when the file has been written and the chooser has not yet been opened, and
 * the screen consumes it and clears it. The `ViewModel` does not launch the intent itself, because
 * starting an activity needs an `Activity` context and a `ViewModel` only has the application's —
 * which would work, with `FLAG_ACTIVITY_NEW_TASK`, and would put the chooser in its own task instead
 * of over Needler. Reporting it upwards is the same shape `SettingsUiState.signedOut` already uses
 * for the same reason: the thing that owns a window does the thing that needs a window.
 *
 * There is deliberately no automatic upload, no crash reporter and no "send to developer" button.
 * REQUIREMENTS.md: "it must never leave the device automatically", and REQUIREMENTS.md "Security"
 * rule 4 forbids "third-party analytics or crash reporting that transmits server URLs or library
 * contents" outright. A share sheet the user opened is the only exit this log has.
 */
data class DiagnosticsUiState(
    /** Oldest first, as the session happened. */
    val lines: List<DiagnosticsLine> = emptyList(),

    /**
     * How many lines fell off the front of the buffer.
     *
     * Rendered, always, when it is non-zero. A log that silently begins in the middle of a session
     * invites the reader to conclude that nothing happened before its first line, which on a long
     * session is exactly wrong.
     */
    val droppedCount: Int = 0,

    /** How many lines the buffer holds at most, so the dropped-lines notice can explain itself. */
    val capacity: Int = 0,

    /** True while the export is being written. Two taps on share must not write two files. */
    val exporting: Boolean = false,

    /** What the last action did, or why it could not. Replaced by the next one. */
    val notice: String? = null,

    /**
     * A written export waiting for the screen to open a chooser for it.
     *
     * Consumed exactly once: the screen calls `onShareHandled` after launching, and a configuration
     * change mid-share therefore re-launches nothing.
     */
    val pendingShare: DiagnosticsShare? = null,
) {

    /** Nothing has been logged yet - a fresh process, or the user has just cleared it. */
    val isEmpty: Boolean get() = lines.isEmpty()

    /** There is something to share and something to clear. */
    val hasContent: Boolean get() = lines.isNotEmpty()

    /**
     * The line under the title: how much is held, and how much was lost.
     *
     * One sentence rather than a count badge, because both halves need explaining and neither is a
     * number the reader came for.
     */
    val summary: String
        get() = when {
            lines.isEmpty() -> "Nothing has been logged yet this session."
            droppedCount > 0 ->
                plural(lines.size, "line") + " from this session, and " +
                    plural(droppedCount, "line") + " dropped from the start of it: the log keeps " +
                    "the most recent " + capacity + " and no more."

            else -> plural(lines.size, "line") + " from this session."
        }
}

/**
 * One line of the log, formatted for the screen.
 *
 * @property key stable identity for the lazy list. The message is not unique — a log is full of
 *   repeated lines — so the key is the position and the timestamp together.
 * @property clock `14:03:11.204`, UTC. See `DiagnosticsEntry.clock` for why UTC.
 * @property source `NET`, `SYNC`, `PLAY`, `APP`.
 * @property badge `WARN` or `ERROR`, or null for the ordinary narrative.
 * @property message the line itself, redacted before it ever reached the buffer.
 * @property emphasised whether to draw it in the primary text colour rather than the muted one.
 *   Never a colour on its own: [badge] carries the same information as a word, because
 *   REQUIREMENTS.md "Accessibility" and the palette's own constraints leave no safe colour for this
 *   — the destructive red is reserved for data loss, and a log line is not data loss.
 */
data class DiagnosticsLine(
    val key: String,
    val clock: String,
    val source: String,
    val badge: String?,
    val message: String,
    val emphasised: Boolean,
) {

    /**
     * How a screen reader announces it.
     *
     * The clock comes last. A screen-reader user scanning a log wants the failure, not the
     * millisecond it happened at, and TalkBack reads a node from the beginning every time focus lands
     * on it.
     */
    val spoken: String
        get() = buildString {
            if (badge != null) {
                append(badge.lowercase())
                append(", ")
            }
            append(sourceSpoken)
            append(": ")
            append(message)
            append(", at ")
            append(clock)
        }

    private val sourceSpoken: String
        get() = when (source) {
            "NET" -> "network"
            "SYNC" -> "sync"
            "PLAY" -> "playback"
            else -> "app"
        }
}

/**
 * A written export, and where it can be read from.
 *
 * @property uri a `content://` URI from the `FileProvider` declared under the authority
 *   `${applicationId}.diagnostics`. Not a `file://` path: handing one to another app has been illegal
 *   since Android 7, and the provider is narrowed to one directory precisely so that granting a read
 *   on this file grants nothing else — see `res/xml/diagnostics_paths.xml`.
 * @property fileName what the receiving app will call it, for the notice line.
 * @property byteCount how big it is, so the notice can say so rather than claiming success abstractly.
 */
data class DiagnosticsShare(
    val uri: Uri,
    val fileName: String,
    val byteCount: Long,
)

/**
 * Turns one buffer entry's parts into a [DiagnosticsLine].
 *
 * Takes the entry's components rather than the entry, for one reason: `DiagnosticsEntry`'s constructor
 * is `internal` to `:core:network` — nothing outside that module may fabricate a log line, which is
 * the right rule — so a test in `:app` cannot build one. Passing the parts keeps the whole of this
 * screen's presentation logic testable on a JVM with no Android, no Hilt and no HTTP client, which is
 * the same trade `SettingsUiState` makes by being a literal.
 */
internal fun diagnosticsLine(
    index: Int,
    clock: String,
    level: NetworkLogLevel,
    source: DiagnosticsSource,
    message: String,
): DiagnosticsLine = DiagnosticsLine(
    key = index.toString() + "@" + clock,
    clock = clock,
    source = source.tag,
    badge = when (level) {
        NetworkLogLevel.Debug, NetworkLogLevel.Info -> null
        NetworkLogLevel.Warn -> "WARN"
        NetworkLogLevel.Error -> "ERROR"
    },
    message = message,
    emphasised = level == NetworkLogLevel.Warn || level == NetworkLogLevel.Error,
)

/**
 * `1 line` / `4 lines`.
 *
 * Local rather than `SettingsFormat.plural`, which takes a `Long` because every figure on the Settings
 * screen is a byte count or an album count from a database. A line count is an `Int` and converting one
 * to reuse three lines of string concatenation is not a saving.
 */
private fun plural(count: Int, noun: String): String =
    count.toString() + " " + noun + (if (count == 1) "" else "s")
