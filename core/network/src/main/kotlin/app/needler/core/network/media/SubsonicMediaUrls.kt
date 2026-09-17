package app.needler.core.network.media

import app.needler.core.network.CredentialProvider
import app.needler.core.network.ServerUrl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * How a track should be fetched.
 *
 * [Original] is the default everywhere, matching screen 12 and REQUIREMENTS.md §Streaming: the
 * server allows one transcode per user and two in total, so a household with two listeners — or a
 * phone plus a Cast session — can exhaust it. Media3 decodes FLAC, MP3, AAC, Vorbis and Opus
 * natively, which covers everything DroppedNeedle serves.
 *
 * [Transcoded] is only for the metered-data case, and only when the `transcoding` extension is
 * advertised **and** the server reports transcoding enabled. On `429` fall back to [Original] for
 * that track and show a one-line notice.
 */
public sealed interface StreamQuality {

    /** `format=raw`: original bytes, no server-side work, always available. */
    public data object Original : StreamQuality

    /** `format=mp3&maxBitRate=320` for "Stream on mobile data: MP3 320". */
    public data class Transcoded(
        /** `mp3` or `opus` — the only values the shim accepts beside `raw`. */
        public val format: String = "mp3",
        public val maxBitRateKbps: Int = 320,
    ) : StreamQuality
}

/**
 * URL builders for the three binary Subsonic endpoints, so Media3 and Coil fetch bytes
 * themselves. This module never buffers audio.
 *
 * **These URLs carry the app-password** in the `apiKey` query parameter, because a Cast receiver
 * and Media3's own data source both need a self-contained URL. Consequences:
 *  * never log one raw — pass it through [app.needler.core.network.redactUrl] first;
 *  * never persist one (store the track id and rebuild);
 *  * never put one in a crash report, an intent extra that leaves the app, or a share sheet.
 *
 * All three endpoints are `Range`-capable, the server returns `416` correctly, and it disables
 * gzip on audio so seeking works.
 */
public class SubsonicMediaUrls(
    private val credentials: CredentialProvider,
    private val clientName: String = DEFAULT_CLIENT_NAME,
    private val protocolVersion: String = DEFAULT_PROTOCOL_VERSION,
) {

    /**
     * `stream?id=tr-…` — the audio URL for Media3.
     *
     * @param timeOffsetSeconds `timeOffset` for the `transcodeOffset` extension. Only meaningful
     *   with [StreamQuality.Transcoded]; the raw path is seeked with `Range` instead.
     * @param estimateContentLength ask the server to estimate `Content-Length` for a transcode, so
     *   the player can show a duration before the stream ends.
     */
    public fun streamUrl(
        trackId: String,
        quality: StreamQuality = StreamQuality.Original,
        timeOffsetSeconds: Double? = null,
        estimateContentLength: Boolean = false,
    ): String {
        val builder = methodUrl("stream").addQueryParameter("id", trackId)
        when (quality) {
            StreamQuality.Original -> builder.addQueryParameter("format", "raw")
            is StreamQuality.Transcoded -> {
                builder.addQueryParameter("format", quality.format)
                builder.addQueryParameter("maxBitRate", quality.maxBitRateKbps.toString())
                if (timeOffsetSeconds != null) {
                    builder.addQueryParameter("timeOffset", formatSeconds(timeOffsetSeconds))
                }
                if (estimateContentLength) builder.addQueryParameter("estimateContentLength", "true")
            }
        }
        return builder.build().toString()
    }

    /**
     * `download?id=tr-…` — original bytes with a `Content-Disposition` filename, used for
     * "Pull local". Per-track range GETs against this resume after interruption; the album zip at
     * `GET /api/v1/download/local/album/{id}` must not be used.
     */
    public fun downloadUrl(trackId: String): String =
        methodUrl("download").addQueryParameter("id", trackId).build().toString()

    /**
     * `getCoverArt?id=…` — artwork for Coil.
     *
     * The id may be an album (`al-`), artist (`ar-`), track (`tr-`) or playlist (`pl-`) id.
     * [size] is a requested pixel width; the server buckets it to 250, 500 or 1200 and answers a
     * placeholder SVG when it has no art.
     */
    public fun coverArtUrl(id: String, size: Int? = null): String {
        val builder = methodUrl("getCoverArt").addQueryParameter("id", id)
        if (size != null) builder.addQueryParameter("size", size.coerceIn(1, 2_000).toString())
        return builder.build().toString()
    }

    /**
     * `GET /api/v1/covers/release-group/{mbid}` — artwork for a **catalogue** result, which the
     * Subsonic lane knows nothing about (REQUIREMENTS.md §"Search behaviour", point 6).
     *
     * This one is on the `/api/v1` lane, so it carries no query credential: it is authorised by
     * the bearer, which the credential interceptor attaches even to Coil's own requests. Answers
     * `202` with an empty body while artwork is still resolving — retry later — and marks a
     * fallback image with `X-Cover-Source: placeholder`.
     *
     * @param size one of `250`, `500`, `1200`, or `original`.
     */
    public fun catalogueCoverUrl(releaseGroupMbid: String, size: String = "500"): String {
        val base = server().apiV1("").toHttpUrlOrNull() ?: error("Saved server URL is not a valid HTTP URL")
        return base.newBuilder()
            .addPathSegment("covers")
            .addPathSegment("release-group")
            .addPathSegment(releaseGroupMbid)
            .addQueryParameter("size", size)
            .build()
            .toString()
    }

    /**
     * The base builder for a Subsonic method: `/subsonic/rest/<method>` with the three parameters
     * every call must carry plus the `apiKey` credential.
     */
    internal fun methodUrl(method: String, includeCredential: Boolean = true): HttpUrl.Builder {
        val url = server().subsonicRest(method).toHttpUrlOrNull()
            ?: error("Saved server URL is not a valid HTTP URL")
        val builder = url.newBuilder()
            .addQueryParameter("v", protocolVersion)
            .addQueryParameter("c", clientName)
            .addQueryParameter("f", "json")
        if (includeCredential) {
            credentials.appPassword()?.takeIf { it.isNotEmpty() }?.let {
                builder.addQueryParameter("apiKey", it)
            }
        }
        return builder
    }

    private fun server(): ServerUrl = credentials.serverUrl()
        ?: error("No server configured: save a ServerUrl before building media URLs")

    private fun formatSeconds(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    public companion object {
        /** Sent as `c` on every Subsonic call, and recorded by the server as the scrobble client. */
        public const val DEFAULT_CLIENT_NAME: String = "Needler"

        /** Sent as `v`. DroppedNeedle's shim implements OpenSubsonic 1.16.1. */
        public const val DEFAULT_PROTOCOL_VERSION: String = "1.16.1"
    }
}
