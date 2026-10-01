package app.needler.core.network

import android.content.Context
import android.content.pm.ApplicationInfo
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * How serious one diagnostic line is.
 *
 * The levels exist so that a logcat sink can map them onto Android's priorities and a developer
 * can ask for `adb logcat NeedlerNet:W *:S` and see only the things that went wrong. A sink with no
 * notion of severity may ignore the level entirely, which is why [NetworkLogSink] keeps a
 * one-argument `log` as its single abstract member and treats the level as an optional refinement
 * rather than a second required method. [SessionDiagnosticsLog] does express it, both to colour the
 * on-screen view and to let a reader of a shared file find the four lines that mattered.
 *
 * There is deliberately no `Verbose`. Everything this module would have written at that level is
 * either a body (never logged at all) or a per-chunk event on the download path, and a level whose
 * only use is to guard things nobody may log is a level that will eventually be used for one.
 */
public enum class NetworkLogLevel {
    /** The ordinary narrative: a request, its status, how long it took. */
    Debug,

    /** Worth seeing in a bug report even though nothing failed — a finished download. */
    Info,

    /** A call produced a [NetworkError] the caller has to handle. */
    Warn,

    /** The transport died, or the answer did not come from DroppedNeedle at all. */
    Error,
}

/**
 * Where this module's request log goes. [SessionDiagnosticsLog] implements it as the local,
 * user-viewable diagnostics log required by REQUIREMENTS.md §Observability ("request URLs with
 * secrets redacted, status codes … never leaves the device automatically"), and
 * [LogcatNetworkLogSink] implements it over `android.util.Log` for a developer with a cable
 * attached. On a debuggable build both are installed at once — see [forApplication].
 *
 * Implementations must be cheap and thread-safe; they are called on OkHttp's threads.
 *
 * Nothing that reaches a sink has been near a credential: [redactUrl] is the URL rule,
 * [RedactingLogInterceptor] is the header rule (no request header is logged, at all), and
 * [redactParseMessage] closes the one place a server's bytes could otherwise have leaked back out
 * through an exception message. [redactLogLine] is the belt-and-braces rule applied to arbitrary
 * text by the buffer that the user can read and share, for lines written by code that has not
 * thought about any of the above.
 */
