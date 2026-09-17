package app.needler.core.domain.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.datetime.Instant

/**
 * One entry of the play queue - "in the crate", in the product's own vocabulary.
 *
 * [id] is a queue-local identifier, not a track identity: the same track may legitimately appear twice
 * in the crate, and drag-to-reorder needs a stable handle per row.
 *
 * The crate lives on the device and persists across restarts. Server-side queue sync via
 * `savePlayQueue` is out of v1 scope, so nothing here is synced.
 */
public data class QueueItem(
    val id: String,
    val track: Track,
    val addedAt: Instant? = null,
    val source: QueueItemSource = QueueItemSource.USER,
)

/** How an item got into the crate. */
public enum class QueueItemSource {
    /** Added explicitly by the user. */
    USER,

    /** Added by playing an album, playlist or artist. */
    COLLECTION,

    /** Restored from the persisted crate at startup. */
    RESTORED,
}

/**
 * The crate as the player and UI see it: screens 08 and 09 show a Playing row, an Up next list with a
 * count and total duration, reorder handles and a Clear action.
 */
public data class PlayQueue(
    val items: List<QueueItem> = emptyList(),
    /** Index into [items], or null when nothing is loaded. */
    val currentIndex: Int? = null,
) {
    public val currentItem: QueueItem?
        get() {
            val index: Int = currentIndex ?: return null
            return items.getOrNull(index)
        }

    /** Everything after the current item: the "Up next" list. */
    public val upNext: List<QueueItem>
        get() {
            val index: Int = currentIndex ?: return items
            return if (index + 1 <= items.lastIndex) items.subList(index + 1, items.size) else emptyList()
        }

    /** Total duration of the whole crate, in milliseconds, ignoring tracks of unknown length. */
    public val totalDurationMs: Long
        get() = items.sumOf { item -> item.track.durationMs ?: 0L }
}

/**
 * The 10-band equaliser from screen 19.
 *
 * Band frequencies and the plus/minus 12 dB range match DroppedNeedle's web player exactly, so a user
 * moving between the two sees the same controls. The *presets* deliberately differ: Needler ships the
 * five-preset mobile set rather than the server's ten.
 *
 * EQ state is stored per device and is never synced to the server. It needs a custom Media3 audio
 * processor chain, which is also why the platform `Equalizer` effect is not used.
 */
public data class EqSettings(
    val isEnabled: Boolean = false,
    /** Gain per band in dB, in [EqBand] order. Exactly [BAND_COUNT] entries. */
    val bandGainsDb: List<Float> = FlatGains,
    /** Global pre-amplifier gain in dB, within [GAIN_RANGE_DB]. */
    val preampDb: Float = 0f,
    /** The preset the gains came from, or [EqPreset.CUSTOM] once the user moves a slider. */
    val preset: EqPreset = EqPreset.FLAT,
) {
    init {
        require(bandGainsDb.size == BAND_COUNT) {
            "EqSettings needs exactly " + BAND_COUNT + " band gains, got " + bandGainsDb.size
        }
    }

    public fun gainFor(band: EqBand): Float = bandGainsDb[band.ordinal]

    public companion object {
        public const val BAND_COUNT: Int = 10

        /** Gain limits in dB, matching the server's web player. */
        public val GAIN_RANGE_DB: ClosedFloatingPointRange<Float> = -12f..12f

        public val FlatGains: List<Float> = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

        public val Default: EqSettings = EqSettings()
    }
}

/** The ten EQ band centre frequencies, in Hz, in slider order. */
public enum class EqBand(public val centreFrequencyHz: Int) {
    HZ_31(31),
    HZ_62(62),
    HZ_125(125),
    HZ_250(250),
    HZ_500(500),
    KHZ_1(1_000),
    KHZ_2(2_000),
    KHZ_4(4_000),
    KHZ_8(8_000),
    KHZ_16(16_000),
}

/**
 * The presets drawn on screen 19.
 *
 * The server ships Flat, Rock, Pop, Jazz, Classical, Bass Boost, Treble Boost, Vocal, Electronic and
 * Acoustic. These five are the better mobile set and are kept deliberately; the screen's caption
 * claims the same *bands* as the web player, not the same presets. Gain curves belong to the
 * presentation layer, not the domain.
 */
public enum class EqPreset {
    FLAT,
    BASS,
    VOCAL,
    BRIGHT,
    VINYL,

    /** The user has adjusted a slider by hand. */
    CUSTOM,
}

/**
 * Crossfade, from screen 20.
 *
 * Implemented with two `ExoPlayer` instances and volume ramps, which is why it is modelled as explicit
 * settings rather than a single duration: the pipeline needs to know when *not* to fade.
 */
