package app.needler.player.service.media

import app.needler.core.domain.model.TrackKey

/**
 * The opaque URI a `MediaItem` carries instead of a real stream URL.
 *
 * ## Why not the stream URL
 *
 * A Subsonic stream URL carries the app-password in its `apiKey` query parameter. A `MediaItem` is
 * bundled across the process boundary to every controller of the session - the app, the widgets, Auto,
 * Wear - persisted by the session for playback resumption, and printed in full by Media3's own debug
 * logging. Putting a credential in it would scatter that credential across four processes and the log.
 *
 * It would also be wrong even if it were secret. The resolver decides per play whether a track comes from
 * disk or the network, at which quality, and whether the bytes on disk are still the current copy; a URL
 * baked into the queue at enqueue time answers all three questions once, minutes or hours early. A queue
 * built on Wi-Fi would still be asking for original FLAC after the phone moved to mobile data, and a
 * track downloaded in the meantime would still be streamed.
 *
 * So the queue holds `needler://track/<release-group mbid>/<disc>/<track>` and the data source resolves it
 * at the moment it is opened. Same reason the cache is keyed this way: it is the identity that survives the
 * server replacing a file.
 */
public object TrackUri {

    public const val SCHEME: String = "needler"
    public const val TRACK_AUTHORITY: String = "track"

    private const val PREFIX: String = SCHEME + "://" + TRACK_AUTHORITY + "/"

    public fun forTrack(key: TrackKey): String = PREFIX + key.canonicalString

    /** Parses one back, or null when it is not one of ours. Null is dropped, never thrown on. */
    public fun toTrackKey(uri: String?): TrackKey? {
        if (uri == null || !uri.startsWith(PREFIX)) return null
        return MediaId.toTrackKey(uri.removePrefix(PREFIX))
    }

    public fun isTrackUri(uri: String?): Boolean = uri != null && uri.startsWith(PREFIX)
}
