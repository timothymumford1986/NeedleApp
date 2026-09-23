package app.needler.wear.playback

/**
 * Everything the watch renders, as one closed set of states.
 *
 * ## Why this is not `PlaybackState`
 *
 * `:core:domain`'s [app.needler.core.domain.playback.PlaybackState] is the phone's model and the
 * watch deliberately does not reuse it. It hangs off `QueueItem` -> `Track` -> `TrackFetchHandle`,
 * which carries stream URLs, quality badges, MBIDs and `Instant`s; none of that means anything on a
 * watch, and all of it would have to be flattened onto a `DataMap`, which is a bag of primitives.
 * Reusing the domain type would therefore not save a mapping - it would add one, and put the
 * temptation to send a whole `Track` across a Bluetooth link within easy reach.
 *
 * What *is* shared is the vocabulary: the fields below are named after the `PlaybackState` fields
 * they mirror, and [WearPlaybackProtocol] documents each mapping, so the two models can be read
 * side by side.
 *
 * ## Four states, because a watch has to admit when it cannot see the phone
 *
 * The phone app never needs [PhoneUnreachable] - it *is* the phone. The watch does, and collapsing it
 * into [Idle] is the single most common way a companion app lies to its user: a dead Bluetooth link
 * and a paused player look identical, so the user presses play on a transport that is shouting into
 * the void. REQUIREMENTS.md makes the general form of this rule for Cast - "say why in the output
 * picker rather than failing after the user picks a speaker" - and the same principle applies here.
 *
 * [Connecting] is separated from both for the same honesty: the first read of a retained data item is
 * fast but not instant, and an empty "Nothing playing" screen that flicks to a track a moment later
 * reads as a bug.
 */
sealed interface WearPlaybackState {

    /**
     * Before the first answer. The retained data item has not been read yet and the node check has
     * not come back.
     *
     * This is the initial value of the state holder rather than a value the client emits late, so
     * the very first composed frame is already correct.
     */
    data object Connecting : WearPlaybackState

    /**
     * No paired node is connected, so nothing the watch does can reach the session.
     *
     * The transport is not drawn in this state. A disabled button the user can still press is worse
     * than no button, because pressing it produces no feedback of any kind - the message simply is
     * not delivered.
     */
    data object PhoneUnreachable : WearPlaybackState

    /**
     * The phone answered and has nothing loaded: `PlaybackState.hasCurrentItem` is false.
     *
     * Reached either from a snapshot with [WearPlaybackProtocol.KEY_HAS_ITEM] false, or from the
     * phone deleting the data item altogether.
     */
    data object Idle : WearPlaybackState

    /**
     * Something is loaded. Mirrors the fields of `PlaybackState` that a watch can act on.
     *
     * @param artworkId the identifier from [WearPlaybackProtocol.KEY_ARTWORK_ID], or null when the
     *   phone sent no artwork. It is an *identifier*, not an image: the bytes arrive over a separate
     *   asynchronous hop, so the screen renders its placeholder immediately and the artwork fades in
     *   if and when it resolves. A watch showing three lines of text and no cover is a normal,
     *   complete state, not a loading state.
     */
    data class NowPlaying(
        val title: String,
        val artist: String,
        val album: String?,
        val isPlaying: Boolean,
        val isBuffering: Boolean,
        val artworkId: String?,
    ) : WearPlaybackState
}
