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
 * One rung of the stream-quality ladder: the *most* the user is willing to have sent.
 *
 * ## A ceiling, not a target
 *
 * A rung is an upper bound, never a request for re-encoding. [ORIGINAL] is the top of the ladder and
 * never transcodes; every other rung transcodes **only** when doing so would actually send fewer
 * bytes than the source already does - see
 * [app.needler.core.domain.usecase.ResolvePlayableSourceUseCase.Companion.streamFormatFor]. Asking
 * for MP3 320 on a library that is already MP3 320 therefore streams the original bytes, and asking
 * for anything at all from an MP3 128 source does too. You cannot transcode upward: requesting FLAC
 * from an MP3 source yields the MP3, so "better than the source" is not a state this ladder can
 * express and does not try to.
 *
 * ## Why the ladder is ordered the way it is
 *
 * Declaration order is **display order** - best first - and nothing reads [Enum.ordinal] to decide
 * anything. Opus at a given bitrate sounds considerably better than MP3 at the same bitrate, which
 * is the whole reason the Opus rungs exist in a data-saving mode, but that is a perceptual judgement
 * and the retention decision is a byte-count one. Comparisons are therefore made on
 * [maxBitrateKbps] alone. REQUIREMENTS.md "Streaming" is about bytes on a metered connection;
 * modelling perceptual equivalence here would put an argument about codecs inside a cache rule.
 *
 * ## The consequence the UI has to say out loud
 *
 * REQUIREMENTS.md "Why transcoded bytes are never cached": only original-format bytes are ever
 * retained. So **a rung below your library's source quality never builds an offline library** - the
 * bytes play and are thrown away, every time. That is correct and must not be weakened, which is why
 * the Settings screen says it in words beside the picker rather than leaving the user to discover it
 * from a storage figure that never moves.
 *
 * @param codec the `format` parameter the Subsonic `stream` endpoint takes - `mp3` or `opus`, the
 *   only two the shim accepts beside `raw`. Null for [ORIGINAL], which passes `format=raw`.
 * @param maxBitrateKbps the `maxBitRate` parameter, and the number every size comparison is made on.
 *   Null for [ORIGINAL], which has no ceiling because it *is* the source.
 */
public enum class StreamRung(
    public val codec: String?,
    public val maxBitrateKbps: Int?,
) {
    /** Original bytes with `format=raw`. Never transcodes, always cacheable, always available. */
    ORIGINAL(codec = null, maxBitrateKbps = null),

    OPUS_192(codec = "opus", maxBitrateKbps = 192),
    OPUS_128(codec = "opus", maxBitrateKbps = 128),
    OPUS_96(codec = "opus", maxBitrateKbps = 96),
    MP3_320(codec = "mp3", maxBitrateKbps = 320),
    MP3_256(codec = "mp3", maxBitrateKbps = 256),
    MP3_192(codec = "mp3", maxBitrateKbps = 192),
    MP3_128(codec = "mp3", maxBitrateKbps = 128),
    ;

    /** True for every rung that can ask the server to re-encode, which is all of them but [ORIGINAL]. */
    public val isTranscode: Boolean get() = this != ORIGINAL

    /**
     * This rung as a concrete transcode request, or null for [ORIGINAL].
     *
     * Null rather than a throw: [ORIGINAL] is the commonest rung and "no transcode" is a legitimate
     * answer to "what transcode does this rung mean", not a programming error.
     */
    public val transcode: StreamFormat.Transcoded?
        get() {
            val requestedCodec: String = codec ?: return null
            val requestedBitrate: Int = maxBitrateKbps ?: return null
            return StreamFormat.Transcoded(codec = requestedCodec, maxBitrateKbps = requestedBitrate)
        }
}

