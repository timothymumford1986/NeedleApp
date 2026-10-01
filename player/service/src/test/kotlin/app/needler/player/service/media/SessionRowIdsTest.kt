package app.needler.player.service.media

import app.needler.player.service.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row ids the session mints for items that arrive without one.
 *
 * The rule being protected is the one [MediaId.forQueueRow] exists for: two rows of the crate may hold the same
 * track and must still be addressable apart, or removing the second copy removes the first. An album selected
 * in Android Auto arrives as one media id and becomes twelve rows, so the ids have to be minted here.
 */
class SessionRowIdsTest {

    private val rowIds = SessionRowIds()

    /** A queue the app built keeps the ids that screen is already addressing rows by. */
    @Test
    fun `a row id for the same track is kept`() {
        val key = Fixtures.key()
        val existing = MediaId.forQueueRow(key, sequence = 4L)

        assertEquals(existing, rowIds.rowIdFor(existing, key))
    }

    @Test
    fun `a browse id or a bare track id gets a row id of its own`() {
        val key = Fixtures.key(disc = 2, track = 7)

        val fromBrowse = rowIds.rowIdFor(MediaId.forAlbum(key.releaseGroupMbid), key)
        val fromTrackId = rowIds.rowIdFor(MediaId.forTrack(key), key)

        assertEquals(key, MediaId.toTrackKey(fromBrowse))
        assertEquals(key, MediaId.toTrackKey(fromTrackId))
        assertNotEquals(fromBrowse, fromTrackId)
    }

    /**
     * Negative on purpose. `QueueBuilder` counts up from 1 in the app's process and this counts down from -1 in
     * the service, so the two cannot mint the same row id for the same track without ever talking to each other.
     */
    @Test
    fun `session-minted sequences are negative and never repeat`() {
        val key = Fixtures.key()

        val sequences: List<Long?> = (1..5).map { MediaId.rowSequenceOf(rowIds.rowIdFor(null, key)) }

        assertTrue(sequences.toString(), sequences.all { it != null && it < 0L })
        assertEquals(sequences.size, sequences.distinct().size)
    }

    /** An id that is a row for some *other* track is not a row id for this one. */
    @Test
    fun `a row id belonging to another track is not reused`() {
        val key = Fixtures.key()
        val otherRow = MediaId.forQueueRow(Fixtures.key(album = Fixtures.ALBUM_B), sequence = 9L)

        val minted = rowIds.rowIdFor(otherRow, key)

        assertNotEquals(otherRow, minted)
        assertEquals(key, MediaId.toTrackKey(minted))
    }
}
