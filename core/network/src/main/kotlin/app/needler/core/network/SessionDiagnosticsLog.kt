package app.needler.core.network

/**
 * Which part of the app wrote one line of the diagnostics log.
 *
 * REQUIREMENTS.md "Observability" names four kinds of content by hand — "request URLs with secrets
 * redacted, status codes, sync summaries and playback errors" — and a reader hunting one of them in
 * four hundred lines needs to be able to tell them apart at a glance. So the source is a field
 * rather than a convention about how each writer phrases its own prefix, and [tag] is fixed at four
 * characters or fewer so the column never shifts.
 *
 * [Network] was the first wired, because [RedactingLogInterceptor] already sees every request on both
 * lanes. [Sync] and [Playback] are wired too: `:core:data` writes one summary per sync pass and one
 * line per outcome of the audio store's write-through path, through a sink interface declared in
 * `:core:domain` and adapted to this buffer by `SessionDiagnosticsSink`. That indirection is not
 * ceremony - this module must stay a leaf, so the modules that produce those lines cannot name this
 * type, and the adapter is the only thing that sees both sides.
 *
 * [App] is declared and not yet written to. It exists so that the next thing worth a line - a session
 * marked stale, an app-password re-minted - has somewhere to write that is already correct about
 * redaction, rather than inventing its own buffer later and getting the redaction question wrong once.
 */
public enum class DiagnosticsSource(public val tag: String) {

    /** An HTTP request on either lane, its status, its size and how long it took. */
    Network("NET"),

    /** What a delta or full sync changed. Written by `:core:data`'s sync repository. */
    Sync("SYNC"),

    /**
     * Playback and the audio store: a dead stream, a decode error, and what the write-through cache
     * did with a track - kept, or declined and for which of the reasons. Written by `:core:data`'s
     * audio store; see `AudioRetentionEvent` in `:core:domain` for the lines.
     */
    Playback("PLAY"),

    /** Everything else worth a line: a session marked stale, an app-password re-minted. */
    App("APP"),
}

/**
 * One line of the diagnostics log, already redacted.
 *
 * "Already redacted" is a property of the type, not a hope about its callers: nothing constructs one
 * of these except [SessionDiagnosticsLog.record], which runs [redactLogLine] over the message first.
 * That is what lets [SessionDiagnosticsLog.render] write the export without a second pass and
 * without a second chance to get the rule wrong.
 *
 * @property epochMillis when the line was written, in wall-clock milliseconds. Kept as a number
 *   rather than formatted at write time because formatting is the reader's problem and an OkHttp
 *   thread should not be doing it.
 * @property level the severity the writer asked for, so the view can raise failures above the
 *   ordinary narrative.
 * @property source who wrote it.
 * @property message the line itself, redacted and length-capped.
 */
public class DiagnosticsEntry internal constructor(
    public val epochMillis: Long,
    public val level: NetworkLogLevel,
    public val source: DiagnosticsSource,
    public val message: String,
) {

    /**
     * `14:03:11.204` — [epochMillis] as UTC time-of-day.
     *
     * UTC, deliberately. A log line must not read a timezone database — it is written on an OkHttp
     * thread and rendered in a list a user is scrolling — and a bug report is read by ordering and by
     * elapsed time between lines rather than by whether something happened at ten past four in the
     * reporter's kitchen. The export header carries the absolute instant once, which is the only
     * place the date actually matters.
     *
     * Public, and the reason is the on-screen view: `:app` draws this column in its own layout rather
     * than parsing it back out of [render], and the alternative was exposing the formatter and
     * letting two modules agree on a format by convention.
     */
    public val clock: String get() = formatUtcTimeOfDay(epochMillis)

    /** `14:03:11.204  W  NET   GET https://…` — one line, fixed columns, for the shared file. */
    public fun render(): String = buildString {
        append(clock)
        append("  ")
        append(level.exportInitial)
        append("  ")
        append(source.tag.padEnd(MAX_TAG_WIDTH))
        append("  ")
        append(message)
    }

    override fun toString(): String = render()

    internal companion object {
        /** `PLAY` is the longest tag, so every tag column is four characters wide. */
        const val MAX_TAG_WIDTH: Int = 4
    }
}

/** `D`, `I`, `W`, `E` — one character, so the severity column cannot shift either. */
internal val NetworkLogLevel.exportInitial: Char
    get() = when (this) {
        NetworkLogLevel.Debug -> 'D'
        NetworkLogLevel.Info -> 'I'
        NetworkLogLevel.Warn -> 'W'
        NetworkLogLevel.Error -> 'E'
    }

