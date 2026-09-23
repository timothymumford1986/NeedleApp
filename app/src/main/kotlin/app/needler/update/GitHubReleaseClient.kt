package app.needler.update

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response

/**
 * One HTTP call: "what is the latest release?".
 *
 * Every outcome other than a parsed release is silent — see [ReleaseLookup] for why an update check
 * is the last thing in the app that should be allowed to interrupt anyone. Nothing here logs, and
 * nothing here throws except cancellation.
 *
 * The client it uses is [GitHubHttp], which is not the app's HTTP client and must never be. That
 * file carries the full argument; the short version is that `:core:network`'s `CredentialInterceptor`
 * is not host-scoped, so reusing it would risk sending the listener's DroppedNeedle bearer token to
 * github.com.
 *
 * ## Why blocking OkHttp inside `withContext`
 *
 * OkHttp 5 ships suspending call support in a separate `okhttp-coroutines` artefact that is not on
 * this project's classpath, and adding a dependency for one call would be a poor trade. `execute()`
 * on [Dispatchers.IO] is the same thing with one more thread parked; the enclosing coroutine's
 * cancellation is honoured at the next suspension point, and a check that is one `GET` long has
 * nothing worth interrupting mid-flight.
 */
@Singleton
class GitHubReleaseClient @Inject constructor(
    private val http: GitHubHttp,
) {

    /**
     * Ask GitHub for the newest release.
     *
     * Returning [ReleaseLookup.Found] does **not** mean an update is available: it means a release
     * was read and understood. Whether it is newer than what is installed, and whether the listener
     * has already dismissed it, is [UpdateCheckPolicy]'s decision and deliberately not this class's.
     */
    suspend fun latestRelease(): ReleaseLookup = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(NeedleAppRepository.LATEST_RELEASE_URL)
            .get()
            // The documented media type and API version. Pinning the version means a future
            // breaking change to the release object shape is GitHub's problem on their schedule,
            // not ours on an APK that shipped a year ago.
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", http.userAgent)
            .build()

        try {
            http.api.newCall(request).execute().use { response -> interpret(response) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            // No network, DNS failure, TLS failure, captive portal, timeout. All the same answer:
            // say nothing, try again shortly. `IOException` is the only thing OkHttp promises to
            // throw from `execute()`, and anything else is a programming error that should not be
            // swallowed into silence.
            ReleaseLookup.Unreachable
        }
    }

    private fun interpret(response: Response): ReleaseLookup = when {
        response.isSuccessful -> {
            // `peekBody` rather than `body.string()`: a body is bounded before it is read, so a
            // pathological or hostile response cannot be streamed into memory indefinitely. The
            // real document is a few kilobytes.
            val body = runCatching { response.peekBody(MAX_BODY_BYTES).string() }.getOrNull()
            val release = body?.let(GitHubReleaseParser::parse)
            if (release == null) ReleaseLookup.NoUsableRelease else ReleaseLookup.Found(release)
        }

        // 403 with the rate-limit headers, or 429. GitHub has used both over the years, and a
        // 403 that is *not* rate limiting (a blocked IP, say) wants the same treatment anyway:
        // wait, and do not ask again soon.
        response.code == HTTP_FORBIDDEN || response.code == HTTP_TOO_MANY_REQUESTS ->
            ReleaseLookup.RateLimited(backoffMillis(response))

        // A repository with no releases yet answers 404. It is a complete, correct answer meaning
        // "nothing to install", not a failure to retry.
        response.code == HTTP_NOT_FOUND -> ReleaseLookup.NoUsableRelease

        else -> ReleaseLookup.Unreachable
    }

    /**
     * How long to wait after a refusal.
     *
     * `Retry-After` is honoured first because it is the explicit instruction. `X-RateLimit-Reset`
     * is a Unix epoch **in seconds** and is what the documented rate limiter actually sends; it is
     * turned into a duration against the local clock, which is why the result is clamped — a device
     * whose clock is a year out would otherwise compute a year-long backoff from a correct header.
     */
    private fun backoffMillis(response: Response): Long {
        val retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull()
        if (retryAfterSeconds != null) return clamp(retryAfterSeconds * 1_000L)

        val resetEpochSeconds = response.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()
        if (resetEpochSeconds != null) {
            return clamp(resetEpochSeconds * 1_000L - System.currentTimeMillis())
        }
        return DEFAULT_BACKOFF_MILLIS
    }

    private fun clamp(millis: Long): Long =
        millis.coerceIn(MIN_BACKOFF_MILLIS, MAX_BACKOFF_MILLIS)

    private companion object {
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429

        /** A release document with a long body and many assets is still far inside this. */
        const val MAX_BODY_BYTES = 512L * 1024L

        /** The unauthenticated window is an hour, so this is the honest default. */
        const val DEFAULT_BACKOFF_MILLIS = 60L * 60L * 1_000L
        const val MIN_BACKOFF_MILLIS = 5L * 60L * 1_000L
        const val MAX_BACKOFF_MILLIS = 24L * 60L * 60L * 1_000L
    }
}
