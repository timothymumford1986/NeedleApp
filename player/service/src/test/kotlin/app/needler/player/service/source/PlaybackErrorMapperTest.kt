package app.needler.player.service.source

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a user is told when a track will not play.
 *
 * The rule that matters is the first three: REQUIREMENTS.md treats a timeout or a DNS failure as *offline*,
 * not as a server error. The mirror and cached audio still work, and telling someone their server is broken
 * when their train went into a tunnel is both wrong and unhelpful.
 */
class PlaybackErrorMapperTest {

    @Test
    fun `DNS failure is offline`() {
        assertEquals(
            NeedlerError.Offline(OfflineCause.DNS_FAILURE),
            PlaybackErrorMapper.fromTransport(UnknownHostException("music.example")),
        )
    }

    @Test
    fun `timeout is offline`() {
        assertEquals(
            NeedlerError.Offline(OfflineCause.TIMEOUT),
            PlaybackErrorMapper.fromTransport(SocketTimeoutException()),
        )
    }

    @Test
    fun `a refused connection is offline`() {
        assertEquals(
            NeedlerError.Offline(OfflineCause.CONNECTION_FAILED),
            PlaybackErrorMapper.fromTransport(ConnectException()),
        )
    }

    /** A cached row naming a file that is gone. Retryable: the fix is to refetch, not to report corruption. */
    @Test
    fun `a missing cached file is not found`() {
        assertTrue(
            PlaybackErrorMapper.fromTransport(FileNotFoundException("/data/audio/a.flac"))
                is NeedlerError.NotFound,
        )
    }

    @Test
    fun `any other IO failure is treated as offline rather than as a bug`() {
        assertEquals(
            NeedlerError.Offline(OfflineCause.CONNECTION_FAILED),
            PlaybackErrorMapper.fromTransport(IOException("socket closed")),
        )
    }

    @Test
    fun `an HTTP failure shares the retry policy's table`() {
        assertEquals(NeedlerError.SessionExpired, PlaybackErrorMapper.fromHttp(HttpFailure(401)))
        assertEquals(NeedlerError.RangeNotSatisfiable, PlaybackErrorMapper.fromHttp(HttpFailure(416)))
        assertEquals(
            NeedlerError.StreamSlotsExhausted,
            PlaybackErrorMapper.fromHttp(HttpFailure(429)),
        )
    }

    @Test
    fun `the domain reason survives being wrapped`() {
        val wrapped = RuntimeException(
            "loader",
            IOException(NeedlerPlaybackException(NeedlerError.StreamSlotsExhausted)),
        )

        assertEquals(
            NeedlerError.StreamSlotsExhausted,
            NeedlerPlaybackException.errorIn(wrapped),
        )
    }

    @Test
    fun `an unrelated throwable carries no domain reason`() {
        assertNull(NeedlerPlaybackException.errorIn(IllegalStateException("nope")))
        assertNull(NeedlerPlaybackException.errorIn(null))
    }
}