public fun interface NetworkLogSink {

    /** Write one line. Implementations must not block. */
    public fun log(line: String)

    /**
     * Write one line at a known severity. The default discards the level, so a sink written as a
     * lambda keeps working unchanged; a sink that can express severity overrides this, and its
     * one-argument [log] then becomes the fallback rather than the other way round.
     */
    public fun log(level: NetworkLogLevel, line: String) {
        log(line)
    }

    public companion object {

        /** Drops everything. The process default, and what every unit test in this project sees. */
        public val None: NetworkLogSink = object : NetworkLogSink {
            override fun log(line: String): Unit = Unit
            override fun log(level: NetworkLogLevel, line: String): Unit = Unit
            override fun toString(): String = "NetworkLogSink.None"
        }

        /**
         * A sink that forwards to whatever [NetworkDiagnostics] currently holds, looked up per
         * line rather than captured once.
         *
         * This indirection is the reason logging can be switched on in this app at all.
         * [NeedlerHttpClient] is an `@Singleton` that Hilt builds while injecting fields into
         * `NeedlerApplication`, which happens inside `super.onCreate()` — *before* the
         * application's own `onCreate` body runs. A sink captured at construction time could
         * therefore never be installed by the application itself, only by the Hilt module that
         * builds the client. Reading the sink per line removes the ordering question entirely:
         * install it whenever, from wherever, and the next request logs.
         *
         * The cost is one volatile read per logged line, which is nothing beside a socket.
         */
        public val Installed: NetworkLogSink = object : NetworkLogSink {
            override fun log(line: String) {
                NetworkDiagnostics.sink.log(line)
            }

            override fun log(level: NetworkLogLevel, line: String) {
                NetworkDiagnostics.sink.log(level, line)
            }

            override fun toString(): String = "NetworkLogSink.Installed(" + NetworkDiagnostics.sink + ")"
        }

        /**
         * A sink that writes to logcat under [tag]. Prefer [forApplication], which will not hand
         * one to a release build.
         */
        public fun logcat(tag: String = NetworkLog.TAG): NetworkLogSink = LogcatNetworkLogSink(tag)

        /**
         * The right sink for this process: the in-memory session log always, plus logcat when the
         * application is debuggable.
         *
         * ## Why the session log is installed on a release build too
         *
         * Because REQUIREMENTS.md §Observability requires it of the shipped app, not of a developer
         * build: "A local, user-viewable diagnostics log covering the last session … It must be
         * shareable as a file for bug reports." A user who cannot connect to their server has no
         * cable, no `adb` and no debuggable build, and they are precisely the person the requirement
         * exists for. Installing the buffer only when `FLAG_DEBUGGABLE` is set would have satisfied
         * the letter of "a diagnostics log exists" while leaving it empty in every install that
         * ships.
         *
         * This is not the "no debug logging" that §Security rule 5 forbids in release builds. That
         * rule is about logcat — a system-wide buffer any other app with the right permission can
         * read. [SessionDiagnosticsLog] is process-private memory that is erased when the process
         * dies and reaches a file only when the user taps share.
         *
         * ## Why the platform flag rather than a build constant
         *
         * `FLAG_DEBUGGABLE` rather than a `BuildConfig.DEBUG` constant, for two reasons. No module
         * in this project enables the `buildConfig` build feature, so there is no such constant to
         * read; and a library's own `BuildConfig.DEBUG` describes the variant the *library* was
         * built in, which is a different question from whether the installed application is
         * debuggable. The platform flag is what the platform itself uses to decide whether a
         * process may be debugged at all. AGP sets it on every debug build and clears it on every
         * release build, and it cannot be got wrong by a caller who forgets a parameter.
         *
         * Note what this still does **not** consult: a user setting. There is no diagnostics
         * on/off switch, because a log the user has to enable before reproducing a bug is a log
         * that is empty on the one run they wanted it for. What the user controls is whether it
         * ever leaves the device, which is the guarantee the requirement actually asks for.
         */
        public fun forApplication(context: Context, tag: String = NetworkLog.TAG): NetworkLogSink {
            val session: NetworkLogSink = NetworkDiagnostics.sessionLog
            return if (isDebuggableFlags(context.applicationInfo.flags)) {
                session + logcat(tag)
            } else {
                session
            }
        }

        /** The predicate behind [forApplication], split out so it is testable without a Context. */
        internal fun isDebuggableFlags(applicationInfoFlags: Int): Boolean =
            (applicationInfoFlags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}

/**
 * Both sinks, in order, for one line.
 *
 * There is exactly one caller — [NetworkLogSink.forApplication], which has to put a line in the
 * user's diagnostics buffer *and* in logcat on a debuggable build — and it exists rather than being
 * inlined there because the alternative was making one of the two sinks wrap the other. A sink that
 * secretly forwards to a second sink is the kind of thing that later gets installed twice.
 *
 * [NetworkLogSink.None] is folded out on both sides, so composing with nothing costs nothing and
 * [NetworkDiagnostics.isEnabled] still answers correctly.
 */
public operator fun NetworkLogSink.plus(other: NetworkLogSink): NetworkLogSink = when {
    this === NetworkLogSink.None -> other
    other === NetworkLogSink.None -> this
    else -> CompositeNetworkLogSink(this, other)
}

/** The pair [plus] produces. Not constructed directly; the operator folds [NetworkLogSink.None]. */
private class CompositeNetworkLogSink(
    private val first: NetworkLogSink,
    private val second: NetworkLogSink,
) : NetworkLogSink {

    override fun log(line: String) {
        first.log(line)
        second.log(line)
    }

    override fun log(level: NetworkLogLevel, line: String) {
        first.log(level, line)
        second.log(level, line)
    }

    override fun toString(): String = first.toString() + " + " + second.toString()
}

/**
 * The process-wide sink that every [NeedlerHttpClient] built with the default argument writes
 * through.
 *
 * It starts at [NetworkLogSink.None], so a build that never calls [installForApplication] logs
 * nothing whatsoever — which is what every unit test in this project wants, and it means the
 * failure mode of forgetting to wire this up is silence rather than a leak, the safe direction for
 * a module that handles credentials.
 *
 * One line in `NeedlerApplication.onCreate` turns it on:
 *
 * ```kotlin
 * NetworkDiagnostics.installForApplication(this)
 * ```
 *
 * That call is safe to make unconditionally and it is what makes REQUIREMENTS.md §Observability
 * true: it installs [sessionLog] in every build, and adds logcat on top of it only where the
 * application's own `FLAG_DEBUGGABLE` says a developer could have attached a cable. See
 * [NetworkLogSink.forApplication] for why the buffer is not gated on the debuggable flag as well.
 *
 * Deliberately not done: a `ContentProvider` declared in this module's manifest that installs the
 * sink with no wiring at all, the trick AndroidX Startup and Firebase use. It would work, and it
 * was rejected because REQUIREMENTS.md §"Performance budgets" puts cold start to library content
 * under 1.2 s and a provider is a manifest-merged component that every process pays for on every
 * launch, forever, so that a developer need not type one line once.
 */
public object NetworkDiagnostics {

    /**
     * The one diagnostics buffer in the process — the log REQUIREMENTS.md §Observability requires,
     * and the thing the Diagnostics screen reads and shares.
     *
     * A process-wide `object` rather than a Hilt `@Singleton`, because of when it has to start
     * working. [installForApplication] is called from `NeedlerApplication.onCreate`, which runs
     * before anything has asked the dependency graph for anything, and the first requests of a cold
     * start — the capability probe and the delta sync — are the ones a bug report most often needs.
     * A graph-scoped buffer would either be constructed too late to see them or would have to be
     * eagerly created by the very `onCreate` that also has to install it, which is the same
     * singleton with more ceremony and one more way to end up with two of them.
     *
     * It receives nothing until a sink is installed: [forApplication] is what puts it behind
     * [sink]. So a unit test that never installs anything sees an empty buffer, and a
     * `NeedlerHttpClient` built in a test writes to nothing.
     */
    public val sessionLog: SessionDiagnosticsLog = SessionDiagnosticsLog()

    @Volatile
    public var sink: NetworkLogSink = NetworkLogSink.None
        private set

    /** True when something other than [NetworkLogSink.None] is installed. */
    public val isEnabled: Boolean get() = sink !== NetworkLogSink.None

    /**
     * Install [sink] for the whole process. It takes effect on the next line logged, including on
     * clients that were constructed before this was called.
     */
    public fun install(sink: NetworkLogSink) {
        require(sink !== NetworkLogSink.Installed) {
            "NetworkLogSink.Installed forwards here; installing it would recurse forever"
        }
        this.sink = sink
    }

    /**
     * Install the right sink for this process: [sessionLog] always, plus logcat where the
     * application is debuggable. Safe to call unconditionally, which is why it is the one line
     * `NeedlerApplication.onCreate` needs.
     */
    public fun installForApplication(context: Context, tag: String = NetworkLog.TAG) {
        install(NetworkLogSink.forApplication(context, tag))
    }

    /**
     * Back to silence.
     *
     * For tests, which share one JVM and one process-wide install point, so any test that touches
     * this has to put it back. There is no user-facing off switch behind this — see
     * [NetworkLogSink.forApplication] — and note that it does not empty [sessionLog]; clearing the
     * buffer is [SessionDiagnosticsLog.clear], and the two are separate because a test that wants
     * silence usually also wants to assert on what was captured before it.
     */
    public fun disable() {
        sink = NetworkLogSink.None
    }
}

/**
 * Query parameter names whose values are credentials or otherwise must never be logged.
 *
 * Matched **exactly** and case-insensitively, because the Subsonic scheme's names are single
 * letters and a substring rule on `s` or `p` would redact half of every query. The substring rule
 * that catches everything else is [looksSecretShaped].
 */
private val REDACTED_QUERY_KEYS: Set<String> = setOf(
    // OpenSubsonic auth: the apiKey extension, plus the legacy schemes we never send but may see.
    // `t` is the salted token, `s` the salt and `p` the plaintext or hex-encoded password. All
    // three travel in the URL rather than in a header, which is precisely why a naive log of
    // `request.url` leaks a credential on this lane and only on this lane.
    "apikey", "p", "t", "s",
    // Anything token-shaped on either lane.
    "token", "access_token", "api_key", "password", "secret",
)

/**
 * Substrings that make an unknown parameter name secret-shaped.
 *
 * A server can grow a parameter this module has never heard of. Guessing wrong in one direction
 * costs an unreadable log line; guessing wrong in the other puts a credential in logcat. So an
 * unknown name that reads like a secret loses its value and keeps its name, which is still enough
 * to diagnose with.
 */
private val SECRET_SHAPED_NAME_FRAGMENTS: List<String> = listOf(
    "token", "secret", "password", "passwd", "pwd", "apikey", "api_key",
    "auth", "credential", "signature", "bearer", "session",
)

private fun looksSecretShaped(name: String): Boolean {
    val lower = name.lowercase()
    return SECRET_SHAPED_NAME_FRAGMENTS.any { lower.contains(it) }
}

/** Response headers that are useful in a bug report and carry no credentials. */
private val LOGGED_RESPONSE_HEADERS: List<String> = listOf(
    "Content-Type",
    "Content-Length",
    "Content-Range",
    "Retry-After",
    "X-RateLimit-Remaining",
    "X-Degraded-Services",
    "X-Cover-Source",
)

/**
 * A URL safe to log: every credential-bearing query value is replaced with `REDACTED`, any unknown
 * query key keeps its name but loses its value when it looks secret-shaped, and any
 * `user:password@` prefix is dropped whole.
 *
 * Stream, download and cover URLs carry the app-password in `apiKey`, so this must be used on
 * every URL that reaches a log, a crash report or the UI.
 *
 * The userinfo strip is belt and braces rather than a live hole: [ServerUrl.parse] rejects an
 * address containing `@` outright ([ServerUrlResult.Reason.CredentialsInUrl]), so no URL this
 * module *builds* can carry one. It can still be handed one — a `Location` header from a proxy, or
 * a URL passed to [app.needler.core.network.media.RangeDownloader.downloadTo] by another module —
 * and `HttpUrl.toString` renders userinfo faithfully, password and all.
 */
public fun redactUrl(url: HttpUrl): String {
    val builder = url.newBuilder().username("").password("")
    if (url.querySize == 0) return builder.query(null).build().toString()
    builder.query(null)
    for (index in 0 until url.querySize) {
        val name = url.queryParameterName(index)
        val value = url.queryParameterValue(index)
        val redact = name.lowercase() in REDACTED_QUERY_KEYS || looksSecretShaped(name)
        builder.addQueryParameter(name, if (redact) "REDACTED" else value)
    }
    return builder.build().toString()
}

/** String overload for call sites that only have text. Falls back to stripping the whole query. */
public fun redactUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull()
    if (parsed != null) return redactUrl(parsed)
    return url.substringBefore('?') + if (url.contains('?')) "?REDACTED" else ""
}

/**
 * Header names whose value is a credential and which no rule about *shape* would catch.
 *
 * `Cookie` and `Set-Cookie` are the whole list: every other header worth worrying about —
 * `Authorization`, `Proxy-Authorization`, `X-Auth-Token`, `CF-Access-Client-Secret` — contains one of
 * [SECRET_SHAPED_NAME_FRAGMENTS] and is caught by shape. Matched exactly, case-insensitively.
 */
private val SECRET_HEADER_NAMES: Set<String> = setOf("cookie", "set-cookie")

/**
 * `scheme://anything@` — the userinfo of a URL, wherever it appears in a line.
 *
 * Requires the trailing `@`, so a port (`host:8443`) is untouched.
 */
private val USERINFO_IN_TEXT = Regex("([A-Za-z][A-Za-z0-9+.\\-]*://)[^/@\\s]+@")

/**
 * One `?key=value` or `&key=value` pair, anywhere in a line.
 *
 * The delimiter is part of the match so the single-letter Subsonic names (`p`, `t`, `s`) can be
 * redacted without a rule that fires on the letter `t` in ordinary prose.
 */
private val QUERY_PAIR_IN_TEXT = Regex("([?&])([A-Za-z0-9_.\\[\\]\\-]{1,60})=([^&\\s\"'<>]*)")

/**
 * One `Header-Name: value` pair whose **name is already secret-shaped**, anywhere in a line, with the
 * value running to the next separator.
 *
 * Built from [SECRET_SHAPED_NAME_FRAGMENTS] and [SECRET_HEADER_NAMES] rather than written out, so a
 * fragment added for the query rule is picked up by the header rule for free and the two cannot
 * disagree about what a secret looks like.
 *
 * The name is baked into the pattern rather than tested in the replacement lambda, and that is the
 * whole point of the shape. A pattern that matched *any* `name: value` would, on the line
 * `GET https://host/x -> 401 Cookie: session=abc`, match `https:` first, consume the rest of the
 * line as its value, find the name innocent, put it back unchanged — and never look at the `Cookie`
 * that came after it, because a regex replacement resumes after the match it has already made. So
 * the innocent case must not match at all.
 *
 * The name class excludes `.` for the same reason: in `music.yourhome.net:8443` no candidate name
 * reaches the colon, so a host and port survive intact.
 */
private val SECRET_HEADER_PAIR_IN_TEXT: Regex = Regex(
    "\\b([A-Za-z0-9_\\-]*(?:" +
        (SECRET_SHAPED_NAME_FRAGMENTS + SECRET_HEADER_NAMES).joinToString("|") { Regex.escape(it) } +
        ")[A-Za-z0-9_\\-]*)\\s*:\\s*([^,;\\r\\n}\\]]*)",
    RegexOption.IGNORE_CASE,
)

/** `Bearer <token>`, for a line that quoted the credential without naming the header. */
private val BEARER_IN_TEXT = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=\\-]{4,}")