public data class CrossfadeSettings(
    val duration: CrossfadeDuration = CrossfadeDuration.OFF,
    /**
     * "Skip fade inside an album": suppress crossfade when the next track shares the album, so gapless
     * records stay intact. On by default, because crossfading a segued album is a bug to most users.
     */
    val suppressWithinAlbum: Boolean = true,
    /** One-second fade when the user presses next. */
    val fadeOnSkip: Boolean = true,
    /** A short ramp instead of an abrupt stop on pause. */
    val fadeOnPause: Boolean = true,
) {
    public val isEnabled: Boolean get() = duration != CrossfadeDuration.OFF
}

/** The crossfade lengths offered on screen 20. */
public enum class CrossfadeDuration(public val duration: Duration) {
    OFF(Duration.ZERO),
    FOUR_SECONDS(4.seconds),
    SIX_SECONDS(6.seconds),
    TWELVE_SECONDS(12.seconds),
}

/** The sleep timer. Not drawn in the design pack; the requirement is end-of-track or a duration. */
public sealed interface SleepTimer {
    public data object Off : SleepTimer

    /** Stop when the current track finishes. */
    public data object EndOfTrack : SleepTimer

    /** Stop at a wall-clock instant, computed when the user picked a duration. */
    public data class At(val instant: Instant) : SleepTimer
}

/**
 * Playback speed, 0.5x to 2x.
 *
 * Validated on construction so an out-of-range speed cannot reach the player: Media3 will happily
 * accept 0 and stall.
 */
public data class PlaybackSpeed(val value: Float) {
    init {
        require(value in RANGE) { "Playback speed must be within " + RANGE + ", was " + value }
    }

    public companion object {
        public val RANGE: ClosedFloatingPointRange<Float> = 0.5f..2.0f
        public val Normal: PlaybackSpeed = PlaybackSpeed(1.0f)
    }
}

/**
 * A place sound can come out of, for the single "Play on" picker on screen 21.
 *
 * The picker replaces the system output dialog because Cast targets must appear beside Bluetooth ones
 * in one list, and the current target is always named in the player so a user never wonders where
 * sound is going.
 */
public sealed interface OutputTarget {
    public val id: String
    public val displayName: String

    /** True when picking this target can actually work right now. */
    public val isSelectable: Boolean

    /** The phone or tablet itself. */
    public data class ThisDevice(
        override val displayName: String,
    ) : OutputTarget {
        override val id: String get() = "this-device"
        override val isSelectable: Boolean get() = true
    }

    /** A connected or nearby Bluetooth sink. */
    public data class Bluetooth(
        override val id: String,
        override val displayName: String,
        val isConnected: Boolean,
    ) : OutputTarget {
        override val isSelectable: Boolean get() = true
    }

    /**
     * A Cast receiver.
     *
     * A receiver fetches the audio itself, so it needs a URL it can reach and a certificate it will
     * accept - and three setups this app explicitly supports defeat that: a VPN-only server, a
     * self-signed certificate, and plain HTTP. Reachability is therefore probed *before* the target is
     * offered, and an unusable target says why in the picker rather than failing after the user picks a
     * speaker.
     *
     * A Cast session also consumes a server stream slot independently of the phone, which matters
     * against the two-transcode ceiling.
     */
    public data class Cast(
        override val id: String,
        override val displayName: String,
        val availability: CastAvailability,
    ) : OutputTarget {
        override val isSelectable: Boolean get() = availability == CastAvailability.REACHABLE
    }
}

/** Whether a Cast receiver can reach the music server, and if not, why not. */
public enum class CastAvailability {
    /** Probed and able to fetch from the server. */
    REACHABLE,

    /** Probe not run yet. */
    UNKNOWN,

    /** The server is only reachable over the phone's VPN, which the receiver is not on. */
    SERVER_NOT_REACHABLE,

    /** The server presents a self-signed certificate, which a receiver will not accept. */
    SELF_SIGNED_CERTIFICATE,

    /** The server is plain HTTP, which a receiver may refuse as mixed content. */
    INSECURE_HTTP,
}

/**
 * Everything on the playback half of the settings screen, as one observable value so the player
 * service and the UI cannot disagree.
 */
public data class PlaybackPreferences(
    /** Gapless playback, on by default; Media3 concatenation with no re-buffer between tracks. */
    val gaplessEnabled: Boolean = true,
    val crossfade: CrossfadeSettings = CrossfadeSettings(),
    val eq: EqSettings = EqSettings.Default,
    val speed: PlaybackSpeed = PlaybackSpeed.Normal,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    /**
     * Stream quality. Original by default; the MP3 320 option is hidden entirely unless
     * [ServerCapabilities.transcodingAvailable] is true.
     */
    val streamQuality: StreamQualityPreference = StreamQualityPreference.ORIGINAL,
    val scrobblingEnabled: Boolean = true,
)
