package app.needler.player.service.media

import app.needler.player.service.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoding every surface addresses a track and a crate row by.
 *
 * It is the canonical key and never the `file_id`: a quality upgrade moves the `file_id` while the record, disc
 * and track stay where they were, and a session holding file ids would lose its place the moment the server
 * replaced a file.
 */
class MediaIdTest {

    private val key = Fixtures.key(disc = 2, track = 7)

    @Test
    fun `a track id is the canonical key`() {
        assertEquals(Fixtures.ALBUM_A + "/2/7", MediaId.forTrack(key))
        assertEquals(key, MediaId.toTrackKey(MediaId.forTrack(key)))
    }

    @Test
    fun `a crate row id carries a sequence and still decodes to the track`() {
        val rowId = MediaId.forQueueRow(key, sequence = 42L)

        assertEquals("42@" + Fixtures.ALBUM_A + "/2/7", rowId)
        assertEquals(key, MediaId.toTrackKey(rowId))
        assertEquals(42L, MediaId.rowSequenceOf(rowId))
        assertNull(MediaId.rowSequenceOf(MediaId.forTrack(key)))
    }

    /**
     * A mediaId can arrive from another process - a resumed platform session, a stale Auto tab, a Wear app
     * built against an older version - and an unparseable one is a row to drop, not a crash in the service.
     */
    @Test
    fun `anything unparseable decodes to null rather than throwing`() {
        assertNull(MediaId.toTrackKey(null))
        assertNull(MediaId.toTrackKey(""))
        assertNull(MediaId.toTrackKey("not-a-key"))
        assertNull(MediaId.toTrackKey("album/only"))
        assertNull(MediaId.toTrackKey("album/1/2/3"))
        assertNull(MediaId.toTrackKey("album/x/2"))
        assertNull(MediaId.toTrackKey("album/0/2"))
        assertNull(MediaId.toTrackKey("album/1/0"))
        assertNull(MediaId.toTrackKey(" /1/2"))
    }

    @Test
    fun `a browse id is never mistaken for a track`() {
        assertTrue(MediaId.isBrowseId(MediaId.BROWSE_ROOT))
        assertNull(MediaId.toTrackKey(MediaId.BROWSE_ROOT))
        assertFalse(MediaId.isBrowseId(MediaId.forTrack(key)))
    }

    /**
     * The queue carries an opaque URI rather than a stream URL. A stream URL carries the app-password, is
     * bundled to every controller of the session, and would freeze the cached-or-stream decision at enqueue
     * time - so a queue built on Wi-Fi would still ask for original FLAC after the phone moved to mobile data.
     */
    @Test
    fun `a track URI round-trips and rejects anything else`() {
        val uri = TrackUri.forTrack(key)

        assertEquals("needler://track/" + Fixtures.ALBUM_A + "/2/7", uri)
        assertTrue(TrackUri.isTrackUri(uri))
        assertEquals(key, TrackUri.toTrackKey(uri))
        assertNull(TrackUri.toTrackKey("https://music.example/stream?id=tr-1"))
        assertNull(TrackUri.toTrackKey(null))
        assertFalse(TrackUri.isTrackUri("file:///data/audio/a.flac"))
    }
}
