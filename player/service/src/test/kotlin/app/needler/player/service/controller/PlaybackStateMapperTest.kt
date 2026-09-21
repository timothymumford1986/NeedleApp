package app.needler.player.service.controller

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.playback.RepeatMode
import app.needler.player.service.Fixtures
import app.needler.player.service.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session's state as the whole app sees it, and the crate row ids everything is addressed by. */
class PlaybackStateMapperTest {

    private val row = QueueItem(id = "1@" + Fixtures.key().canonicalString, track = Fixtures.track())

    /**
     * The rule that keeps the transport from looking broken on a slow connection: a track re-buffering
     * mid-stream is still "playing" as far as the button is concerned. Flipping to a play icon on every stall
     * is how a player reads as broken.
     */
    @Test
    fun `buffering mid-stream is still playing`() {
        val state = PlaybackStateMapper.toState(
            SessionSnapshot(
                currentItem = row,
                status = PlayerStatus.BUFFERING,
                playWhenReady = true,
            ),
        )

        assertTrue(state.isPlaying)
        assertTrue(state.isBuffering)
    }

    @Test
    fun `a paused player is not playing`() {
        val state = PlaybackStateMapper.toState(
            SessionSnapshot(currentItem = row, status = PlayerStatus.READY, playWhenReady = false),
        )

        assertFalse(state.isPlaying)
        assertFalse(state.isBuffering)
    }

    @Test
    fun `an idle or ended player is not playing whatever playWhenReady says`() {
        for (status in listOf(PlayerStatus.IDLE, PlayerStatus.ENDED)) {
            val state = PlaybackStateMapper.toState(
                SessionSnapshot(currentItem = row, status = status, playWhenReady = true),
            )
            assertFalse(status.name, state.isPlaying)
        }
    }

    @Test
    fun `an unknown duration is null rather than zero`() {
        assertNull(
            PlaybackStateMapper.toState(SessionSnapshot(durationMs = 0L)).durationMs,
        )
        assertNull(
            PlaybackStateMapper.toState(SessionSnapshot(durationMs = -9_223_372_036_854_775_807L)).durationMs,
        )
        assertEquals(
            240_000L,
            PlaybackStateMapper.toState(SessionSnapshot(durationMs = 240_000L)).durationMs,
        )
    }

    @Test
    fun `the modes, the output and the error come straight through`() {
        val state = PlaybackStateMapper.toState(
            SessionSnapshot(
                currentItem = row,
                status = PlayerStatus.READY,
                playWhenReady = true,
                shuffleEnabled = true,
                repeatMode = RepeatMode.ONE,
                speed = PlaybackSpeed(1.5f),
                output = OutputTarget.ThisDevice("This phone"),
                error = NeedlerError.StreamSlotsExhausted,
            ),
        )

        assertTrue(state.shuffleEnabled)
        assertEquals(RepeatMode.ONE, state.repeatMode)
        assertEquals(1.5f, state.speed.value, 0f)
        assertEquals("This phone", state.output?.displayName)
        assertEquals(NeedlerError.StreamSlotsExhausted, state.error)
        assertTrue(state.hasCurrentItem)
    }

    /** Drawing a creeping buffer bar over a file that is already on disk would be a lie. */
    @Test
    fun `a local file is fully buffered by definition`() {
        val progress = PlaybackStateMapper.toProgress(
            positionMs = 10_000L,
            bufferedPositionMs = 12_000L,
            durationMs = 240_000L,
            playingFromLocalFile = true,
        )

        assertEquals(240_000L, progress.bufferedPositionMs)
        assertEquals(10_000L, progress.positionMs)
        assertEquals(0.0416f, progress.fractionOf(240_000L)!!, 0.001f)
    }

    @Test
    fun `a stream reports what it has actually buffered`() {
        val progress = PlaybackStateMapper.toProgress(
            positionMs = 10_000L,
            bufferedPositionMs = 12_000L,
            durationMs = 240_000L,
            playingFromLocalFile = false,
        )

        assertEquals(12_000L, progress.bufferedPositionMs)
    }

    @Test
    fun `repeat modes round-trip through the player's own constants`() {
        for (mode in RepeatMode.entries) {
            assertEquals(
                mode,
                PlaybackStateMapper.toDomainRepeatMode(PlaybackStateMapper.toPlayerRepeatMode(mode)),
            )
        }
        assertEquals(RepeatMode.OFF, PlaybackStateMapper.toDomainRepeatMode(99))
    }

    @Test
    fun `player states map from the four constants`() {
        assertEquals(PlayerStatus.IDLE, PlayerStatus.fromPlayerState(1))
        assertEquals(PlayerStatus.BUFFERING, PlayerStatus.fromPlayerState(2))
        assertEquals(PlayerStatus.READY, PlayerStatus.fromPlayerState(3))
        assertEquals(PlayerStatus.ENDED, PlayerStatus.fromPlayerState(4))
        assertEquals(PlayerStatus.IDLE, PlayerStatus.fromPlayerState(0))
    }

    // ------------------------------------------------------------------- crate rows

    /**
     * The crate can hold the same track twice - add-to-crate twice, or a playlist that repeats one - and
     * `removeQueueItem(itemId)` addresses a row by id. With the bare track id as the row id, removing the
     * second copy would remove the first and the user would watch the wrong row disappear.
     */
    @Test
    fun `two copies of the same track get different row ids`() {
        val builder = QueueBuilder()
        val rows = builder.rowsFor(listOf(Fixtures.track(), Fixtures.track()))

        assertNotEquals(rows[0].id, rows[1].id)
        assertEquals(Fixtures.key(), MediaId.toTrackKey(rows[0].id))
        assertEquals(Fixtures.key(), MediaId.toTrackKey(rows[1].id))
    }

    @Test
    fun `row ids are never reused within a session`() {
        val builder = QueueBuilder()
        val first = builder.rowsFor(List(3) { Fixtures.track(number = it + 1) })
        val second = builder.rowsFor(List(3) { Fixtures.track(number = it + 1) })

        assertEquals(6, (first + second).map { it.id }.distinct().size)
    }

    @Test
    fun `an out-of-range current index is dropped rather than trusted`() {
        val builder = QueueBuilder()
        val rows = builder.rowsFor(listOf(Fixtures.track()))

        assertTrue(builder.queueOf(rows, 0).currentIndex == 0)
        assertNull(builder.queueOf(rows, 7).currentIndex)
        assertNull(builder.queueOf(emptyList(), 0).currentIndex)
    }
}
