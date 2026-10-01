package app.needler.wear.playback

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow

/**
 * The watch's view of the phone's playback session, and the module's one seam.
 *
 * This is the Wear analogue of `:core:domain`'s `PlaybackController`, and it is an interface for the
 * same reason that one is: so that the screen renders state and calls methods, and knows nothing
 * about Google Play services, `DataMap`s or `Asset`s. Everything above this line is testable without
 * a watch, a phone or a paired emulator; everything below it is untestable without all three.
 *
 * The shape is deliberately narrower than `PlaybackController`. There is no seek, no reorder, no
 * remove, no clear, no shuffle, no repeat and no speed, because none of those are drawn on this watch
 * - see [WearPlaybackProtocol] for what was left off the wire and why.
 *
 * ## Commands return `Unit`
 *
 * Copied from `PlaybackController`, and for the same reason plus one more. On the phone, "a failure
 * surfaces on `PlaybackState.error` instead, because the session is shared". On the watch there is a
 * second reason: a data-layer message has no meaningful result. `MessageClient.sendMessage` resolves
 * when Google Play services has accepted the message for delivery, not when the phone acted on it, so
 * a `Result` here would report the wrong thing confidently. The honest signal that a command worked
 * is the next snapshot arriving with a changed [WearPlaybackState].
 */
interface WearPlaybackClient {

    /**
     * The session as the watch can see it, starting from the retained snapshot and then following
     * every change the phone publishes.
     *
     * Cold: collection registers a data-layer listener and cancellation removes it. The caller
     * therefore controls the battery cost by controlling when it collects - see
     * `NeedlerWearActivity`, which collects only between `onStart` and `onStop`.
     *
     * Collecting this is also what asks the phone to publish at all. See
     * [WearPlaybackProtocol.PATH_REQUEST_STATE]: the phone does not observe its own session until a
     * watch says it is looking, so nothing else in this interface produces fresh state on its own.
     */
    fun observe(): Flow<WearPlaybackState>

    /**
     * The crate, as far down it as the phone published.
     *
     * A second flow rather than a field on [WearPlaybackState], mirroring the split
     * `PlaybackController` makes between `observeState` and `observeQueue` and for the same reason:
     * the transport changes several times a song and the crate changes when somebody edits it, so
     * folding them together would re-diff the list on every play and pause.
     *
     * Cold, like [observe], but it does **not** ask the phone for a publishing window of its own. It
     * rides the one [observe] asks for, because the crate screen is reachable only from the
     * now-playing screen and the host collects both for as long as either is on screen. Collecting
     * this alone would read the retained crate item and then never see it change.
     */
    fun observeCrate(): Flow<WearCrateState>

    /**
     * The artwork for [artworkId], or null when there is none, when the bytes have not arrived, or
     * when the transfer failed.
     *
     * Separate from [observe] because it is a second, slow, optional hop: an `Asset`'s bytes are
     * fetched lazily and may take a moment over Bluetooth. Folding it into the state flow would hold
     * the title and the transport hostage to an image transfer.
     *
     * Null is an ordinary answer, not an error. The screen draws its record placeholder and stays
     * complete.
     */
    suspend fun loadArtwork(artworkId: String): ImageBitmap?

    /** The transport's primary button. See [WearPlaybackProtocol.PATH_PLAY_PAUSE]. */
    suspend fun playPause()

    /** See [WearPlaybackProtocol.PATH_NEXT]. */
    suspend fun skipToNext()

    /** See [WearPlaybackProtocol.PATH_PREVIOUS]. */
    suspend fun skipToPrevious()

    /**
     * Jumps to one row of the crate. See [WearPlaybackProtocol.PATH_SKIP_TO_ROW].
     *
     * [rowId] is a [WearCrateRow.id], which is `QueueItem.id` on the phone. A row that has left the
     * crate since the watch drew it matches nothing and nothing happens, which is the correct
     * outcome - the alternative is playing whatever moved into that position.
     */
    suspend fun skipToRow(rowId: String)
}
