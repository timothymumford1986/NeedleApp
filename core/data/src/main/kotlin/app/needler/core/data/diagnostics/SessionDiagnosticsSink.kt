package app.needler.core.data.diagnostics

import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.network.DiagnosticsSource
import app.needler.core.network.NetworkDiagnostics
import app.needler.core.network.NetworkLogLevel
import app.needler.core.network.SessionDiagnosticsLog

/**
 * The one place a domain-shaped diagnostics line becomes a line in the session log.
 *
 * ## Why the adapter is here and not at either end
 *
 * `SessionDiagnosticsLog` lives in `:core:network`, because that is where the request lane already
 * writes and where redaction is applied on ingest. `DiagnosticsSink` is declared in `:core:domain`,
 * because the classes that need to write to it - the audio store and the sync repository - implement
 * interfaces declared there and must not name a transport type. Neither module may depend on the
 * other: `:core:network` is a leaf by design and `:core:domain` is a pure Kotlin/JVM library, and
 * those two rules are what keep the wire model and the domain model from leaking into each other.
 *
 * `:core:data` is the only module that already sees both, which is what makes it the place this
 * translation belongs. It is the same arrangement, for the same reason, as `CredentialProvider`:
 * declared in `:core:network` and implemented here by `SecureCredentialStore`, so that the network
 * module needs no dependency on the thing that holds the secret.
 *
 * ## Why the source is fixed per instance
 *
 * [DiagnosticsSource] is what makes a five-hundred-line file readable - `NET`, `SYNC`, `PLAY`, `APP`
 * in a fixed column - and it is a property of *who is writing*, not of any individual line. So it is
 * a constructor argument and every line from one instance carries the same tag. The alternative was a
 * source argument on [DiagnosticsSink.record], which would have put a `:core:network` enum in a
 * `:core:domain` signature and asked every call site to classify itself correctly.
 *
 * ## Why this does not consult `NetworkDiagnostics.isEnabled`
 *
 * It would be easy to drop every line unless a sink has been installed, matching the promise that
 * `NetworkDiagnostics.sessionLog` "receives nothing until a sink is installed" and keeping unit tests
 * from writing into a process-wide buffer. It is deliberately not done. That gate exists so that a
 * forgotten wiring cannot leak a credential to logcat; applied here it would mean a forgotten wiring
 * silently discards the refusal reasons this class exists to record - which is precisely the failure
 * being fixed, reintroduced one layer down. The buffer is handed in instead, so a test that wants to
 * assert on lines passes its own and a test that does not care passes [DiagnosticsSink.None] and this
 * class is never constructed at all.
 *
 * @param log the buffer to write into. [forPlayback] and [forSync] supply the process-wide one.
 * @param source the column every line from this instance is filed under.
 */
public class SessionDiagnosticsSink(
    private val log: SessionDiagnosticsLog,
    private val source: DiagnosticsSource,
) : DiagnosticsSink {

    /**
     * Writes one line, redacted by the buffer on the way in.
     *
     * Nothing is caught here. `SessionDiagnosticsLog.record` is an append to an `ArrayDeque` under a
     * monitor and a few regex passes; it has no I/O and nothing to fail on, so a `try` around it would
     * only hide a programming error in the buffer itself.
     */
    override fun record(level: DiagnosticsLevel, message: String) {
        log.record(source = source, level = levelOf(level), message = message)
    }

    override fun toString(): String = "SessionDiagnosticsSink(" + source.tag + ")"

    public companion object {

        /**
         * Lines about playback and the audio store, filed as `PLAY`.
         *
         * Reads the process-wide buffer rather than taking one from the dependency graph, for the same
         * reason `DiagnosticsViewModel` does: it is an `object` that starts working in
         * `NeedlerApplication.onCreate`, before anything has asked Hilt for anything, and the first
         * requests of a cold start are the ones a bug report most often needs.
         */
        public fun forPlayback(): SessionDiagnosticsSink = SessionDiagnosticsSink(
            log = NetworkDiagnostics.sessionLog,
            source = DiagnosticsSource.Playback,
        )

        /** Sync summaries, filed as `SYNC`. */
        public fun forSync(): SessionDiagnosticsSink = SessionDiagnosticsSink(
            log = NetworkDiagnostics.sessionLog,
            source = DiagnosticsSource.Sync,
        )

        /**
         * The whole of the translation between the two level vocabularies.
         *
         * `internal` and named rather than inlined into [record], because it is the one piece of this
         * class with a right and a wrong answer and a test should be able to say so directly.
         */
        internal fun levelOf(level: DiagnosticsLevel): NetworkLogLevel = when (level) {
            DiagnosticsLevel.Debug -> NetworkLogLevel.Debug
            DiagnosticsLevel.Info -> NetworkLogLevel.Info
            DiagnosticsLevel.Warn -> NetworkLogLevel.Warn
            DiagnosticsLevel.Error -> NetworkLogLevel.Error
        }
    }
}