/**
 * Any line of arbitrary text, with every credential this app can hold taken back out of it.
 *
 * ## Why this exists when [redactUrl] already does
 *
 * [redactUrl] is applied by the code that builds a URL, to a `HttpUrl` it holds. It is the right
 * rule and it is not enough, because REQUIREMENTS.md §Observability puts three more kinds of line in
 * the diagnostics log — "sync summaries and playback errors", plus whatever else proves useful — and
 * those are written by modules that never see an `HttpUrl`. A sync summary assembled from the
 * endpoint it was syncing, or a playback error built from an ExoPlayer `IOException` whose message
 * is the request line that failed, is a plain `String` that arrived with the Subsonic app-password
 * inside it. REQUIREMENTS.md §Security rule 1 does not have an exception for lines written by
 * somebody else's module.
 *
 * So [SessionDiagnosticsLog.record] runs this over **every** line on ingest. The cost is a few regex
 * passes per logged line, on the thread that just finished a socket read, and the benefit is that
 * "did the author of this call site think about credentials?" stops being a question anyone has to
 * answer correctly for the app to be safe.
 *
 * ## What it takes out, and in what order
 *
 *  1. **Userinfo.** `https://tim:hunter2@host/…` becomes `https://REDACTED@host/…`. First, because
 *     the password there is the one credential that is not introduced by a name at all.
 *  2. **Secret-shaped headers.** A `Name: value` pair whose name is [SECRET_HEADER_NAMES] or
 *     [looksSecretShaped] loses the whole value — `Authorization: Bearer eyJ…` becomes
 *     `Authorization: REDACTED`. This is the `/api/v1` lane's credential, and the rule is
 *     deliberately greedy to the end of the value: a bearer is one opaque token and there is nothing
 *     in it worth keeping.
 *  3. **Secret-shaped query values.** Exactly the rule [redactUrl] applies, reused here through the
 *     same [REDACTED_QUERY_KEYS] set and the same [looksSecretShaped] predicate rather than
 *     re-stated, so the two cannot drift. This is the Subsonic lane's credential, `apiKey`, plus the
 *     legacy `p`/`t`/`s` trio. **Every** occurrence goes: the replacement is global, so a URL
 *     carrying the same app-password twice — a duplicated parameter, or a retry line that quotes the
 *     URL a second time — comes out redacted twice. A rule that stopped at the first match would be
 *     worse than no rule, because it would look like it had worked.
 *  4. **A bare `Bearer` token**, for a line that quoted the credential without the header name.
 *
 * ## The alternative that was rejected
 *
 * Scrubbing by *value*: hand the buffer the live app-password and bearer from [CredentialProvider]
 * and delete every literal occurrence of either from every line. It is strictly stronger — it would
 * catch a credential hidden in a parameter named something innocuous — and it was rejected because
 * it requires the diagnostics component, whose entire output is a file the user is encouraged to
 * attach to a public bug report, to hold a copy of both live secrets for the life of the process.
 * That trades a hypothetical leak for a real one if any part of the scrubbing is ever inverted or
 * bypassed, and REQUIREMENTS.md §Security rule 1 is about where credentials are allowed to be, not
 * only about what is printed. The name-based rule needs no credential at all, which is why it is the
 * one that ships.
 */
