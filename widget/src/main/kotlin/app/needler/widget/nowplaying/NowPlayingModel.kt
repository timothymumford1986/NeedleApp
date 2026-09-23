package app.needler.widget.nowplaying

import android.graphics.Bitmap
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.widget.internal.WidgetFormat

/**
 * Everything the now-playing card draws, with the work already done.
 *
 * It is `PlaybackState` plus the strings, the fraction and the decoded cover, for the same reason
 * `:feature:player` has `PlayerUiState`: a Glance composable is the worst possible place to do
 * anything that can fail or block. Composition happens inside a Glance session whose cancellation is
 * not under our control, and everything it produces has to survive a trip through `RemoteViews`, so
 * the fetch, the decode, the crop and the formatting all happen before this type exists.
 *
 * ## Why the cover is a Bitmap and not an ArtworkRef
 *
 * Because the launcher cannot resolve one. See `WidgetArtwork` for the whole argument; the short
 * version is that `androidx.glance.ImageProvider` accepts pixels, not intentions.
 *
 * ## Why position is in here at all
 *
 * `PlaybackController` splits the ticking half of playback into its own flow precisely so a screen
 * bound to the state does not recompose several times a second, and `PlayerUiState` honours that by
 * refusing to hold a position. This model does hold one, and the difference is deliberate: a widget
 * cannot subscribe to a 4 Hz flow, because every emission is a `RemoteViews` rebuilt and pushed
 * across a Binder into the launcher. REQUIREMENTS.md "Widgets" still asks the now-playing widget for
 * "artwork, title, artist, position and transport controls", so the position arrives here as a
 * periodic *sample* rather than a subscription - see `NowPlayingWidget.nowPlayingModels`. The bar is
 * therefore accurate at every moment a person changed something and slightly behind in between,
 * which is the honest trade and is called out in the widget's KDoc rather than hidden.
 */
internal data class NowPlayingModel(
    /** False when the session has nothing loaded: the empty state the card draws instead. */
    val hasTrack: Boolean = false,
    /** The track title. Empty in the empty state, where the card prints its own copy. */
    val title: String = "",
    /** `The Marías · Submarine`, or the artist alone when the server reported no album. */
    val subtitle: String = "",
    /** `Submarine by The Marías`, or null when there is nothing worth announcing. */
    val artworkDescription: String? = null,
    /** The cropped, rounded cover, or null for the placeholder tint. */
    val cover: Bitmap? = null,
    /**
     * Whether the transport shows Pause rather than Play.
     *
     * Taken from `PlaybackState.isPlaying`, which is deliberately not the negation of buffering: a
     * track re-buffering mid-stream is still playing as far as this button is concerned, and
     * flipping the glyph on every stall is how a player looks broken on a slow connection.
     */
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    /** Null while the session does not know the length - a stream, or a track the server never measured. */
    val durationMs: Long? = null,
) {

    /** The left-hand timecode, `1:16`. */
    val elapsed: String get() = if (hasTrack) WidgetFormat.timecode(positionMs) else WidgetFormat.UNKNOWN_TIME

    /** The right-hand timecode, `3:20`, or `--:--` when the length is unknown. */
    val total: String get() = WidgetFormat.timecode(durationMs)

    /**
     * How much of the bar is filled, 0f..1f, or null when there is nothing to measure against.
     *
     * Null draws an empty track rather than a full or a zero one, which is the same choice
     * `PlayerFormat.timecode` makes for a length it does not know.
     */
    val fraction: Float?
        get() {
            val length: Long = durationMs ?: return null
            if (length <= 0L) return null
            return (positionMs.toFloat() / length.toFloat()).coerceIn(0f, 1f)
        }

    companion object {

        /** Nothing loaded: what the card draws before the session answers, and whenever it is idle. */
        val Idle: NowPlayingModel = NowPlayingModel()

        /**
         * Folds a session state and a position sample into the card's own shape.
         *
         * [cover] is passed in rather than derived, because fetching it is a suspending network
         * call and this has to stay a pure function - it is the only part of the widget that can be
         * exercised without a launcher, a session or a server.
         */
        fun of(state: PlaybackState, progress: PlaybackProgress, cover: Bitmap?): NowPlayingModel {
            val item = state.currentItem ?: return Idle
            return NowPlayingModel(
                hasTrack = true,
                title = item.track.title,
                subtitle = WidgetFormat.artistAndAlbum(item),
                artworkDescription = WidgetFormat.artworkDescription(item),
                cover = cover,
                isPlaying = state.isPlaying,
                positionMs = progress.positionMs,
                durationMs = state.durationMs ?: item.track.durationMs,
            )
        }
    }
}
