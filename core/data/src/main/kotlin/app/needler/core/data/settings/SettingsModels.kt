package app.needler.core.data.settings

import app.needler.core.data.local.cache.CacheBudget

/*
 * Everything on the Settings screen (design/html/12-Settings.html), plus the two sub-screens it
 * leads to: the equaliser (19) and crossfade (20).
 *
 * Three things on the drawn screen are deliberately absent from these models:
 *
 *  * **"Prefer FLAC"** is not a setting. The request body carries no quality field - quality is a
 *    server-side policy under `/api/v1/download-clients/policy`, which is admin-only to change -
 *    so it is shown read-only from `quality_snapshot_summary` and stored on `album`, not here.
 *  * **"Scrobble to ListenBrainz"** loses its destination. The toggle governs whether Needler
 *    reports plays at all; where they go is the server's business, read from
 *    `GET /api/v1/me/scrobble-preferences` and used only to label the switch.
 *  * **"Pull on Wi-Fi only"** moves and changes meaning - see [StorageSettings].
 */

/** Stream quality, as the two quality rows on screen 12 offer it. */
public enum class StreamQuality(public val storageValue: String) {
    /**
     * `stream?format=raw`: original bytes, decoded on device. The default, deliberately: the server
     * allows one transcode per user and two in total, so a household with two listeners plus a Cast
     * session can exhaust it.
     */
    ORIGINAL("original"),
    MP3_320("mp3_320"),
    MP3_256("mp3_256"),
    MP3_192("mp3_192"),
    MP3_128("mp3_128"),
    ;

    /** Bitrate to pass as `maxBitRate`, or null for [ORIGINAL], which passes `format=raw` instead. */
    public val maxBitrateKbps: Int?
        get() = when (this) {
            ORIGINAL -> null
            MP3_320 -> 320
            MP3_256 -> 256
            MP3_192 -> 192
            MP3_128 -> 128
        }

    public val isTranscode: Boolean get() = this != ORIGINAL

    public companion object {
        public fun fromStorageValue(value: String?): StreamQuality =
            entries.firstOrNull { it.storageValue == value } ?: ORIGINAL
    }
}

/** The five presets on screen 19. */
public enum class EqPreset(public val storageValue: String) {
    FLAT("flat"),
    BASS("bass"),
    VOCAL("vocal"),
    BRIGHT("bright"),
    VINYL("vinyl"),

    /** The user moved a band by hand, so no preset chip is selected. */
    CUSTOM("custom"),
    ;

    public companion object {
        public fun fromStorageValue(value: String?): EqPreset =
            entries.firstOrNull { it.storageValue == value } ?: FLAT
    }
}

/**
 * The equaliser, stored per device and never synced to the server, exactly as screen 19 states.
 *
 * Ten bands at 31, 62, 125, 250, 500 Hz and 1, 2, 4, 8, 16 kHz, gain +/-12 dB, plus a preamp. The
 * band frequencies and the range match DroppedNeedle's web player; the *presets* deliberately do
 * not - Needler ships the five mobile-appropriate ones from screen 19 rather than the server's ten.
 */
public data class EqualiserSettings(
    val enabled: Boolean = false,
    val preset: EqPreset = EqPreset.FLAT,
    /** Ten gains in dB, low band first, each clamped to [MIN_GAIN_DB]..[MAX_GAIN_DB]. */
    val bandGainsDb: List<Int> = FLAT_BANDS,
    val preampDb: Int = 0,
) {
    public companion object {
        /** Band centre frequencies in Hz, in order. */
        public val BAND_FREQUENCIES_HZ: List<Int> =
            listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

        public const val BAND_COUNT: Int = 10
        public const val MIN_GAIN_DB: Int = -12
        public const val MAX_GAIN_DB: Int = 12

        public val FLAT_BANDS: List<Int> = List(BAND_COUNT) { 0 }

        /** Clamps a gain into the supported range rather than rejecting it. */
        public fun clampGain(gainDb: Int): Int = gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)

        /**
         * Forces a band list to exactly [BAND_COUNT] entries in range. A stored value of the wrong
         * length - a settings file written by a different build, say - degrades to flat for the
         * missing bands instead of crashing the audio pipeline.
         */
        public fun sanitiseBands(bands: List<Int>): List<Int> =
            List(BAND_COUNT) { index -> clampGain(bands.getOrElse(index) { 0 }) }
    }
}