public fun redactLogLine(line: String): String {
    if (line.isEmpty()) return line
    var result: String = USERINFO_IN_TEXT.replace(line) { match -> match.groupValues[1] + "REDACTED@" }
    result = SECRET_HEADER_PAIR_IN_TEXT.replace(result) { match ->
        match.groupValues[1] + ": REDACTED"
    }
    result = QUERY_PAIR_IN_TEXT.replace(result) { match ->
        val delimiter: String = match.groupValues[1]
        val name: String = match.groupValues[2]
        val redact: Boolean = name.lowercase() in REDACTED_QUERY_KEYS || looksSecretShaped(name)
        if (redact) delimiter + name + "=REDACTED" else match.value
    }
    return BEARER_IN_TEXT.replace(result, "Bearer REDACTED")
}

private const val MAX_PARSE_MESSAGE_CHARS = 240

/**
 * A parse failure's message, with the server's own bytes taken back out of it.
 *
 * This exists for one specific and easily missed leak. `kotlinx.serialization`'s
 * `JsonDecodingException` helpfully appends the document it could not read:
 *
 * ```
 * Unexpected JSON token at offset 0: Expected start of the object … at path: $
 * JSON input: <!DOCTYPE html><html><head><title>Sign in</title> …
 * ```
 *
 * which is a superb diagnostic right up until the response that failed to parse is
 * `POST /api/v1/auth/login`, whose body is the freshly minted bearer token. Logging `cause.message`
 * raw would therefore have put a live credential into logcat on exactly the day authentication
 * broke — the day somebody is most likely to be reading logcat.
 *
 * So only the first line survives, the `JSON input:` tail is cut, and the remainder is truncated.
 * What is left — "Fields [releases] are required for type with serial name …", or "Expected start
 * of the object" and an offset — is the part that says what is wrong with the contract, which is
 * the part worth having.
 */
