package app.needler.widget.internal

import app.needler.core.domain.model.QueueItem

/**
 * The three strings the now-playing widget prints, and the one it reads aloud.
 *
 * `:feature:player` has all of this already, in `PlayerFormat`, and this is not an improvement on
 * it - it is a copy of the two functions the widget needs. Feature modules are not each other's
 * dependencies (REQUIREMENTS.md "Architecture > Modules"), and hoisting a handful of string
 * formatting into `:core:domain` to share it would put presentation in the layer that exists to hold
 * none. Two small functions restated is the cheaper of the two mistakes.
 *
 * They are kept byte-identical to `PlayerFormat`'s on purpose. The widget and the mini player are
 * often on screen within seconds of each other, and a user who sees `3:20` in one and `03:20` in
 * the other has found a bug even though neither is wrong. If `PlayerFormat` changes, this changes.
 */
internal object WidgetFormat {

    /** The pack's separator between artist and album, as `PlayerFormat.DOT`. */
    private const val DOT: String = " · "

    /** An unknown timecode, as `PlayerFormat.UNKNOWN_TIME`. Must match `R.string.widget_unknown_time`. */
    const val UNKNOWN_TIME: String = "--:--"

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