/**
 * The local, user-viewable diagnostics log REQUIREMENTS.md "Observability" requires: "A local,
 * user-viewable diagnostics log covering the last session: request URLs with secrets redacted,
 * status codes, sync summaries and playback errors. It must be shareable as a file for bug reports,
 * and it must never leave the device automatically."
 *
 * ## Why a bounded buffer in memory rather than a file
 *
 * "Covering the last session" is the whole specification, and it rules out the obvious
 * implementation. A file that accumulates would keep a record of every server address, every album
 * a user has ever streamed and every failure across months of use, in app-private storage that
 * `android:allowBackup="false"` protects from leaving the device but that nothing prunes. The
 * requirement asks for the last session; a ring buffer *is* the last session, it costs one
 * allocation per line and no I/O at all, and it is erased by the one event that ends a session —
 * the process dying. That also makes "it must never leave the device automatically" true by
 * construction rather than by policy: there is nothing on disk to leak until the user taps share,
 * at which point `:app` writes one file into the single directory the `FileProvider` exposes.
 *
 * The rejected alternative is worth naming precisely, because it was the first design: a rotating
 * file in `filesDir` with a size cap. It buys crash survival — a log that outlives the process that
 * failed — and it costs a write per request on the main path, a second question about when to prune,
 * and a permanent on-disk record of a user's listening. Crash survival is not what the requirement
 * asks for, and this app's failures are overwhelmingly "it will not connect" rather than "it
 * vanished", which the current session answers.
 *
 * ## Why redaction happens here and not only at the call site
 *
 * [RedactingLogInterceptor] already routes every URL through [redactUrl] and logs no request header
 * whatsoever, so the network lane arrives clean. That is not enough. This buffer is also the
 * destination for [DiagnosticsSource.Sync], [DiagnosticsSource.Playback] and
 * [DiagnosticsSource.App] lines written by modules that have never thought about
 * REQUIREMENTS.md "Security" rule 1 — "Credentials in Keystore-backed storage, never in logs,
 * analytics or crash reports" — and a sync summary that helpfully includes the URL it was syncing,
 * or a playback error built from an ExoPlayer `IOException` whose message is the failed request line,
 * carries the Subsonic app-password in plain sight. So [record] redacts unconditionally, on ingest,
 * before anything is stored. A line that never entered the buffer un-redacted cannot leave it
 * un-redacted, in the on-screen view or in the shared file.
 *
 * Redacting on ingest rather than on render is the deliberate half of that. Rendering happens twice
 * — once for the list, once for the export — and a rule applied at two call sites is a rule with two
 * chances of being forgotten when a third view is added.
 *
 * ## Thread safety
 *
 * Written from OkHttp's dispatcher threads, read from the main thread. Every mutation and every read
 * takes one monitor on [lock]; the critical sections are an append and a list copy, so contention is
 * irrelevant beside the socket that produced the line.
 *
 * It is deliberately **not** a `StateFlow`. A flow would recompose a list the user is reading on
 * every request in flight, and a diagnostics log that reorders itself under a scrolling thumb is
 * unreadable. The view takes a [snapshot] and offers a refresh.
 *
 * @param capacity how many lines to keep. The oldest is dropped when the buffer is full, and
 *   [droppedCount] says how many, so a view can tell the reader that the beginning of the session
 *   is gone rather than implying the log starts where it starts.
 * @param clock the source of timestamps, so tests do not have to guess at wall-clock time.
 */