public fun redactParseMessage(message: String?): String {
    if (message.isNullOrBlank()) return "no detail"
    val withoutInput = message.substringBefore("JSON input:").trim()
    val firstLine = withoutInput.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (firstLine.isEmpty()) return "no detail"
    return if (firstLine.length <= MAX_PARSE_MESSAGE_CHARS) {
        firstLine
    } else {
        firstLine.take(MAX_PARSE_MESSAGE_CHARS) + "…"
    }
}

/**
 * A [NetworkError] as one line.
 *
 * Its `message` is safe by construction — see the class KDoc on [NetworkError], which commits to
 * messages carrying no bearer, no app-password and no query string — so the only thing added here
 * is the concrete subclass name, which is what a reader wants to see first.
 */
internal fun NetworkError.describeForLog(): String = javaClass.simpleName + ": " + message

/**
 * The narrow logging façade the rest of this module is handed, so that no call site has to think
 * about whether logging is on.
 *
 * Every method takes its line as a lambda. That is not decoration: with no sink installed — a
 * release build, and every unit test in this module that does not ask for one — none of the string
 * building behind a log call ever runs, so the instrumentation added for these bugs costs one
 * volatile read and one identity comparison per call site.
 */
public class NetworkLog(
    private val sink: NetworkLogSink = NetworkLogSink.Installed,
    /** When false the whole façade is inert, for a release build with diagnostics off. */
    private val enabled: Boolean = true,
) {

    private val target: NetworkLogSink
        get() = if (sink === NetworkLogSink.Installed) NetworkDiagnostics.sink else sink

    /** False when nothing would come of logging, so a caller can skip expensive preparation. */
    public val isEnabled: Boolean
        get() = enabled && target !== NetworkLogSink.None

    public fun log(level: NetworkLogLevel, line: () -> String) {
        val current = target
        if (!enabled || current === NetworkLogSink.None) return
        current.log(level, line())
    }

    public fun debug(line: () -> String): Unit = log(NetworkLogLevel.Debug, line)

    public fun info(line: () -> String): Unit = log(NetworkLogLevel.Info, line)

    public fun warn(line: () -> String): Unit = log(NetworkLogLevel.Warn, line)

    public fun error(line: () -> String): Unit = log(NetworkLogLevel.Error, line)

    public companion object {

        /**
         * The logcat tag for everything this module writes, so that `adb logcat -s NeedlerNet` is
         * the whole network story and nothing else.
         */
        public const val TAG: String = "NeedlerNet"

        /** A façade that never writes. For tests, and for objects built without a client. */
        public val Disabled: NetworkLog = NetworkLog(NetworkLogSink.None, enabled = false)
    }
}

