package app.needler.feature.player.ui

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.playback.RepeatMode

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

    /** What a timecode shows when the length is not known. */
    const val UNKNOWN_TIME: String = "--:--"
}
