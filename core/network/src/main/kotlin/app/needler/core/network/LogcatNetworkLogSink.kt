package app.needler.core.network

import android.util.Log

/**
 * The [NetworkLogSink] that finally puts something in `adb logcat`.
 *
 * ## Why this class did not exist before
 *
 * Everything else was already here. [RedactingLogInterceptor] was built, wired into
 * [NeedlerHttpClient], and correct; [redactUrl] was written and tested. The only implementation of
 * [NetworkLogSink] anywhere in the project, though, was [NetworkLogSink.None], and the one place
 * that constructs a [NeedlerHttpClient] in the running app took the default argument. So the
 * interceptor ran on every call, took the `sink === None` short circuit on its first line, and
 * dropped the lot. A `logcat -d` filtered to the app's PID for a whole session produced framework
 * noise and not one line of ours, and two real bugs — offline downloads that retain nothing, and
 * every `GET /api/v1/artists/{mbid}/releases` failing — could not be told apart from each other,
 * let alone diagnosed. That is what this file fixes.
 *
 * ## Severity
 *
 * [NetworkLogLevel] maps onto Android's priorities so that a developer can narrow the stream:
 *
 * ```
 * adb logcat -s NeedlerNet          # everything this module writes
 * adb logcat NeedlerNet:W '*:S'     # only the calls that produced a failure
 * ```
 *
 * ## Release builds
 *
 * This class does not decide whether it runs; [NetworkLogSink.forApplication] does, and it hands
 * back [NetworkLogSink.None] for any process without `FLAG_DEBUGGABLE`. Nothing here consults a
 * build flag itself, because a sink that decided for itself would be a second place to get the
 * answer wrong.
 *
 * ## What it deliberately does not do
 *
 * No framework, no dependency, no buffering, no background thread, no rate limiting, no
 * `Log.isLoggable` gate. `Log.println` is already a cheap write to a kernel ring buffer, the
 * caller has already decided the line is worth writing, and an `isLoggable` gate would have made
 * the default behaviour "silent until you set a system property", which is how this module came to
 * be silent in the first place.
 */
public class LogcatNetworkLogSink(
    private val tag: String = NetworkLog.TAG,
) : NetworkLogSink {

    /**
     * Latched false the first time `android.util.Log` refuses to work.
     *
     * On a device it never will. This is for the JVM unit-test classpath, where `android.util.Log`
     * is a stub: this project sets `unitTests.isReturnDefaultValues = true` in its library
     * convention plugin so the stub returns `0` rather than throwing, but that is a build setting
     * one module could stop setting, and the consequence of being wrong would be every test in
     * that module failing inside a log call. Latching on the first `RuntimeException` costs one
     * volatile read per line and makes this class impossible to break a test with.
     */
    @Volatile
    private var logcatUsable: Boolean = true

    override fun log(line: String) {
        log(NetworkLogLevel.Debug, line)
    }

    override fun log(level: NetworkLogLevel, line: String) {
        if (!logcatUsable) return
        val priority = when (level) {
            NetworkLogLevel.Debug -> Log.DEBUG
            NetworkLogLevel.Info -> Log.INFO
            NetworkLogLevel.Warn -> Log.WARN
            NetworkLogLevel.Error -> Log.ERROR
        }
        try {
            // logcat discards an entry longer than about 4 KiB, silently, which would turn a long
            // redacted URL into no line at all rather than a truncated one. Splitting is cheap and
            // the alternative is losing exactly the noisiest, most interesting lines.
            if (line.length <= MAX_ENTRY_CHARS) {
                Log.println(priority, tag, line)
                return
            }
            var start = 0
            while (start < line.length) {
                val end = minOf(start + MAX_ENTRY_CHARS, line.length)
                Log.println(priority, tag, line.substring(start, end))
                start = end
            }
        } catch (unavailable: RuntimeException) {
            logcatUsable = false
        }
    }

    override fun toString(): String = "LogcatNetworkLogSink(" + tag + ")"

    private companion object {
        /** Comfortably under logcat's per-entry ceiling once the tag and priority are added. */
        const val MAX_ENTRY_CHARS = 3_500
    }
}
