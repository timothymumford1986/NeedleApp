package app.needler.widget

import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.PlaybackState
import app.needler.widget.internal.WidgetFormat
import app.needler.widget.nowplaying.NowPlayingModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fold from a session state onto the card.
 *
 * Worth testing on its own because it is the only part of the widget that can be exercised at all
 * without a launcher, a Media3 session and a server - everything above it is a `RemoteViews`. Three
 * of the cases below are the ones that go wrong quietly: an empty session that draws a transport
 * anyway, a track whose length the server never reported drawing a full or an empty bar as though
 * it knew, and a position that has overshot the reported duration as a track changes over.
 */
class NowPlayingModelTest {

    @Test
    fun `nothing loaded is the empty state, whatever else the session says`() {
        val state = PlaybackState(currentItem = null, isPlaying = true, durationMs = 200_000L)

        val model = NowPlayingModel.of(state, PlaybackProgress(positionMs = 76_000L), cover = null)

        assertEquals(NowPlayingModel.Idle, model)
        assertFalse(model.hasTrack)
        assertFalse(model.isPlaying)
        assertNull(model.fraction)
    }

    @Test
    fun `a playing track is the card the pack draws`() {
        // design/html/15-Widget.html: Sienna, The Marías · Submarine, 1:16 of 3:20, Pause showing.
        val state = PlaybackState(
            currentItem = WidgetFixtures.sienna,
            isPlaying = true,
            durationMs = 200_000L,
        )

        val model = NowPlayingModel.of(state, PlaybackProgress(positionMs = 76_000L), cover = null)

        assertTrue(model.hasTrack)
        assertTrue(model.isPlaying)
        assertEquals("Sienna", model.title)
        assertEquals("The Marías · Submarine", model.subtitle)
        assertEquals("1:16", model.elapsed)
        assertEquals("3:20", model.total)
        assertEquals(0.38f, model.fraction!!, 0.005f)
    }

    @Test
    fun `the session's length wins, and the track's is the fallback`() {
        val item = WidgetFixtures.sienna

        val fromSession = NowPlayingModel.of(
            PlaybackState(currentItem = item, durationMs = 123_000L),
            PlaybackProgress.Zero,
            cover = null,
        )
        assertEquals(123_000L, fromSession.durationMs)

        // The session has not measured the stream yet; the mirror already knows how long it is.
        val fromTrack = NowPlayingModel.of(
            PlaybackState(currentItem = item, durationMs = null),
            PlaybackProgress.Zero,
            cover = null,
        )
        assertEquals(item.track.durationMs, fromTrack.durationMs)
    }

    @Test
    fun `an unknown length draws no bar and prints no length`() {
        val item = WidgetFixtures.untitledAlbum.let { existing ->
            existing.copy(track = existing.track.copy(durationMs = null))
        }

        val model = NowPlayingModel.of(
            PlaybackState(currentItem = item, durationMs = null),
            PlaybackProgress(positionMs = 30_000L),
            cover = null,
        )

        assertNull(model.fraction)
        assertEquals(WidgetFormat.UNKNOWN_TIME, model.total)
        // The position itself is still known, so it is still printed.
        assertEquals("0:30", model.elapsed)
    }

    @Test
    fun `a position past the end does not overfill the bar`() {
        // A position can overshoot the reported duration as a track changes over.
        val model = NowPlayingModel.of(
            PlaybackState(currentItem = WidgetFixtures.sienna, durationMs = 200_000L),
            PlaybackProgress(positionMs = 210_000L),
            cover = null,
        )

        assertEquals(1f, model.fraction!!, 0.0001f)
    }

    @Test
    fun `buffering mid-stream does not flip the transport back to play`() {
        // PlaybackState.isBuffering is deliberately not the opposite of isPlaying: a track
        // re-buffering is still playing as far as this button is concerned.
        val model = NowPlayingModel.of(
            PlaybackState(currentItem = WidgetFixtures.sienna, isPlaying = true, isBuffering = true),
            PlaybackProgress.Zero,
            cover = null,
        )

        assertTrue(model.isPlaying)
    }
}
