package app.needler.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Where this module's request log goes. `:core:data` implements it over the local, user-viewable
 * diagnostics log required by REQUIREMENTS.md §Observability ("request URLs with secrets
 * redacted, status codes … never leaves the device automatically").
 *
 * Implementations must be cheap and thread-safe; they are called on OkHttp's threads.
 */
public fun interface NetworkLogSink {

    public fun log(line: String)

    public companion object {
        /** Drops everything. The default, and what release builds use unless diagnostics is on. */
        public val None: NetworkLogSink = NetworkLogSink { }
    }
}

/** Query parameter names whose values are credentials or otherwise must never be logged. */
private val REDACTED_QUERY_KEYS: Set<String> = setOf(
    // OpenSubsonic auth: apiKey extension plus the legacy schemes we never send but may see.
    "apikey", "p", "t", "s",
    // Anything token-shaped on either lane.
    "token", "access_token", "api_key", "password", "secret",
)

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
 * A URL safe to log: every credential-bearing query value is replaced with `REDACTED`, and any
 * unknown query key keeps its name but loses its value if it looks secret-shaped.
 *
 * Stream, download and cover URLs carry the app-password in `apiKey`, so this must be used on
 * every URL that reaches a log, a crash report or the UI.
 */
public fun redactUrl(url: HttpUrl): String {
    if (url.querySize == 0) return url.newBuilder().query(null).build().toString()
    val builder = url.newBuilder().query(null)
    for (index in 0 until url.querySize) {
        val name = url.queryParameterName(index)
        val value = url.queryParameterValue(index)
        val redact = name.lowercase() in REDACTED_QUERY_KEYS
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
 * Structured, credential-free request logging.
 *
 * Logs one line per attempt: method, redacted URL, status, byte count and duration. It never
 * logs request or response bodies (the login body carries the account password) and never logs
 * `Authorization`, `Cookie` or `Set-Cookie`.
 */
public class RedactingLogInterceptor(
    private val sink: NetworkLogSink,
    /** When false the interceptor is a no-op, for release builds with diagnostics off. */
    private val enabled: Boolean = true,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!enabled || sink === NetworkLogSink.None) return chain.proceed(request)

        val safeUrl = redactUrl(request.url)
        val startedNanos = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (failure: Exception) {
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
            // Only the exception type and its own message; OkHttp never puts credentials there,
            // but the URL is still routed through redactUrl for safety.
            sink.log("${request.method} $safeUrl FAILED ${failure.javaClass.simpleName} after ${tookMs}ms")
            throw failure
        }
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
        val length = response.header("Content-Length") ?: "?"
        val headers = LOGGED_RESPONSE_HEADERS
            .mapNotNull { name -> response.header(name)?.let { "$name=$it" } }
            .joinToString(" ")
        sink.log(
            buildString {
                append(request.method).append(' ').append(safeUrl)
                append(" -> ").append(response.code)
                append(" ").append(length).append("B in ").append(tookMs).append("ms")
                if (headers.isNotEmpty()) append(' ').append(headers)
            },
        )
        return response
    }
}
