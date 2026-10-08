package app.needler.feature.library.artist

import app.needler.core.domain.model.AlbumState
import app.needler.feature.library.SampleLibrary
import app.needler.feature.library.common.LibraryFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Who a discography row says the record is by.
 *
 * ## The defect
 *
 * The real Dido's page listed forty releases with artwork and Pull buttons - the feature working -
 * and all but one subtitle read **"Unknown artist · 1999"**. The exception was "No Angel", the one
 * record that also existed in the mirror from an earlier search and therefore already had a name on
 * its row.
 *
 * `GET /api/v1/artists/{mbid}/releases` sends no artist with a discography item: `ReleaseItemDto`
 * has no such field. The repository substitutes the mirror's artist row, and `refreshArtistDiscography`
 * writes albums and never an artist row, so for an artist reached through the catalogue there is
 * nothing to substitute and the name is stored blank. The row then drew the placeholder for a blank
 * one - on a screen whose header was printing the name at that moment.
 *
 * ## What is asserted
 *
 * That the placeholder is gone, and that it is gone the right way round: the row says nothing about
 * the artist when the credit is the page's own or when nothing at all is known, leads with the year,
 * and keeps a credit the catalogue *did* send when it disagrees with the page - a collaboration is
 * the one case where the row's artist is news.
 */
class ArtistDiscographyCreditTest {

    private val dido = SampleLibrary.album(
        slug = "highbury-fields",
        title = "The Highbury Fields EP",
        // Exactly what the catalogue lane stores for a release group on an artist's own page: no
        // credit, because the endpoint sent none and the mirror had no artist row to borrow from.
        artistName = "",
        year = 1999,
        state = AlbumState.NotOwned,
    )

    @Test
    fun `a release with no credit says nothing about the artist whose page it is on`() {
        val state = ArtistUiState(loading = false, knownName = "Dido")

        // The heading above these rows is the word "Dido". Repeating it forty times down the
        // narrowest column on the screen is what pushed the year off the row at 200% text.
        assertEquals("1999", discographyRowSubtitle(dido, state.creditedName))
    }

    /**
     * The same when the name comes from the mirror rather than from the tap.
     *
     * The two paths differ - `artist` is the mirror's row, `knownName` the hint the route carried -
     * and the row must not care which of them named the artist. Either way it is the page's own
     * artist and the row stays quiet about it.
     */
    @Test
    fun `the mirror's own artist row is dropped from the rows just as well`() {
        val state = ArtistUiState(
            loading = false,
            artist = SampleLibrary.marias,
        )

        assertEquals("1999", discographyRowSubtitle(dido, state.creditedName))
    }

    /** The defect itself: the string that must never appear on one of these rows again. */
    @Test
    fun `no discography row ever reads Unknown artist`() {
        val pageNames = listOf("Dido", "", "   ", null)
        val rows = listOf(dido, dido.copy(artistName = "Faithless"), dido.copy(year = null))

        for (name in pageNames) {
            val state = ArtistUiState(loading = false, knownName = name)
            for (row in rows) {
                val subtitle: String = discographyRowSubtitle(row, state.creditedName)
                assertFalse(subtitle, subtitle.contains(LibraryFormat.UNKNOWN_ARTIST))
            }
        }
    }

    /**
     * With nothing known anywhere the artist is dropped, not placeheld.
     *
     * A row subtitled with the year alone reads fine. One subtitled "Unknown artist" asserts
     * ignorance about a name the screen may well be displaying two inches above it.
     */
    @Test
    fun `an artist nobody has named leaves the row reading as the year`() {
        val state = ArtistUiState(loading = false, mbid = SampleLibrary.marias.mbid)

        assertNull(state.creditedName)
        assertEquals("1999", discographyRowSubtitle(dido, state.creditedName))
    }

    /** Neither a credit nor a year: an empty subtitle, which the screen also keeps out of the label. */
    @Test
    fun `a row with neither a credit nor a year has an empty subtitle`() {
        assertEquals("", discographyRowSubtitle(dido.copy(year = null), null))
    }

    /**
     * A credit the catalogue *did* send wins, even on another artist's page.
     *
     * A discography holds collaborations and various-artists records. Overwriting "Faithless" with
     * "Dido" on her page would be inventing a fact rather than filling a gap, which is a worse
     * failure than the one being fixed.
     */
    @Test
    fun `a release that carries its own credit keeps it`() {
        val state = ArtistUiState(loading = false, knownName = "Dido")

        assertEquals(
            "1999 · Faithless",
            discographyRowSubtitle(dido.copy(artistName = "Faithless"), state.creditedName),
        )
    }

    /** A blank page name is not a name. `knownName = ""` reached the header as a hint once. */
    @Test
    fun `a blank page name counts as no name`() {
        assertNull(ArtistUiState(loading = false, knownName = "   ").creditedName)
        assertEquals("1999", discographyRowSubtitle(dido, "   "))
    }
}
