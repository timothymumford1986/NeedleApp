package app.needler.widget

import app.needler.widget.recent.RecentlyAddedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fold from one album onto the recently added card.
 *
 * Worth testing on its own for the same reason `NowPlayingModelTest` is: it is the only part of that
 * widget that can be exercised without a launcher, a Hilt graph and a server, because everything above
 * it is a `RemoteViews`.
 *
 * Three of the cases below are the ones that go wrong quietly - an empty mirror that draws a Play disc
 * with no album for it to play, an album whose identity does not survive into the tap target (on a
 * device, a card that opens the wrong record), and an album the mirror holds no title for, which draws
 * a blank line that is indistinguishable from a broken widget.
 */
class RecentlyAddedModelTest {

    @Test
    fun `an empty mirror is the empty state, with nothing to play`() {
        val model = RecentlyAddedModel.of(album = null, cover = null)

        assertEquals(RecentlyAddedModel.Empty, model)
        assertFalse(model.hasAlbum)
        assertFalse(model.canPlay)
        assertEquals("", model.releaseGroupMbid)
        assertNull(model.artworkDescription)
    }

    @Test
    fun `the newest album is the card the pack draws`() {
        // design/html/15-Widget.html: Mordechai, Khruangbin.
        val model = RecentlyAddedModel.of(WidgetFixtures.mordechai, cover = null)

        assertTrue(model.hasAlbum)
        assertTrue(model.canPlay)
        assertEquals("Mordechai", model.title)
        assertEquals("Khruangbin", model.artistName)
    }

    @Test
    fun `the album's identity survives onto the tap target`() {
        // The card opens this album and the disc plays it, and both carry the bare MBID rather than
        // the Subsonic `al-` form: a route and an ActionParameters value are strings, and a prefix
        // leaking into either is an album screen that finds nothing.
        val model = RecentlyAddedModel.of(WidgetFixtures.mordechai, cover = null)

        assertEquals(WidgetFixtures.mordechai.releaseGroupMbid.value, model.releaseGroupMbid)
        assertFalse(model.releaseGroupMbid.startsWith("al-"))
    }

    @Test
    fun `artwork is described the way the pack's alt text reads`() {
        // The pack's own alt on the recently added cover: alt="Mordechai by Khruangbin".
        val model = RecentlyAddedModel.of(WidgetFixtures.mordechai, cover = null)

        assertEquals("Mordechai by Khruangbin", model.artworkDescription)
    }

    @Test
    fun `an album the mirror holds no title for is flagged rather than drawn blank`() {
        // The same guard the pull card needs, and for the same reason: a blank text slot on a home
        // screen looks broken and tells the user nothing, so the card draws "Untitled album" instead.
        val model = RecentlyAddedModel.of(WidgetFixtures.untitledRecord, cover = null)

        assertTrue(model.hasAlbum)
        // Whitespace in, empty out, so the render site's one check is enough.
        assertEquals("", model.title)
        assertFalse(model.hasTitle)
        // Still playable and still a link: thin metadata is not a broken album.
        assertTrue(model.canPlay)
        assertTrue(model.hasArtist)
        // With no title the alt text falls back to the artist rather than reading " by Yussef Dayes".
        assertEquals("Yussef Dayes", model.artworkDescription)
    }

    @Test
    fun `padded metadata reaches the card trimmed`() {
        val album = WidgetFixtures.submarine.copy(title = "  Submarine  ", artistName = "  Khruangbin ")

        val model = RecentlyAddedModel.of(album, cover = null)

        assertEquals("Submarine", model.title)
        assertEquals("Khruangbin", model.artistName)
        // And the alt text too, so TalkBack does not read "Submarine   by Khruangbin".
        assertEquals("Submarine by Khruangbin", model.artworkDescription)
        assertTrue(model.hasTitle)
        assertTrue(model.hasArtist)
    }

    @Test
    fun `an album the server described without an artist still announces itself`() {
        val album = WidgetFixtures.submarine.copy(artistName = "")

        val model = RecentlyAddedModel.of(album, cover = null)

        // The title alone, rather than "Submarine by " with nothing after it.
        assertEquals("Submarine", model.artworkDescription)
        assertEquals("", model.artistName)
        // So the card draws "Unknown artist" - LibraryFormat.UNKNOWN_ARTIST - rather than an empty line.
        assertFalse(model.hasArtist)
        // Still playable and still a link: a missing artist name is a thin sync, not a broken album.
        assertTrue(model.canPlay)
    }

    @Test
    fun `an album with nothing to name stays out of the accessibility tree`() {
        // Matches WidgetFormat.artworkDescription's null case and :core:design's
        // albumArtContentDescription: with nothing useful to say, announce nothing rather than "image".
        val album = WidgetFixtures.submarine.copy(title = "", artistName = "   ")

        val model = RecentlyAddedModel.of(album, cover = null)

        assertNull(model.artworkDescription)
        assertFalse(model.hasTitle)
        assertFalse(model.hasArtist)
    }
}
