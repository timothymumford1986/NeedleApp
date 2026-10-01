package app.needler.core.domain.diagnostics

/**
 * How serious one diagnostic line is, in the vocabulary the domain owns.
 *
 * It is a deliberate near-duplicate of `:core:network`'s `NetworkLogLevel`, and the duplication is
 * forced by the one architectural rule this module cannot bend: `:core:domain` is a pure
 * Kotlin/JVM library and `:core:network` is not on its compile classpath at all. A shared enum
 * would either drag the transport layer into the domain or invert the module graph, and the whole
 * point of `:core:network` being a leaf is that neither happens. So the domain names the four
 * severities it needs, and the one adapter that owns both sides translates - see
 * `SessionDiagnosticsSink` in `:core:data`, where the mapping is four lines in one place rather
 * than a level argument every call site has to get right.
 *
 * The rejected alternative was four methods on [DiagnosticsSink] - `debug`, `info`, `warn`, `error`
 * - which needs no enum and cannot be a `fun interface`. Every test double would then implement
 * four members to exercise one, which is exactly the friction that makes a test write to nothing
 * instead.
 */
public enum class DiagnosticsLevel {

    /** The ordinary narrative: a track cached, a delta sync that found nothing. */
    Debug,

    /** Worth seeing in a bug report even though nothing failed - a track retained, a sync's counts. */
    Info,

    /**
     * Something the user would want explained: a stream not retained for a reason that is not simply
     * policy, a sync that failed.
     */
    Warn,

    /** A failure with no recovery in this layer. */
    Error,
}

/**
 * Where a line of REQUIREMENTS.md "Observability" narrative goes, as far as the domain is concerned.
 *
 * ## Why this interface exists at all
 *
 * REQUIREMENTS.md "Observability" asks for "a local, user-viewable diagnostics log covering the last
 * session: request URLs with secrets redacted, status codes, sync summaries and playback errors".
 * The buffer that holds it is `SessionDiagnosticsLog` in `:core:network`, because that is where the
 * request lane already writes and where redaction is applied on ingest. Two of the four kinds of
 * content it is required to carry - sync summaries, and the reasons the audio store declined to keep
 * a stream - are produced in `:core:data` by classes whose interfaces are declared here. Those
 * classes cannot name the buffer's type: `:core:domain` has no dependency on `:core:network` by
 * design, and adding one would put the wire layer underneath the model.
 *
 * So the domain declares the seam and something else supplies the implementation, the same shape and
 * for the same reason as `CredentialProvider`, which `:core:network` declares and `:core:data`
 * implements so that the network module stays a leaf.
 *
 * ## Why silence is the default
 *
 * [None] drops everything, and every class that takes one of these defaults to it. A unit test that
 * says nothing about diagnostics gets a sink that does nothing, and a production wiring that forgets
 * one loses lines rather than crashing. That is the same trade `NetworkLogSink.None` makes, and it is
 * the right direction here for an additional reason: a diagnostics write must never be able to fail
 * the thing it is describing. The audio store's whole contract is that a cache write cannot disturb
 * the playback it rides along with (see `AudioCacheWriter`), and a sink that could throw would put
 * that back in question.
 *
 * Implementations must be cheap and thread-safe. They are called from a Media3 loading thread, from
 * a sync coroutine and from OkHttp's dispatcher, and they are called while a lock on the write handle
 * is held.
 */
public fun interface DiagnosticsSink {

    /**
     * Writes one line.
     *
     * The message must already be worth reading on its own: this log is a flat, line-oriented file
     * that a user attaches to a bug report, so a line that only makes sense next to the one above it
     * is a line that will be read alone and misunderstood.
     *
     * Callers do **not** redact. `SessionDiagnosticsLog.record` runs the redaction rule over every
     * line on ingest, which is what lets a sync summary built from a `NeedlerError` whose message is
     * a failed request line be written here without every call site having to think about the
     * Subsonic app-password in the query string.
     */
    public fun record(level: DiagnosticsLevel, message: String)

    public companion object {

        /** Drops everything. The default everywhere one of these is a constructor parameter. */
        public val None: DiagnosticsSink = object : DiagnosticsSink {
            override fun record(level: DiagnosticsLevel, message: String): Unit = Unit
            override fun toString(): String = "DiagnosticsSink.None"
        }
    }
}
