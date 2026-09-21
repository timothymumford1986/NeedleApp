package app.needler.player.service.source

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.StreamFormat
import app.needler.player.service.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cached-versus-stream-versus-nothing decision, and the write-through rule that rides on it. */
class SourcePlannerTest {

    @Test
    fun `cached bytes play from the local file`() {
        val plan = SourcePlanner.plan(
            PlayableSource.Cached(
                key = Fixtures.key(),
                filePath = "/data/audio/a.flac",
                sizeBytes = 1_234L,
                pinned = true,
            ),
        )

        val local = plan as SourcePlan.LocalFile
        assertEquals("/data/audio/a.flac", local.filePath)
        assertTrue(local.pinned)
    }

    @Test
    fun `an original stream is write-through`() {
        val plan = SourcePlanner.plan(
            PlayableSource.Stream(
                key = Fixtures.key(),
                fetchHandle = Fixtures.handle(),
                format = StreamFormat.Original,
                cacheWhileStreaming = true,
            ),
        )

        val stream = plan as SourcePlan.HttpStream
        assertTrue(stream.writeThrough)
        assertFalse(stream.isTranscode)
    }

    /**
     * The rule REQUIREMENTS.md spends a whole section on: a 320 kbps rendering of a FLAC must never become that
     * track's permanent offline copy. The resolver sets the flag; nothing downstream may override it.
     */
    @Test
    fun `a transcode is never write-through`() {
        val plan = SourcePlanner.plan(
            PlayableSource.Stream(
                key = Fixtures.key(),
                fetchHandle = Fixtures.handle(),
                format = StreamFormat.Transcoded.Mp3_320,
                cacheWhileStreaming = false,
            ),
        )

        val stream = plan as SourcePlan.HttpStream
        assertFalse(stream.writeThrough)
        assertTrue(stream.isTranscode)
    }

    @Test
    fun `offline with nothing on the device is an offline error, not a fault`() {
        val plan = SourcePlanner.plan(PlayableSource.UnavailableOffline(Fixtures.key()))

        val unplayable = plan as SourcePlan.NotPlayable
        assertTrue(unplayable.error is NeedlerError.Offline)
        assertTrue(unplayable.error.isRetryable)
    }

    @Test
    fun `a modelled failure keeps its reason`() {
        val plan = SourcePlanner.plan(
            PlayableSource.Unavailable(Fixtures.key(), NeedlerError.SubsonicProtocolDisabled),
        )

        assertEquals(
            NeedlerError.SubsonicProtocolDisabled,
            (plan as SourcePlan.NotPlayable).error,
        )
    }

    // ------------------------------------------------------------------ write-through

    private fun stream(writeThrough: Boolean): SourcePlan.HttpStream = SourcePlan.HttpStream(
        key = Fixtures.key(),
        fetchHandle = Fixtures.handle(),
        format = StreamFormat.Original,
        writeThrough = writeThrough,
    )

    @Test
    fun `a read from byte zero may be written through`() {
        assertTrue(SourcePlanner.mayWriteThrough(stream(writeThrough = true), readPosition = 0L))
    }

    /**
     * Seeking into an un-cached track streams without retaining. A handle appends from the start, so a read
     * that begins anywhere else cannot produce a complete file - and keeping the tail of a track as though it
     * were the track is the same silent failure as keeping a truncated one.
     */
    @Test
    fun `a read that starts mid-track is never written through`() {
        assertFalse(SourcePlanner.mayWriteThrough(stream(writeThrough = true), readPosition = 4_096L))
    }

    @Test
    fun `a local file is never written through`() {
        val local = SourcePlan.LocalFile(Fixtures.key(), "/data/a.flac", 10L, pinned = false)
        assertFalse(SourcePlanner.mayWriteThrough(local, readPosition = 0L))
    }
}
