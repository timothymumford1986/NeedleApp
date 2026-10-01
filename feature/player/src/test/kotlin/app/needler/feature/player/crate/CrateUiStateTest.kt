package app.needler.feature.player.crate

import app.needler.core.domain.model.PlayQueue
import app.needler.feature.player.fake.PlayerFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a row of the crate says, and when it stops saying the same thing eleven times.
 *
 * The device audit: an eleven-track crate of one album repeated an identical subtitle and thumbnail on
 * every row. A second line that repeats carries no information, and TalkBack read the pair out each
 * time, so a listener heard "The Marias, Submarine" eleven times to find out which track was fourth.
 *
 * The decision is a property of the whole crate rather than of a row - eleven rows must not be able to
 * disagree about which form they are in - so it is asserted here, on the state, rather than through a
 * render.
 */
class CrateUiStateTest {

    @Test
    fun `a crate of one record prints the running order`() {
        val state = CrateUiState(queue = PlayerFixtures.albumCrate)

        assertTrue(state.isOneRecord)
        assertFalse("one record means one cover, drawn above the crate", state.showsRowArtwork)
        assertEquals(
            listOf("Track 1", "Track 2", "Track 3", "Track 4"),
            PlayerFixtures.albumCrate.items.map { state.rowSubtitle(it) },
        )
    }

    /** The regression itself: four rows, four different second lines. */
    @Test
    fun `no two rows of a one-record crate say the same thing`() {
        val state = CrateUiState(queue = PlayerFixtures.albumCrate)
        val subtitles: List<String> = PlayerFixtures.albumCrate.items.map { state.rowSubtitle(it) }

        assertEquals(subtitles.size, subtitles.distinct().size)
    }

    @Test
    fun `the pack's own mixed crate keeps the artist and the album`() {
        val state = CrateUiState(queue = PlayerFixtures.crate)

        assertFalse("four records is not one record", state.isOneRecord)
        assertTrue(state.showsRowArtwork)
        assertEquals(
            "The Marias · Submarine",
            state.rowSubtitle(PlayerFixtures.crate.items.first()),
        )
    }

    @Test
    fun `a double album names the disc, so Track 1 does not appear twice`() {
        val state = CrateUiState(queue = PlayerFixtures.doubleAlbumCrate)

        assertTrue(state.isOneRecord)
        assertTrue(state.spansDiscs)
        assertEquals(
            listOf("Disc 1, track 1", "Disc 2, track 1"),
            PlayerFixtures.doubleAlbumCrate.items.map { state.rowSubtitle(it) },
        )
    }

    /**
     * A track that does not know its place in the record cannot exist, so [CrateUiState.isOneRecord]
     * does not guard for one.
     *
     * `TrackKey` `require`s `trackNumber >= 1` in its init block. A guard was written for the
     * no-track-numbers case and removed when the fixture for it would not construct; this asserts the
     * invariant that makes the guard unnecessary, so that removing it from `TrackKey` fails here rather
     * than putting "Track 0" on a row.
     */
    @Test
    fun `a track with no place in the record cannot be built at all`() {
        val key = PlayerFixtures.sienna.key
        assertThrows(IllegalArgumentException::class.java) { key.copy(trackNumber = 0) }
        assertThrows(IllegalArgumentException::class.java) { key.copy(discNumber = 0) }
    }

    @Test
    fun `a crate of one row has nothing to repeat`() {
        val state = CrateUiState(
            queue = PlayQueue(items = listOf(PlayerFixtures.playingItem), currentIndex = 0),
        )

        assertFalse(state.isOneRecord)
        assertEquals("The Marias · Submarine", state.rowSubtitle(PlayerFixtures.playingItem))
    }

    @Test
    fun `an empty crate answers without throwing`() {
        assertFalse(CrateUiState.Empty.isOneRecord)
        assertFalse(CrateUiState.Empty.spansDiscs)
        assertTrue(CrateUiState.Empty.showsRowArtwork)
    }

    /**
     * Two records can share a title, so the identity is what the grouping reads - the same field the
     * rest of the app joins albums on.
     */
    @Test
    fun `the grouping is by release group, not by album title`() {
        val sameTitleDifferentRecord = PlayerFixtures.sienna.copy(
            albumTitle = "Mordechai",
            artistName = "Khruangbin",
        )
        val state = CrateUiState(
            queue = PlayQueue(
                items = listOf(
                    PlayerFixtures.item("x1", PlayerFixtures.pelota),
                    PlayerFixtures.item("x2", sameTitleDifferentRecord),
                ),
                currentIndex = 0,
            ),
        )

        assertFalse(
            "two records titled Mordechai are still two records",
            state.isOneRecord,
        )
    }
}