public class SessionDiagnosticsLog(
    public val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) : NetworkLogSink {

    init {
        require(capacity > 0) { "a diagnostics buffer with no room is not a diagnostics buffer" }
    }

    private val lock = Any()

    private val entries = ArrayDeque<DiagnosticsEntry>(minOf(capacity, INITIAL_CAPACITY_CAP))

    private var dropped: Int = 0

    /** How many lines have been pushed out of the far end since the last [clear]. */
    public val droppedCount: Int get() = synchronized(lock) { dropped }

    /** How many lines are held right now. */
    public val size: Int get() = synchronized(lock) { entries.size }

    /**
     * Records one line, redacting it first.
     *
     * The message is capped at [MAX_MESSAGE_CHARS]. One pathological line — a stack trace, a server
     * that answered with a megabyte of HTML in an error message — must not be allowed to evict the
     * rest of the session to hold itself.
     */
    public fun record(source: DiagnosticsSource, level: NetworkLogLevel, message: String) {
        val safe: String = redactLogLine(message).let {
            if (it.length <= MAX_MESSAGE_CHARS) it else it.take(MAX_MESSAGE_CHARS) + "…"
        }
        val entry = DiagnosticsEntry(
            epochMillis = clock(),
            level = level,
            source = source,
            message = safe,
        )
        synchronized(lock) {
            while (entries.size >= capacity) {
                entries.removeFirst()
                dropped++
            }
            entries.addLast(entry)
        }
    }

    /** A [DiagnosticsSource.Network] line at [NetworkLogLevel.Debug]. */
    override fun log(line: String) {
        record(DiagnosticsSource.Network, NetworkLogLevel.Debug, line)
    }

    /** A [DiagnosticsSource.Network] line at the level the network layer asked for. */
    override fun log(level: NetworkLogLevel, line: String) {
        record(DiagnosticsSource.Network, level, line)
    }

    /** Oldest first, copied, so a reader can hold it while the buffer keeps filling. */
    public fun snapshot(): List<DiagnosticsEntry> = synchronized(lock) { entries.toList() }

    /** [snapshot] and [droppedCount] under one lock, for [render]. */
    private fun snapshotWithDropped(): Pair<List<DiagnosticsEntry>, Int> =
        synchronized(lock) { entries.toList() to dropped }

    /**
     * Empties the buffer and forgets how much was dropped.
     *
     * Offered to the user because a log is most useful when it covers one reproduction of one
     * failure rather than everything since launch: clear it, do the thing that breaks, share that.
     */
    public fun clear() {
        synchronized(lock) {
            entries.clear()
            dropped = 0
        }
    }

    /**
     * The whole buffer as the text of the file that gets shared.
     *
     * @param header lines to print above the log — the app version, the device, the Android level.
     *   Supplied by the caller rather than read here because this module has no business touching
     *   `Build` or `PackageManager`, and because a test wants a header it chose.
     */
    public fun render(header: List<String> = emptyList()): String {
        // One lock acquisition for both figures, so the count in the header cannot disagree with the
        // lines beneath it when a request lands mid-render. Returned as a pair rather than assigned
        // into two locals from inside the critical section, because assigning a captured `val` inside
        // a lambda is not something to rely on the compiler permitting.
        val (lines: List<DiagnosticsEntry>, lost: Int) = snapshotWithDropped()
        return buildString {
            appendLine(EXPORT_TITLE)
            header.forEach { appendLine(it) }
            appendLine()
            appendLine(EXPORT_PREAMBLE)
            appendLine()
            append(lines.size)
            append(if (lines.size == 1) " line" else " lines")
            if (lost > 0) {
                append(", ")
                append(lost)
                append(" older ")
                append(if (lost == 1) "line" else "lines")
                append(" dropped to keep the buffer bounded")
            }
            appendLine()
            appendLine(EXPORT_RULE)
            if (lines.isEmpty()) {
                appendLine("Nothing was logged this session.")
            } else {
                lines.forEach { appendLine(it.render()) }
            }
        }
    }

    override fun toString(): String = "SessionDiagnosticsLog(" + size + "/" + capacity + ")"

    public companion object {

        /**
         * 500 lines.
         *
         * Sized against the thing it has to be able to explain. A cold start plus a delta sync plus
         * browsing to an album and playing it is on the order of forty requests; five hundred lines
         * therefore holds several minutes of hard use, or one reproduction of a failure with plenty
         * of room either side of it. At roughly 200 characters a line that is about 100 KB held for
         * the life of the process, which is less than one album cover.
         */
        public const val DEFAULT_CAPACITY: Int = 500

        /**
         * The ceiling on one line.
         *
         * Long enough for the longest redacted URL this app builds plus its status, size, duration
         * and the allow-listed response headers, with room to spare for a sync summary that names
         * counts. Anything longer is a stack trace or a server's HTML, neither of which belongs in a
         * line-oriented log.
         */
        public const val MAX_MESSAGE_CHARS: Int = 1_000

        /** Pre-allocating five hundred slots for a buffer that may never fill is pointless. */
        private const val INITIAL_CAPACITY_CAP: Int = 64

        private const val EXPORT_TITLE: String = "Needler diagnostics — this session"

        /**
         * Printed into the file itself, because the file is the thing that gets attached to a
         * GitHub issue and read by someone who has no idea what was taken out of it.
         */
        private const val EXPORT_PREAMBLE: String =
            "Secrets are removed before a line is stored, not before it is written out: the " +
                "Subsonic app-password travels as an apiKey query parameter and the companion " +
                "bearer travels in a request header, so query values that name a credential read " +
                "REDACTED and no request header is recorded at all. Request and response bodies " +
                "are never logged. Nothing in this file left the device until you shared it."

        private const val EXPORT_RULE: String =
            "----------------------------------------------------------------------"
    }
}

private const val MILLIS_PER_DAY: Long = 86_400_000L

/**
 * UTC time-of-day from epoch milliseconds, with no timezone database read and no date library.
 *
 * `internal` and tested directly, because the alternative — asserting on a whole rendered line — is
 * the kind of test that fails for the wrong reason the first time a column moves.
 */
internal fun formatUtcTimeOfDay(epochMillis: Long): String {
    val millisOfDay: Long = ((epochMillis % MILLIS_PER_DAY) + MILLIS_PER_DAY) % MILLIS_PER_DAY
    val hours: Long = millisOfDay / 3_600_000L
    val minutes: Long = (millisOfDay / 60_000L) % 60L
    val seconds: Long = (millisOfDay / 1_000L) % 60L
    val millis: Long = millisOfDay % 1_000L
    return buildString(12) {
        appendPadded(hours, 2)
        append(':')
        appendPadded(minutes, 2)
        append(':')
        appendPadded(seconds, 2)
        append('.')
        appendPadded(millis, 3)
    }
}

private fun StringBuilder.appendPadded(value: Long, width: Int) {
    val text: String = value.toString()
    repeat(width - text.length) { append('0') }
    append(text)
}
