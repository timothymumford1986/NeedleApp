// kotlinx.datetime.Instant is a typealias for kotlin.time.Instant from
// kotlinx-datetime 0.7.0 onwards, and the underlying type has carried an
// opt-in marker through several Kotlin releases. Opting in here costs a
// warning if it turns out not to be needed, and avoids a build break if it is.
// The same note sits at the top of LibraryFormat.kt.
@file:OptIn(ExperimentalTime::class)

package app.needler.feature.player.ui

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.playback.RepeatMode
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Every string the player screens print, in one place.
 *
 * Formatting lives here rather than in the composables for two reasons. The timecodes are the thing
 * the design pack is fussiest about - "elapsed and remaining times use tabular numerals so they do
 * not jitter" - and getting the shape of them right (no hours until there are hours, a leading minus
 * on the remaining side, never a bare "0:0") is worth testing directly rather than through a
 * rendered screen. And every one of them has a spoken counterpart: TalkBack reads "3:59" as "three
 * fifty-nine", so the accessible label has to say "3 minutes 59 seconds" instead.
 */
object PlayerFormat {

    /**
     * A position or duration as the pack prints it: `1:16`, `21:04`, `1:02:03`.
     *
     * Negative and unknown values render as `--:--` rather than as a nonsense number, because an
     * unknown length is a real state - a live stream, or a track whose duration the server never
     * reported - and a scrubber showing `0:00` for it is a lie.
     */
    fun timecode(millis: Long?): String {
        if (millis == null || millis < 0L) return UNKNOWN_TIME
        val totalSeconds: Long = millis / 1_000L
        val seconds: Long = totalSeconds % 60L
        val minutes: Long = (totalSeconds / 60L) % 60L
        val hours: Long = totalSeconds / 3_600L
        return if (hours > 0L) {
            hours.toString() + ":" + pad(minutes) + ":" + pad(seconds)
        } else {
            minutes.toString() + ":" + pad(seconds)
        }
    }

