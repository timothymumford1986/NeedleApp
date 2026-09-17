package app.needler.core.domain.model

/**
 * Container/codec of a track as the server reports it.
 *
 * Media3 decodes all of these natively, which is why Needler defaults to fetching original bytes
 * (`stream?format=raw`) instead of asking the server to transcode.
 */
public enum class AudioFormat {
    FLAC,
    MP3,
    AAC,
    M4A,
    OGG_VORBIS,
    OPUS,
    WAV,
    ALAC,
    WMA,

    /** The server reported a format Needler does not model. Playback is attempted anyway. */
    UNKNOWN,
    ;

    public companion object {
        /** Maps a server-reported suffix or content type fragment onto a format. Never throws. */
        public fun fromServerToken(token: String?): AudioFormat {
            val normalised: String = token?.trim()?.lowercase() ?: return UNKNOWN
            return when {
                normalised.contains("flac") -> FLAC
                normalised.contains("mp3") || normalised.contains("mpeg") -> MP3
                normalised.contains("alac") -> ALAC
                normalised.contains("m4a") -> M4A
                normalised.contains("aac") -> AAC
                normalised.contains("opus") -> OPUS
                normalised.contains("vorbis") || normalised.contains("ogg") -> OGG_VORBIS
                normalised.contains("wav") -> WAV
                normalised.contains("wma") -> WMA
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Format plus bitrate, the pair the design pack badges on every album and track row
 * (FLAC, MP3 320, MP3 256).
 *
 * Denormalised onto [Album] as well as [TrackFetchHandle] so list rendering never has to load tracks.
 */
public data class AudioQuality(
    val format: AudioFormat?,
    val bitrateKbps: Int?,
) {
    /** True for formats that carry no meaningful bitrate badge because they are lossless. */
    public val isLossless: Boolean
        get() = format == AudioFormat.FLAC || format == AudioFormat.ALAC || format == AudioFormat.WAV

    public companion object {
        public val Unknown: AudioQuality = AudioQuality(format = null, bitrateKbps = null)
    }
}

/**
 * What the user has asked Needler to stream.
 *
 * Original is the default and the requirement: the server allows one transcode per user and two in
 * total, so a household with two listeners plus a Cast session can exhaust it. Transcoding is only ever
 * offered when `transcoding:1` is advertised *and* the server reports transcoding enabled - see
 * [ServerCapabilities.transcodingAvailable].
 */
public enum class StreamQualityPreference {
    /** Always fetch original bytes with `format=raw`. */
    ORIGINAL,

    /** Original on unmetered networks; `format=mp3&maxBitRate=320` while metered. */
    MP3_320_ON_METERED,
}

/**
 * The concrete stream a playback attempt should make, resolved from preference, connectivity and
 * capabilities. The data layer turns this into a URL; the domain does not know URLs.
 */
public sealed interface StreamFormat {
    /** `stream?id=<tr-file_id>&format=raw`: original bytes, decoded on device. */
    public data object Original : StreamFormat

    /**
     * A server-side transcode, e.g. `format=mp3&maxBitRate=320`.
     *
     * On `429` the caller must fall back to [Original] for that track and show a one-line notice
     * rather than failing, because the server's transcode slots are a shared, tiny resource.
     */
    public data class Transcoded(
        val codec: String,
        val maxBitrateKbps: Int,
    ) : StreamFormat {
        public companion object {
            /** The only transcode v1 ever requests. */
            public val Mp3_320: Transcoded = Transcoded(codec = "mp3", maxBitrateKbps = 320)
        }
    }
}
