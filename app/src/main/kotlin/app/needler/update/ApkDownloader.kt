package app.needler.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Fetches the release APK into app-private storage, reporting progress, and refuses to hand on
 * anything whose length does not match what GitHub published.
 *
 * ## Where the file goes, and why there
 *
 * `context.cacheDir/updates/`. App-private internal storage, which means no storage permission on
 * any API level, no `FileProvider`, no world-readable copy of an installable APK sitting in
 * Downloads, and eviction by the platform if the device runs short — which is exactly the right
 * behaviour for a file that is disposable the moment the install finishes.
 *
 * `cacheDir` rather than `filesDir` is deliberate. REQUIREMENTS.md's storage rules are about audio
 * ("App-private internal storage, with no user-set budget", downloads "never auto-evicted"), and an
 * update APK is the opposite of that: a few megabytes with a lifetime of minutes, which the
 * listener should never meet in a storage breakdown as something they chose to keep.
 *
 * ## Verification
 *
 * The byte count is compared against the asset's published `size`, and the file is deleted if it
 * differs. That catches a truncated response, a captive portal's login page served with a 200, and
 * a proxy that decided to transform the body.
 *
 * It is deliberately **not** a security check, and there is no checksum here. The signature is what
 * makes an APK safe to install, and the platform verifies it as part of the install: an APK signed
 * with anything other than the key that signed what is already on the device is rejected outright
 * with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Re-implementing that would be a second, weaker copy of
 * a guarantee the system already gives — and which the release workflow's "Verify the APK is
 * actually signed" step gives at the other end. GitHub publishes no checksum for release assets, so
 * a hash would have to arrive in the same document as the URL and would prove nothing against
 * anyone able to rewrite one of them.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: GitHubHttp,
) {

    /**
     * Download [update], calling [onProgress] with the running byte count.
     *
     * @return the finished file, or `null` for every failure. Like the check itself, a failed
     *   download says nothing on its own; the caller decides what the listener sees.
     */
    suspend fun download(
        update: AvailableUpdate,
        onProgress: (bytesRead: Long) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        // Belt and braces. The parser already refused any URL not on this prefix, but this is the
        // last point before bytes are requested from a URL that arrived in a remote document.
        if (!update.downloadUrl.startsWith(NeedleAppRepository.DOWNLOAD_URL_PREFIX)) {
            return@withContext null
        }

        val target = fileFor(update)

        // An APK already here at exactly the published length is the one we were about to fetch:
        // the app was killed, or the listener backgrounded it mid-install and came back. Reusing it
        // spares someone on mobile data from paying twice for the same three megabytes.
        if (target.isFile && target.length() == update.sizeBytes) {
            onProgress(update.sizeBytes)
            return@withContext target
        }

        val directory = target.parentFile
        if (directory != null && !directory.isDirectory && !directory.mkdirs()) {
            return@withContext null
        }
        // A part-written file from an earlier attempt is never resumed. Range resumption would need
        // the server to guarantee the same object across a redirect it signs per request, and the
        // whole file is a few megabytes; starting again is simpler and cannot splice two releases.
        target.delete()

        val request = Request.Builder()
            .url(update.downloadUrl)
            .get()
            .header("Accept", "application/vnd.android.package-archive")
            .header("User-Agent", http.userAgent)
            .build()

        try {
            http.download.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                var written = 0L
                response.body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            // Cancellation is checked per buffer rather than per call: OkHttp's
                            // blocking read does not observe the coroutine, so without this a
                            // cancelled download would go on consuming the listener's data until
                            // the body ended of its own accord.
                            coroutineContext.ensureActive()

                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read

                            // Refuse to keep writing past the published size, rather than filling
                            // the cache partition for a body that never ends.
                            if (written > update.sizeBytes) {
                                target.delete()
                                return@withContext null
                            }
                            onProgress(written)
                        }
                        output.flush()
                    }
                }

                if (written != update.sizeBytes) {
                    target.delete()
                    return@withContext null
                }
                target
            }
        } catch (cancelled: CancellationException) {
            // Leave nothing half-written behind: the reuse short-circuit above trusts the length of
            // whatever it finds, so a partial file must not survive to be mistaken for a whole one.
            target.delete()
            throw cancelled
        } catch (failure: IOException) {
            target.delete()
            null
        }
    }

    /**
     * Throw away cached APKs that can no longer be installed.
     *
     * Called after every completed check. The obvious case is the one that matters: the install
     * succeeded, the process was replaced, and the APK for the version now running is still sitting
     * in the cache. Anything at or below [installedVersionCode] goes, and so does anything whose
     * name no longer parses — the platform would evict it eventually, but a file the app can
     * account for is a file the app should tidy up itself.
     */
    fun pruneStaleDownloads(installedVersionCode: Long) {
        val directory = File(context.cacheDir, DIRECTORY_NAME)
        val files = directory.listFiles() ?: return
        for (file in files) {
            val tag = file.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
            val versionCode = ReleaseTag.versionCodeOf(tag)
            if (versionCode == null || versionCode <= installedVersionCode) {
                file.delete()
            }
        }
    }

    /**
     * Where an update's APK is cached.
     *
     * The tag is scrubbed to letters, digits, dot, dash and underscore before it becomes a file
     * name. The tag is a value from a remote document, and a tag containing a path traversal would
     * otherwise choose the path — which would have this code writing an arbitrary file inside the
     * app's own data directory.
     */
    private fun fileFor(update: AvailableUpdate): File = File(
        File(context.cacheDir, DIRECTORY_NAME),
        FILE_PREFIX + safeTag(update.tagName) + FILE_SUFFIX,
    )

    private fun safeTag(tag: String): String {
        val scrubbed = StringBuilder(tag.length)
        for (character in tag) {
            val safe = character.isLetterOrDigit() ||
                character == '.' ||
                character == '-' ||
                character == '_'
            scrubbed.append(if (safe) character else '_')
        }
        return scrubbed.toString().take(MAX_TAG_CHARS)
    }

    private companion object {
        const val DIRECTORY_NAME = "updates"
        const val FILE_PREFIX = "needler-"
        const val FILE_SUFFIX = ".apk"
        const val MAX_TAG_CHARS = 64
        const val BUFFER_BYTES = 64 * 1024
    }
}
