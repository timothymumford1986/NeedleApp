package app.needler.image

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.map.Mapper
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.request.crossfade
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.subsonic.SubsonicApi

/**
 * Turns an [ArtworkRef] into a URL Coil can fetch.
 *
 * Screens hand Coil the domain's [ArtworkRef] rather than a string, because building the URL needs
 * the saved server address and the app-password, and a feature module is not allowed to reach either.
 * That left every artwork tile in the app rendering as a placeholder tint until something owned the
 * translation. This is that something, and `:app` is the right place for it: it is where the server
 * address, the session and the HTTP client already meet.
 *
 * The two server-backed cases authenticate differently, which is the reason this cannot be a simple
 * string concatenation:
 *
 *  * [ArtworkRef.Owned] goes to Subsonic `getCoverArt`, which carries the app-password as an `apiKey`
 *    query parameter. The URL is self-authenticating and needs no header.
 *  * [ArtworkRef.Catalogue] goes to `/api/v1/covers/release-group/{mbid}`, which is behind the bearer
 *    token. It authenticates by header, which is why the loader below is built on the project's own
 *    OkHttp client rather than a fresh one — that client already attaches the bearer, honours the
 *    pinned certificate for a self-signed server, and redacts credentials from its logs.
 *
 * [ArtworkRef.Remote] is an absolute URL the server handed us for an artist image and is passed
 * through untouched.
 */
internal class ArtworkRefMapper(
    private val subsonic: SubsonicApi,
) : Mapper<ArtworkRef, String> {

    override fun map(data: ArtworkRef, options: Options): String? = try {
        when (data) {
            is ArtworkRef.Owned -> subsonic.mediaUrls.coverArtUrl(data.subsonicId, size = REQUEST_SIZE)
            is ArtworkRef.Catalogue ->
                subsonic.mediaUrls.catalogueCoverUrl(data.releaseGroupMbid.value)
            is ArtworkRef.Remote -> data.url.takeIf { it.isNotBlank() }
        }
    } catch (_: IllegalStateException) {
        // The URL builders throw when no server is saved yet, which is the normal state on the
        // Connect screen. A null here renders the placeholder, which is the correct picture of
        // "there is no server to ask", rather than an error.
        null
    }

    private companion object {
        /**
         * Covers are requested at 500px rather than full size. The largest artwork the app draws is
         * the tablet sidebar hero; everything else is a grid cell or a list row, and a full-resolution
         * FLAC-era cover can be several megabytes each, which on a library-sized grid is a lot of
         * traffic and memory for pixels nobody sees.
         */
        private const val REQUEST_SIZE = 500
    }
}

/**
 * Builds the app's single [ImageLoader].
 *
 * Shares the project's OkHttp client so artwork inherits the bearer token, the certificate pin and
 * the credential redaction rather than quietly bypassing all three with a default client.
 *
 * The disk cache is Coil's own and is separate from the audio store. It is small and expendable:
 * `REQUIREMENTS.md` reports artwork on its own line in Settings precisely so that someone hunting for
 * gigabytes does not waste a tap on it.
 */
internal fun buildArtworkImageLoader(
    context: Context,
    http: NeedlerHttpClient,
    subsonic: SubsonicApi,
): ImageLoader = ImageLoader.Builder(context)
    .components {
        add(ArtworkRefMapper(subsonic))
        // mediaClient, not client: it is the one built for audio and artwork, with no overall call
        // timeout and a longer read timeout. A large cover on a slow home connection should not be
        // cancelled by a timeout sized for JSON.
        add(OkHttpNetworkFetcherFactory(callFactory = { http.mediaClient }))
    }
    .diskCache {
        DiskCache.Builder()
            .directory(context.cacheDir.resolve(ARTWORK_CACHE_DIR))
            .maxSizeBytes(ARTWORK_CACHE_BYTES)
            .build()
    }
    .crossfade(true)
    .build()

private const val ARTWORK_CACHE_DIR = "artwork"

/** 256 MB, enough for a large library's grid without competing with the audio store. */
private const val ARTWORK_CACHE_BYTES = 256L * 1024 * 1024