/**
 * Structured, credential-free request logging.
 *
 * Logs one line per attempt: method, redacted URL, status, byte count and duration. It never logs
 * request or response bodies (the login request carries the account password, and the login
 * *response* carries the bearer), and it logs no request header at all — not `Authorization`, not
 * `Cookie`, and not the user's proxy headers, whose values are credentials in their own right (see
 * [ProxyCredentials]). An allowlist of response headers is logged, each chosen because it answers
 * a question a bug report asks: what came back, how much of it, and why it was refused.
 *
 * Note where this sits in the chain. It is the **last** application interceptor
 * [NeedlerHttpClient] adds, so it sees the request after [CredentialInterceptor] has appended
 * `apiKey` to the Subsonic query: the URL it is handed genuinely does contain the app-password,
 * and [redactUrl] is the only thing between that and logcat. Nothing about this class is
 * ornamental.
 */
public class RedactingLogInterceptor(
    private val log: NetworkLog,
) : Interceptor {

    /** Convenience for a caller holding a sink rather than a façade. */
    public constructor(sink: NetworkLogSink, enabled: Boolean = true) : this(NetworkLog(sink, enabled))

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!log.isEnabled) return chain.proceed(request)

        val safeUrl = redactUrl(request.url)
        val startedNanos = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (failure: Exception) {
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
            // Only the exception type and its own message; OkHttp never puts credentials there,
            // but the URL is still routed through redactUrl for safety.
            log.error {
                "${request.method} $safeUrl FAILED ${failure.javaClass.simpleName} after ${tookMs}ms"
            }
            throw failure
        }
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        val length = response.header("Content-Length") ?: "?"
        val headers = LOGGED_RESPONSE_HEADERS
            .mapNotNull { name -> response.header(name)?.let { "$name=$it" } }
            .joinToString(" ")
        // A 4xx or 5xx is the thing the reader came for, so it is raised above the ordinary
        // narrative rather than left for them to spot among two hundred successful calls.
        val level = when {
            response.code >= 500 -> NetworkLogLevel.Error
            response.code >= 400 -> NetworkLogLevel.Warn
            else -> NetworkLogLevel.Debug
        }
        log.log(level) {
            buildString {
                append(request.method).append(' ').append(safeUrl)
                append(" -> ").append(response.code)
                append(" ").append(length).append("B in ").append(tookMs).append("ms")
                if (headers.isNotEmpty()) append(' ').append(headers)
            }
        }
        return response
    }
}