    /**
     * What is left of the current track, printed with the pack's leading minus: `-2:04`.
     *
     * Clamped at zero: the position can momentarily overshoot the reported duration while a track
     * changes over, and `-0:-1` on screen looks like a bug to the person watching it.
     */
    fun remaining(positionMs: Long, durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0L) return UNKNOWN_TIME
        val left: Long = (durationMs - positionMs).coerceAtLeast(0L)
        return "-" + timecode(left)
    }

    /** `3 minutes 59 seconds`: a timecode as TalkBack should say it rather than as it is written. */
    fun spokenDuration(millis: Long?): String {
        if (millis == null || millis < 0L) return "length unknown"
        val totalSeconds: Long = millis / 1_000L
        val seconds: Long = totalSeconds % 60L
        val minutes: Long = (totalSeconds / 60L) % 60L
        val hours: Long = totalSeconds / 3_600L
        val parts: MutableList<String> = mutableListOf()
        if (hours > 0L) parts += plural(hours, "hour")
        if (minutes > 0L) parts += plural(minutes, "minute")
        if (seconds > 0L || parts.isEmpty()) parts += plural(seconds, "second")
        return parts.joinToString(separator = " ")
    }

    /**
     * The crate's own summary line: `6 tracks - 21 min`, with the pack's middle dot.
     *
     * Rounds *up* to the nearest minute once there is anything at all to play, because "0 min" next
     * to a queued track reads as an error. Tracks whose length the server never reported contribute
     * nothing, which is the same rule [app.needler.core.domain.model.PlayQueue.totalDurationMs] uses.
     */
    fun crateSummary(trackCount: Int, totalDurationMs: Long): String {
        val tracks: String = plural(trackCount.toLong(), "track")
        if (totalDurationMs <= 0L) return tracks
        val minutes: Long = ((totalDurationMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        return if (minutes >= 60L) {
            val hours: Long = minutes / 60L
            val rest: Long = minutes % 60L
            val length: String =
                if (rest == 0L) plural(hours, "hr") else plural(hours, "hr") + " " + rest + " min"
            tracks + DOT + length
        } else {
            tracks + DOT + minutes + " min"
        }
    }

    /** The same, spoken. */
    fun spokenCrateSummary(trackCount: Int, totalDurationMs: Long): String =
        plural(trackCount.toLong(), "track") + ", " + spokenDuration(totalDurationMs)

    /**
     * The format badge beside the title: `FLAC`, `MP3 320`.
     *
     * Lossless formats carry no bitrate, because a bitrate on a FLAC badge tells a user nothing they
     * can act on; lossy ones are meaningless without it. Returns null when the server reported
     * neither, so the badge is left off rather than drawn empty.
     */
    fun formatBadge(quality: AudioQuality?): String? {
        val format: AudioFormat? = quality?.format
        val name: String? = format?.let(::formatName)
        val bitrate: Int? = quality?.bitrateKbps?.takeIf { it > 0 }
        return when {
            name == null && bitrate == null -> null
            name == null -> bitrate.toString() + " kbps"
            quality.isLossless || bitrate == null -> name
            else -> name + " " + bitrate
        }
    }

    /**
     * What the **Server** tag shows: the format the next play would actually fetch.
     *
     * Two cases, and the distinction is the whole reason this is not [formatBadge]:
     *
     *  * [StreamFormat.Original] means the server sends the file untouched, so the honest label is the
     *    *source's* own quality - `FLAC`, `MP3 320`. The rung does not appear anywhere, because a rung
     *    is a ceiling and a ceiling nobody reached is not news.
     *  * [StreamFormat.Transcoded] means the server re-encodes, so the label is what it re-encodes to -
     *    `MP3 192`, `Opus 128` - which is genuinely different from what the library holds.
     *
     * Null when there is nothing to say, so the tag is left off rather than drawn empty.
     */
    fun serverBadge(format: StreamFormat?, source: AudioQuality?): String? = when (format) {
        null -> null
        StreamFormat.Original -> formatBadge(source)
        is StreamFormat.Transcoded -> codecName(format.codec) + " " + format.maxBitrateKbps
    }

    /**
     * The Server tag, spoken.
     *
     * TalkBack reads a chip border as nothing at all, so the sentence has to carry what the chip's
     * emphasis says on screen: whether this is what you are about to hear, or a rate that a local copy
     * is overriding.
     */
    fun spokenServerBadge(format: StreamFormat?, source: AudioQuality?, inUse: Boolean): String? {
        val value: String = serverBadge(format = format, source = source) ?: return null
        val transcoding: Boolean = format is StreamFormat.Transcoded
        return when {
            !inUse -> "From the server, " + value + ", not in use while this is on the device"
            transcoding -> "Streaming " + value + ", re-encoded by the server and not kept on this device"
            else -> "Streaming " + value + ", original quality"
        }
    }

    /**
     * What the **Pulled** tag shows: the quality of the copy actually on this device.
     *
     * Always the source's own quality, because a download is always original bytes - REQUIREMENTS.md
     * "Why transcoded bytes are never cached" makes a transcoded download "that track's **permanent**
     * offline version", which is why there is no rung picker for pulling and no second case here.
     */
    fun pulledBadge(pulled: AudioQuality?): String? = formatBadge(pulled)

    /** The Pulled tag, spoken. Names the tier, since "on device" is the fact the tag exists to state. */
    fun spokenPulledBadge(pulled: AudioQuality?): String? {
        val value: String = pulledBadge(pulled) ?: return null
        return "Downloaded to this device, " + value + ", playing from here"
    }

    /** `MP3`, `Opus` - a codec the resolver names for a URL, spelled the way the project spells it. */
    private fun codecName(codec: String): String = when (codec.lowercase()) {
        "mp3" -> "MP3"
        "opus" -> "Opus"
        // Anything else is a codec this build does not know the house spelling for. Shown as the
        // server names it rather than dropped: a rate with no format is worse than an odd-looking one.
        else -> codec.uppercase()
    }

    private fun formatName(format: AudioFormat): String? = when (format) {
        AudioFormat.FLAC -> "FLAC"
        AudioFormat.MP3 -> "MP3"
        AudioFormat.AAC -> "AAC"
        AudioFormat.M4A -> "M4A"
        AudioFormat.OGG_VORBIS -> "OGG"
        AudioFormat.OPUS -> "OPUS"
        AudioFormat.WAV -> "WAV"
        AudioFormat.ALAC -> "ALAC"
        AudioFormat.WMA -> "WMA"
        AudioFormat.UNKNOWN -> null
    }

    /** `The Marias - Submarine`, the second line under every title in the pack. */
    fun artistAndAlbum(item: QueueItem?): String {
        val track = item?.track ?: return ""
        val album: String? = track.albumTitle?.takeIf { it.isNotBlank() }
        return if (album == null) track.artistName else track.artistName + DOT + album
    }

    /**
     * Where sound is going, named plainly.
     *
     * REQUIREMENTS.md: "The current output is always named in the player - 'Living room speaker',
     * 'This tablet' - so a user never wonders where sound is going." A null target is the session not
     * having told us yet, and "This device" is the honest answer: nothing else can be playing.
     */
    fun outputName(target: OutputTarget?): String = target?.displayName ?: "This device"

    /** The picker's second line: `Bluetooth - connected`, `Speaker`, `Cast`. */
    fun outputDetail(target: OutputTarget): String = when (target) {
        is OutputTarget.ThisDevice -> "Speaker"
        is OutputTarget.Bluetooth ->
            "Bluetooth" + DOT + if (target.isConnected) "connected" else "nearby"
        is OutputTarget.Cast -> "Cast"
    }

    /**
     * Why a target cannot be picked, or null when it can.
     *
     * REQUIREMENTS.md "Cast, and where it breaks": a receiver fetches the audio itself, so a
     * VPN-only, self-signed or plain-HTTP server defeats it. "Probe reachability before offering a
     * Cast target, and when it cannot work, say why in the output picker rather than failing after
     * the user picks a speaker." These are those sentences. Each names the thing that is wrong with
     * the setup, not with the speaker, because the speaker is fine.
     */
    fun unavailableReason(target: OutputTarget): String? {
        val cast: OutputTarget.Cast = target as? OutputTarget.Cast ?: return null
        return when (cast.availability) {
            CastAvailability.REACHABLE -> null
            CastAvailability.UNKNOWN -> "Cast - checking whether it can reach your server"
            CastAvailability.SERVER_NOT_REACHABLE ->
                "Cast - your server is only reachable over the VPN, which this speaker is not on"
            CastAvailability.SELF_SIGNED_CERTIFICATE ->
                "Cast - this speaker will not accept your server's self-signed certificate"
            CastAvailability.INSECURE_HTTP ->
                "Cast - this speaker refuses plain HTTP, which your server is served over"
        }
    }

    // ------------------------------------------------------------------ sleep timer

    /**
     * What the sleep-timer chip reads: `Sleep timer`, `End of track`, `24 min`.
     *
     * The armed-with-a-duration case prints what is *left*, not what was chosen, because what was chosen
     * is not recorded - [SleepTimer.At] holds the moment to stop and nothing counts down to it. See
     * [SleepTimer] for why that is the right shape.
     *
     * Under a minute reads `Less than a minute` rather than `0 min`, which would look like the timer had
     * already failed to fire. An instant that has passed reads as the unarmed label: the coordinator
     * disarms an elapsed timer on its next tick, so saying anything else would be a value about to
     * vanish.
     */
    fun sleepTimerLabel(timer: SleepTimer, now: Instant): String = when (timer) {
        SleepTimer.Off -> SLEEP_TIMER_OFF
        SleepTimer.EndOfTrack -> "End of track"
        is SleepTimer.At -> {
            val left: Duration = timer.instant - now
            when {
                !left.isPositive() -> SLEEP_TIMER_OFF
                left.inWholeMinutes < 1L -> "Less than a minute"
                else -> minutesLabel(left.inWholeMinutes)
            }
        }
    }

    /** The same, said: TalkBack reads "24 min" as "twenty-four min". */
    fun spokenSleepTimer(timer: SleepTimer, now: Instant): String = when (timer) {
        SleepTimer.Off -> "Sleep timer off"
        SleepTimer.EndOfTrack -> "Sleep timer, stopping at the end of this track"
        is SleepTimer.At -> {
            val left: Duration = timer.instant - now
            if (!left.isPositive()) {
                "Sleep timer off"
            } else {
                "Sleep timer, " + spokenDuration(left.inWholeMilliseconds) + " left"
            }
        }
    }

    /** `45 min`, `1 hr`, `1 hr 20 min` - the crate summary's own shape, for the same reason. */
    private fun minutesLabel(minutes: Long): String {
        if (minutes < 60L) return minutes.toString() + " min"
        val hours: Long = minutes / 60L
        val rest: Long = minutes % 60L
        return if (rest == 0L) plural(hours, "hr") else plural(hours, "hr") + " " + rest + " min"
    }

    // ------------------------------------------------------------------ favourites

    /**
     * Why a star did not stick, or null when it did.
     *
     * Almost nothing reaches here. `FavouriteRepository` writes the mirror before it asks the server and
     * journals the call in the write queue when offline or when the failure is retryable, so the heart
     * fills under the finger and stays filled - REQUIREMENTS.md: "Every mutation made offline is
     * journalled and replayed in order on reconnect: pulls, playlist changes, star and unstar,
     * scrobbles." What is left is a *permanent* rejection, and that one has to be said out loud: the
     * mirror now says starred and the server never will, and a silent disagreement between the two is
     * how a favourite quietly disappears on the next sync.
     */
    fun favouriteErrorMessage(error: NeedlerError): String = when (error) {
        is NeedlerError.PermissionDenied ->
            "Your account is not allowed to change favourites."
        is NeedlerError.SessionExpired, is NeedlerError.AppPasswordRevoked ->
            "Your session expired. Sign in again to save favourites."
        is NeedlerError.NotFound -> "That track is no longer on the server."
        else -> "That favourite did not reach the server."
    }

    /** How the repeat button's current mode is spoken, since the two "on" modes differ only in a dot. */
    fun repeatDescription(mode: RepeatMode): String = when (mode) {
        RepeatMode.OFF -> "Repeat off"
        RepeatMode.ALL -> "Repeat the crate"
        RepeatMode.ONE -> "Repeat this track"
    }

    /**
     * A playback failure as a line a listener can act on.
     *
     * Commands do not report their own failures - they land on
     * [app.needler.core.domain.playback.PlaybackState.error], because the session is shared - so this
     * is the one place the player turns one into words. Every case names what to do next where there
     * is anything to do; [NeedlerError.diagnostic] is deliberately not shown, since it exists for a
     * log rather than for a person.
     */
    fun errorMessage(error: NeedlerError): String = when (error) {
        is NeedlerError.Offline -> "No connection. On-device tracks still play."
        is NeedlerError.StreamSlotsExhausted ->
            "The server is out of streaming slots. Try again in a moment."
        is NeedlerError.RateLimited -> "The server is busy. Try again in a moment."
        is NeedlerError.NotFound -> "That track is no longer on the server."
        is NeedlerError.SessionExpired, is NeedlerError.AppPasswordRevoked ->
            "Your session expired. Sign in again to keep streaming."
        is NeedlerError.InvalidCredentials -> "Your sign-in is no longer valid."
        is NeedlerError.InsufficientStorage -> "There is no room left on this device."
        is NeedlerError.DownloadForbidden -> "Your account is not allowed to stream this."
        is NeedlerError.PermissionDenied -> "Your account is not allowed to do that."
        is NeedlerError.Cancelled -> "Playback stopped."
        else -> "That track would not play."
    }

    /** `1 track` / `6 tracks`, and the same for minutes and hours. */
    private fun plural(count: Long, noun: String): String =
        count.toString() + " " + noun + if (count == 1L) "" else "s"

    private fun pad(value: Long): String = if (value < 10L) "0" + value else value.toString()

    /** The pack's separator: a middle dot with a space either side. */
    const val DOT: String = " · "

    /** What the sleep-timer chip says when nothing is armed, which is its usual state. */
    const val SLEEP_TIMER_OFF: String = "Sleep timer"

    /** What a timecode shows when the length is not known. */
    const val UNKNOWN_TIME: String = "--:--"
}
