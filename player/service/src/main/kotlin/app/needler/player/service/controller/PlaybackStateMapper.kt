package app.needler.player.service.controller

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.QueueItemSource
import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.playback.RepeatMode
import app.needler.player.service.media.MediaId

/**
 * The session's playback state as four plain values, so the mapping to domain types is testable without
 * a running `MediaController`.
 *
 * `Player`'s own state is three fields that have to be read together - `playbackState`, `playWhenReady`
 * and `isPlaying` - and the interesting rule is what happens when they disagree. That rule is in
 * [PlaybackStateMapper], and this is its input.
 */
public data class SessionSnapshot(
    public val currentItem: QueueItem? = null,
    /** `Player.STATE_*`, narrowed to the four that exist. */
    public val status: PlayerStatus = PlayerStatus.IDLE,
    /** `playWhenReady`: the transport button's position, not whether sound is coming out. */
    public val playWhenReady: Boolean = false,
    public val durationMs: Long? = null,
    public val shuffleEnabled: Boolean = false,
    public val repeatMode: RepeatMode = RepeatMode.OFF,
    public val speed: PlaybackSpeed = PlaybackSpeed.Normal,
    public val output: OutputTarget? = null,
    public val error: NeedlerError? = null,
)

/** `Player.STATE_*` as an enum, so a `when` over it is exhaustive. */
public enum class PlayerStatus {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ;

    public companion object {
        /** Mirrors `Player.STATE_IDLE`, `STATE_BUFFERING`, `STATE_READY`, `STATE_ENDED` in that order. */
        public fun fromPlayerState(state: Int): PlayerStatus = when (state) {
            PLAYER_STATE_BUFFERING -> BUFFERING
            PLAYER_STATE_READY -> READY
            PLAYER_STATE_ENDED -> ENDED
            else -> IDLE
        }

        internal const val PLAYER_STATE_IDLE: Int = 1
        internal const val PLAYER_STATE_BUFFERING: Int = 2
        internal const val PLAYER_STATE_READY: Int = 3
        internal const val PLAYER_STATE_ENDED: Int = 4
    }
}

/**
 * Builds the domain [PlaybackState] the whole app renders from.
 *
 * ## Buffering is not the opposite of playing
 *
 * `Player.isPlaying` goes false the instant a stream stalls, and a transport bound to it flips to a play
 * icon every time a train goes through a tunnel. That reads as broken. So `isPlaying` here means "the
 * user has asked for sound and nothing has stopped it", which is `playWhenReady` on a player that is not
 * idle or ended, and a stall shows up on `isBuffering` instead - the spinner over the button, not the
 * button.
 */
public object PlaybackStateMapper {

    public fun toState(snapshot: SessionSnapshot): PlaybackState = PlaybackState(
        currentItem = snapshot.currentItem,
        isPlaying = snapshot.playWhenReady &&
            snapshot.status != PlayerStatus.IDLE &&
            snapshot.status != PlayerStatus.ENDED,
        isBuffering = snapshot.status == PlayerStatus.BUFFERING,
        durationMs = snapshot.durationMs?.takeIf { it > 0L },
        shuffleEnabled = snapshot.shuffleEnabled,
        repeatMode = snapshot.repeatMode,
        speed = snapshot.speed,
        output = snapshot.output,
        error = snapshot.error,
    )

    /**
     * Progress, with the local-file rule applied.
     *
     * For a track playing from the on-device store the buffered position is the duration: the bytes are
     * already there, and drawing a creeping buffer bar over a local file would be a lie.
     */
    public fun toProgress(
        positionMs: Long,
        bufferedPositionMs: Long,
        durationMs: Long?,
        playingFromLocalFile: Boolean,
    ): PlaybackProgress = PlaybackProgress(
        positionMs = positionMs.coerceAtLeast(0L),
        bufferedPositionMs = when {
            playingFromLocalFile && durationMs != null && durationMs > 0L -> durationMs
            else -> maxOf(bufferedPositionMs, positionMs).coerceAtLeast(0L)
        },
    )

    /** Domain repeat mode to `Player.REPEAT_MODE_*`. */
    public fun toPlayerRepeatMode(mode: RepeatMode): Int = when (mode) {
        RepeatMode.OFF -> REPEAT_MODE_OFF
        RepeatMode.ONE -> REPEAT_MODE_ONE
        RepeatMode.ALL -> REPEAT_MODE_ALL
    }

    /** `Player.REPEAT_MODE_*` to domain repeat mode. Anything unrecognised is off. */
    public fun toDomainRepeatMode(playerMode: Int): RepeatMode = when (playerMode) {
        REPEAT_MODE_ONE -> RepeatMode.ONE
        REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    /** Mirrors `Player.REPEAT_MODE_OFF`. */
    public const val REPEAT_MODE_OFF: Int = 0

    /** Mirrors `Player.REPEAT_MODE_ONE`. */
    public const val REPEAT_MODE_ONE: Int = 1

    /** Mirrors `Player.REPEAT_MODE_ALL`. */
    public const val REPEAT_MODE_ALL: Int = 2
}

/**
 * Builds the domain [PlayQueue] from what the session holds, and hands out the row ids the crate is
 * addressed by.
 *
 * The sequence counter is per instance and monotonic. It is never reused inside a session, so a row id is
 * stable across reorders, removals and the same track appearing twice - which is exactly what
 * `removeQueueItem(itemId)` and `skipToQueueItem(itemId)` need in order not to act on the wrong row.
 */
public class QueueBuilder {

    private var nextSequence: Long = 1L

    /** Wraps [tracks] as fresh crate rows, each with its own id. */
    public fun rowsFor(
        tracks: List<Track>,
        source: QueueItemSource = QueueItemSource.USER,
    ): List<QueueItem> = tracks.map { track ->
        QueueItem(
            id = MediaId.forQueueRow(track.key, nextSequence++),
            track = track,
            source = source,
        )
    }

    /** The queue as the session holds it: rows in order, with the index of the one playing. */
    public fun queueOf(rows: List<QueueItem>, currentIndex: Int?): PlayQueue = PlayQueue(
        items = rows,
        currentIndex = currentIndex?.takeIf { it in rows.indices },
    )

    /** The current sequence, so a test can assert ids do not repeat. */
    public val sequenceWatermark: Long get() = nextSequence
}
