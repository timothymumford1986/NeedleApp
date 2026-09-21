package app.needler.feature.player.fake

import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [PlaybackController] with no session behind it.
 *
 * The same idea as `:app`'s `FakeSessionRepository` for Connect: the view models under test talk to
 * a domain interface, so testing them needs a hand-written implementation of that interface rather
 * than a fake Media3 - which is one of the three consequences `PlaybackController` says it exists
 * for ("testing a player screen with no session running... needs no fake Media3").
 *
 * The three flows are three flows here too, because the view models depend on their independence:
 * pushing a queue must not emit a state, and pushing progress must not emit either.
 *
 * Commands are recorded in [commands] and, where it makes the test readable, applied to the state -
 * `playPause` really does flip `isPlaying`, and `moveQueueItem` really does apply
 * [PlayQueue.withItemMoved], which is what the session's implementation is required to do.
 */
class FakePlaybackController(
    initialState: PlaybackState = PlaybackState.Idle,
    initialQueue: PlayQueue = PlayQueue.Empty,
    initialProgress: PlaybackProgress = PlaybackProgress.Zero,
) : PlaybackController {

    private val stateFlow = MutableStateFlow(initialState)
    private val queueFlow = MutableStateFlow(initialQueue)
    private val progressFlow = MutableStateFlow(initialProgress)

    /** Every command sent, in order, as a readable string. */
    val commands: MutableList<String> = mutableListOf()

    /** Set to true to make [moveQueueItem] record the command without applying it. */
    var echoMoves: Boolean = true

    override fun observeState(): Flow<PlaybackState> = stateFlow.asStateFlow()

    override fun observeProgress(): Flow<PlaybackProgress> = progressFlow.asStateFlow()

    override fun observeQueue(): Flow<PlayQueue> = queueFlow.asStateFlow()

    override suspend fun currentState(): PlaybackState = stateFlow.value

    override suspend fun play() {
        commands += "play"
        stateFlow.value = stateFlow.value.copy(isPlaying = true)
    }

    override suspend fun pause() {
        commands += "pause"
        stateFlow.value = stateFlow.value.copy(isPlaying = false)
    }

    override suspend fun playPause() {
        commands += "playPause"
        stateFlow.value = stateFlow.value.copy(isPlaying = !stateFlow.value.isPlaying)
    }

    override suspend fun seekTo(positionMs: Long) {
        commands += "seekTo(" + positionMs + ")"
        progressFlow.value = progressFlow.value.copy(positionMs = positionMs)
    }

    override suspend fun skipToNext() {
        commands += "skipToNext"
    }

    override suspend fun skipToPrevious() {
        commands += "skipToPrevious"
    }

    override suspend fun skipToQueueItem(itemId: String) {
        commands += "skipToQueueItem(" + itemId + ")"
    }

    override suspend fun playAlbum(mbid: ReleaseGroupMbid, startIndex: Int, shuffle: Boolean) {
        commands += "playAlbum(" + mbid.value + "," + startIndex + "," + shuffle + ")"
    }

    override suspend fun playTracks(tracks: List<Track>, startIndex: Int) {
        commands += "playTracks(" + tracks.size + "," + startIndex + ")"
    }

    override suspend fun enqueue(tracks: List<Track>, playNext: Boolean) {
        commands += "enqueue(" + tracks.size + "," + playNext + ")"
    }

    override suspend fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        commands += "moveQueueItem(" + fromIndex + "," + toIndex + ")"
        // The real implementation is required to use this helper rather than recomputing the
        // current index, so the fake does too: a test that passed against arithmetic here would be
        // testing the wrong thing.
        if (echoMoves) queueFlow.value = queueFlow.value.withItemMoved(fromIndex, toIndex)
    }

    override suspend fun removeQueueItem(itemId: String) {
        commands += "removeQueueItem(" + itemId + ")"
        queueFlow.value = queueFlow.value.withItemRemoved(itemId)
    }

    override suspend fun clearQueue() {
        commands += "clearQueue"
        queueFlow.value = PlayQueue.Empty
        stateFlow.value = PlaybackState.Idle
    }

    override suspend fun setShuffleEnabled(enabled: Boolean) {
        commands += "setShuffleEnabled(" + enabled + ")"
        stateFlow.value = stateFlow.value.copy(shuffleEnabled = enabled)
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        commands += "setRepeatMode(" + mode + ")"
        stateFlow.value = stateFlow.value.copy(repeatMode = mode)
    }

    override suspend fun setPlaybackSpeed(speed: PlaybackSpeed) {
        commands += "setPlaybackSpeed(" + speed.value + ")"
        stateFlow.value = stateFlow.value.copy(speed = speed)
    }

    override suspend fun stop() {
        commands += "stop"
        stateFlow.value = stateFlow.value.copy(isPlaying = false)
    }

    // ---- what a test drives from the other side ----------------------------

    fun emitState(state: PlaybackState) {
        stateFlow.value = state
    }

    fun emitQueue(queue: PlayQueue) {
        queueFlow.value = queue
    }

    fun emitProgress(progress: PlaybackProgress) {
        progressFlow.value = progress
    }
}
