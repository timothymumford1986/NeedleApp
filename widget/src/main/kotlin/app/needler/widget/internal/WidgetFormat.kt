package app.needler.widget.internal

import app.needler.core.domain.model.QueueItem
import kotlin.math.roundToInt

/**
 * The strings the widgets print, and the one they read aloud.
 *
 * `:feature:player` has the timecode and the artist line already, in `PlayerFormat`, and
 * `:feature:pulls` has the percentage in `PullsFormat`. This is not an improvement on either - it is
 * a copy of the handful of functions the three widgets need. Feature modules are not each other's
 * dependencies (REQUIREMENTS.md "Architecture > Modules"), and hoisting a few lines of string
 * formatting into `:core:domain` to share them would put presentation in the layer that exists to
 * hold none. A few small functions restated is the cheaper of the two mistakes.
 *
 * They are kept byte-identical to the originals on purpose. A widget and the screen it is a glance
 * at are often in front of a user within seconds of each other, and someone who sees `3:20` in one
 * and `03:20` in the other has found a bug even though neither is wrong. If `PlayerFormat` or
 * `PullsFormat` changes, this changes.
 */
internal object WidgetFormat {

    /** The pack's separator between artist and album, as `PlayerFormat.DOT`. */
    private const val DOT: String = " · "

    /** An unknown timecode, as `PlayerFormat.UNKNOWN_TIME`. Must match `R.string.widget_unknown_time`. */
    const val UNKNOWN_TIME: String = "--:--"

    /**
     * `62%`, the pull card's one number, or null when the server has not said.
     *
     * Rounded rather than truncated, and rounded the same way `PullsFormat.percent` rounds it on the
     * Pulls screen, so a pull at 61.6 percent does not read 62 in the app and 61 on the home screen.
     * The fraction it is given has already preferred the server's own `progress_percent` over a byte
     * or file count - see `PullProgress.fraction` - so this function does no deciding of its own.
     *
     * Null rather than `0%` for a pull the server is still searching for. A percentage is a claim
     * about how far along something is, and a pull with no candidate chosen is not zero percent
     * downloaded; it has not started. See `PullCardModel` for what the card draws instead.
     */
    fun percent(fraction: Float?): String? {
        val value: Float = fraction ?: return null
        return (value.coerceIn(0f, 1f) * 100f).roundToInt().toString() + "%"
    }

    /** `Searching · Black Classical Music`: the pack's own separator, joining whatever is present. */
    fun withSeparator(first: String?, second: String?): String {
        val left: String? = first?.takeIf { it.isNotBlank() }
        val right: String? = second?.takeIf { it.isNotBlank() }
        return when {
            left == null -> right.orEmpty()
            right == null -> left
            else -> left + DOT + right
        }
    }

    /**
     * A position or duration as the pack prints it: `1:16`, `21:04`, `1:02:03`.
     *
     * Unknown and negative lengths render as `--:--` rather than `0:00`, because an unknown length
     * is a real state - a track whose duration the server never reported - and a widget claiming
     * `0:00` for it is a lie a user has no way to see through.
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

    /** `The Marías · Submarine`, the pack's own second line. Falls back to the artist alone. */
    fun artistAndAlbum(item: QueueItem?): String {
        val track = item?.track ?: return ""
        val album: String? = track.albumTitle?.takeIf { it.isNotBlank() }
        return if (album == null) track.artistName else track.artistName + DOT + album
    }

    /**
     * Artwork alt text in the pack's own wording: `Submarine by The Marías`.
     *
     * Mirrors `:core:design`'s `albumArtContentDescription`, including its null case: when there is
     * nothing useful to say the caller should keep the image out of the accessibility tree rather
     * than announce "image".
     */
    fun artworkDescription(item: QueueItem?): String? {
        val track = item?.track ?: return null
        val album: String? = track.albumTitle?.takeIf { it.isNotBlank() }
        val artist: String? = track.artistName.takeIf { it.isNotBlank() }
        return when {
            album == null && artist == null -> null
            artist == null -> album
            album == null -> artist
            else -> album + " by " + artist
        }
    }

    private fun pad(value: Long): String = if (value < 10L) "0" + value else value.toString()
}
