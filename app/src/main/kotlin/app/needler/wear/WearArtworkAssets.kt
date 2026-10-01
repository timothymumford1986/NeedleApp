package app.needler.wear

import app.needler.core.domain.model.ArtworkRef
import app.needler.core.network.NeedlerHttpClient
import app.needler.core.network.subsonic.SubsonicApi
import com.google.android.gms.wearable.Asset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Cover art for the watch, as a data-layer `Asset`.
 *
 * ## Why this asks the server for a 200 px cover instead of going through Coil
 *
 * `:app` owns the project's one Coil `ImageLoader` and it is the right tool for anything drawn on this
 * phone, because it caches decoded bitmaps and a 256 MB disk cache of covers on disk. It is the wrong
 * tool for this, for one reason: what goes on the wire is *bytes*, and Coil hands back a decoded
 * `Bitmap`. The cached copy is the 500 px one the app's grids ask for, so the Coil route is
 * decode-then-scale-then-re-encode on every album change, to produce a JPEG the server will build for
 * free.
 *
 * Subsonic's `getCoverArt` takes a `size`, so the phone asks for exactly the square the watch wants and
 * forwards the response body untouched. No `Bitmap`, no re-encode, and the bytes the server sent are
 * the bytes the watch decodes.
 *
 * The cost is honest and small: one HTTP GET of roughly 10 to 20 KB per album, and only while a watch
 * is actually looking - [WearStatePublisher] publishes inside a window, not all day. There is no
 * OkHttp response cache on this client, so it really is a request per album rather than per track,
 * which is what the one-entry cache below is for.
 *
 * ## Why the URL is built here rather than reused
 *
 * `ArtworkRefMapper` in `app.needler.image` does the same translation for Coil, and it cannot be
 * reused: it is a `coil3.map.Mapper`, so calling it means manufacturing a Coil `Options` for a request
 * that does not exist. The translation itself is two lines and the interesting half - which lane
 * authenticates how - is recorded in that file and in `SubsonicMediaUrls`.
 *
 * ## One entry, and why that is enough
 *
 * The watch shows one track, every track on an album shares one artwork id, and an `Asset` is a digest
 * once Google Play services has it. Holding the last one means an album's worth of publishes - every
 * play, pause, buffer and skip - re-uses the same asset, and the data layer recognises the digest and
 * transfers no bytes. Publishing a freshly built `Asset` each time would look identical in this file
 * and put the cover back across Bluetooth on every pause.
 */
@Singleton
class WearArtworkAssets @Inject constructor(
    private val http: NeedlerHttpClient,
    private val subsonic: SubsonicApi,
) {

    /**
     * The last asset and the id it belongs to.
     *
     * Volatile because the publisher observes on a background dispatcher and Google Play services
     * calls back on its own threads; the pair is written together and read together, and a torn read
     * would at worst fetch a cover twice.
     */
    @Volatile
    private var cachedId: String? = null

    @Volatile
    private var cached: Asset? = null

    /**
     * The cover for [artworkId], or null when there is none to be had.
     *
     * Null is an ordinary answer, not an error: there may be no artwork, no saved server yet, no
     * network, or a server that answers `202` while it is still resolving a cover. The watch draws its
     * record placeholder for all of them, which is a complete state rather than a loading one.
     *
     * @param artworkId the value that goes on the wire as
     *   [WearPlaybackProtocol.KEY_ARTWORK_ID] - the release-group MBID, stable across an album.
     * @param ref where to fetch it from, as the domain recorded it.
     */
    suspend fun assetFor(artworkId: String, ref: ArtworkRef): Asset? {
        cached?.takeIf { cachedId == artworkId }?.let { return it }

        // The URL builders throw when no server is saved, which is the ordinary state before anyone
        // has connected - the same case ArtworkRefMapper answers with null rather than an error.
        val url: String = orNullOnFailure { urlFor(ref) } ?: return null
        val bytes: ByteArray = withContext(Dispatchers.IO) { fetch(url) } ?: return null

        val asset: Asset = Asset.createFromBytes(bytes)
        cachedId = artworkId
        cached = asset
        return asset
    }

    private fun urlFor(ref: ArtworkRef): String? = when (ref) {
        // Self-authenticating: coverArtUrl carries the app-password as an apiKey query parameter.
        is ArtworkRef.Owned -> subsonic.mediaUrls.coverArtUrl(ref.subsonicId, size = COVER_PX)
        // The v1 lane, authorised by the bearer that the credential interceptor attaches.
        is ArtworkRef.Catalogue ->
            subsonic.mediaUrls.catalogueCoverUrl(ref.releaseGroupMbid.value, size = CATALOGUE_SIZE)
        // An absolute URL the server handed us. Artist images, which a track never carries.
        is ArtworkRef.Remote -> ref.url.takeIf { it.isNotBlank() }
    }

    /**
     * The response body, or null for anything that is not a usable cover.
     *
     * `mediaClient`, not `client`: it is the one built for audio and artwork, and it shares the
     * connection pool, the pinned certificate and the credential redaction with every other request
     * the app makes. A fresh OkHttp client here would be a second certificate-pinning policy, which
     * is the thing REQUIREMENTS.md has one client to avoid.
     *
     * An empty body is the server's `202` "still resolving", which is a null rather than a zero-byte
     * asset. The size ceiling is a guard against a server that ignores `size` and returns the original
     * scan: a two-megabyte cover over Bluetooth would take longer than the track.
     */
    private fun fetch(url: String): ByteArray? = orNullOnFailure {
        val request: Request = Request.Builder().url(url).get().build()
        http.mediaClient.newCall(request).execute().use { response ->
            val body: ByteArray? = if (response.isSuccessful) response.body.bytes() else null
            body?.takeIf { it.isNotEmpty() && it.size <= MAX_ASSET_BYTES }
        }
    }

    private companion object {
        /**
         * The square the watch is sent, in pixels.
         *
         * The protocol's own figure: "200 px or so is ample for any watch". Wear's largest round
         * display is 227dp across and the cover is drawn at a quarter of that.
         */
        const val COVER_PX: Int = 200

        /**
         * `catalogueCoverUrl`'s `size`, which takes one of `250`, `500`, `1200` or `original` rather
         * than any number. 250 is the nearest thing to [COVER_PX] it will serve.
         *
         * This path is nearly unreachable: a playing track is by definition owned, so its artwork is
         * an [ArtworkRef.Owned]. It is handled because the sealed interface has three cases and
         * guessing which two can occur is how a `when` grows an exception.
         */
        const val CATALOGUE_SIZE: String = "250"

        /** 256 KB. Ample for a 200 px JPEG and a refusal to put a full-size scan on a Bluetooth link. */
        const val MAX_ASSET_BYTES: Int = 256 * 1024
    }
}