/** Crossfade and the three fade sub-toggles from screen 20. */
public data class CrossfadeSettings(
    /** 0 means off. The chips offered are Off, 4 s, 6 s and 12 s. */
    val seconds: Int = 0,

    /**
     * "Skip fade inside an album": suppress crossfade when the next track shares the album, so
     * gapless records stay intact. On by default, because crossfading a segued album is worse than
     * not crossfading at all.
     */
    val skipFadeInsideAlbum: Boolean = true,

    /** "Fade on skip": a 1 s fade when the user presses next. */
    val fadeOnSkip: Boolean = true,

    /** "Fade on pause": a short ramp instead of an abrupt stop. */
    val fadeOnPause: Boolean = true,
) {
    public val enabled: Boolean get() = seconds > 0

    public companion object {
        /** The choices drawn on screen 20. */
        public val OPTIONS_SECONDS: List<Int> = listOf(0, 4, 6, 12)

        public const val MAX_SECONDS: Int = 12

        /** Snaps any stored value to a supported one, so a bad value cannot reach the audio chain. */
        public fun sanitiseSeconds(seconds: Int): Int =
            if (OPTIONS_SECONDS.contains(seconds)) seconds else 0
    }
}

/** The Playing section of screen 12, minus the equaliser and crossfade sub-screens. */
public data class PlaybackSettings(
    /** On by default: Media3 concatenation, no re-buffer between tracks. */
    val gaplessEnabled: Boolean = true,
    val streamQuality: StreamQuality = StreamQuality.ORIGINAL,

    /**
     * Quality requested **only while on a metered connection**, as the "Stream on mobile data" row
     * offers. MP3 320 to match the drawn screen.
     *
     * The setting must be hidden entirely unless `transcoding:1` is advertised *and* the server
     * reports transcoding enabled, and a `429` from a transcode falls back to the original stream
     * for that track. Neither of those is this store's business - it only remembers the choice.
     */
    val mobileDataStreamQuality: StreamQuality = StreamQuality.MP3_320,

    /**
     * Whether Needler reports plays at all. The destination (ListenBrainz, Last.fm) is configured
     * server-side, so it is read for the label and never stored here.
     */
    val scrobbleEnabled: Boolean = true,
)

/** The three independently switchable notifications on screen 12. */
public data class NotificationSettings(
    val pullFinished: Boolean = true,
    val pullFailed: Boolean = true,
    val newReleaseFromFollowedArtist: Boolean = true,
) {
    /**
     * True when no notification is wanted. With no active pulls this also means no background
     * polling beyond the six-hourly sync, which is a battery requirement rather than a nicety.
     */
    public val allDisabled: Boolean
        get() = !pullFinished && !pullFailed && !newReleaseFromFollowedArtist
}

/** The Storage section of screen 12. */
public data class StorageSettings(
    /** Bytes, or [CacheBudget.UNLIMITED]. Defaults to the 4 GB drawn on screen 12. */
    val budgetBytes: Long = CacheBudget.DEFAULT_BYTES,

    /**
     * "Keep pulled albums on device": auto-pin any album this device successfully pulled, so newly
     * acquired music is already offline next time.
     */
    val keepPulledAlbumsOnDevice: Boolean = false,

    /**
     * "Download to device on Wi-Fi only", defaulting to on.
     *
     * This is the corrected form of the design pack's "Pull on Wi-Fi only", which sat under Pulling.
     * A pull costs the phone nothing - the server does the downloading over its own connection and
     * the phone sends one small request. What actually consumes mobile data is **Pull local**,
     * downloading audio to the device, so the setting belongs here and governs that. Leaving it
     * under Pulling would teach users that requesting music costs them data, which is false.
     */
    val downloadToDeviceOnWifiOnly: Boolean = true,
) {
    public val isUnlimited: Boolean get() = CacheBudget.isUnlimited(budgetBytes)
}

/** Every setting in one snapshot, for callers that want a single read. */
public data class NeedlerSettings(
    val playback: PlaybackSettings = PlaybackSettings(),
    val crossfade: CrossfadeSettings = CrossfadeSettings(),
    val equaliser: EqualiserSettings = EqualiserSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val storage: StorageSettings = StorageSettings(),
) {
    public companion object {
        /** The state a fresh install is in, before anything has been written. */
        public val Defaults: NeedlerSettings = NeedlerSettings()
    }
}