/**
 * The two rungs the user picks: one for Wi-Fi, one for mobile data.
 *
 * ## Why there is no third rung for downloads
 *
 * "Pulled" is always original bytes and has no picker. A download is the copy that stays, and
 * REQUIREMENTS.md "Why transcoded bytes are never cached" is explicit about what a transcoded
 * download would be: "a lossy 320 kbps copy [as] that track's **permanent** offline version". So the
 * third mode exists in the product's vocabulary and deliberately not as a setting.
 *
 * ## Why [wifi] defaults to [StreamRung.ORIGINAL] and stays there
 *
 * REQUIREMENTS.md "Streaming": "Default stream quality is Original", because "the server allows one
 * transcode per user and two in total, so a household with two listeners can exhaust it". The
 * unmetered case has nothing to save and a scarce slot to spend, so it never spends one by default.
 *
 * [data] defaults to [StreamRung.MP3_320], which is the pair the retired
 * "Stream MP3 320 on mobile data" toggle already stored. That is what makes this change a widening
 * of an existing setting rather than a migration: see
 * `DefaultPlaybackSettingsRepository.toStreamRungs`.
 */
public data class StreamRungs(
    val wifi: StreamRung = StreamRung.ORIGINAL,
    val data: StreamRung = StreamRung.MP3_320,
) {
    /**
     * The rung this connection uses, before any per-item override and before the source cap.
     *
     * Offline is not a case here: nothing is streamed offline, and
     * [app.needler.core.domain.usecase.ResolvePlayableSourceUseCase] has already returned
     * `UnavailableOffline` by the time a rung is chosen. An unmetered connection gets [wifi], which
     * is also the honest answer for a connection whose meteredness the platform will not say.
     */
    public fun forConnectivity(connectivity: ConnectivityState): StreamRung =
        if (connectivity.isMetered) data else wifi

    public companion object {
        /** A fresh install: original on Wi-Fi, MP3 320 on mobile data. */
        public val Default: StreamRungs = StreamRungs()
    }
}

/** What a per-item quality override is attached to. Resolution order is track, then album. */
public enum class StreamOverrideScope {
    /** One track, keyed on [TrackKey.canonicalString]. */
    TRACK,

    /** A whole record, keyed on its release-group MBID. */
    ALBUM,
}

/**
 * A per-item quality override: "this record, at this rung, wherever I am".
 *
 * ## Absolute, not per-network
 *
 * One value, applied on Wi-Fi and on mobile data alike. The mode defaults answer "what do I usually
 * want here"; an override answers "this record is different", and a record that is worth hearing
 * lossless is worth it on the train too. Two per-network overrides per item would also mean four
 * numbers to reason about for one album, three of which nothing on screen shows.
 *
 * ## It survives a download, dormant
 *
 * REQUIREMENTS.md "Streaming" and this use case's own local-first rule mean a local copy always
 * wins, so while an album is downloaded its override changes nothing. It is **kept** rather than
 * cleared: deleting a download to free space should not silently revoke a preference the user never
 * withdrew, and the dimmed `Server:` tag keeps it on screen so it is not hidden state. The
 * alternative - clearing the override when a download lands - was rejected for that reason, and the
 * decision is one line in either direction if it proves wrong.
 */
public data class StreamOverride(
    val scope: StreamOverrideScope,
    /** [TrackKey.canonicalString] for a track, the release-group MBID for an album. */
    val id: String,
    val rung: StreamRung,
)

/**
 * The concrete stream a playback attempt should make, resolved from the chosen [StreamRung],
 * connectivity, capabilities and the source's own quality. The data layer turns this into a URL; the
 * domain does not know URLs.
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
            /**
             * What [StreamRung.MP3_320] asks for: the default mobile-data rung, and the only
             * transcode the retired "Stream MP3 320 on mobile data" toggle could ever produce.
             *
             * Kept as a named value because it is the one transcode existing installs will already
             * be on, so it reads better in a test than a bare pair of literals.
             */
            public val Mp3_320: Transcoded = Transcoded(codec = "mp3", maxBitrateKbps = 320)
        }
    }
}
