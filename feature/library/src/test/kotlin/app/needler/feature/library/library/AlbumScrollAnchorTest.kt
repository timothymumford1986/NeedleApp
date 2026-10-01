package app.needler.feature.library.library

import app.needler.core.domain.model.Album
import app.needler.feature.library.SampleLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The grid/list toggle used to throw away the user's place: scrolled to "RIOT!"
 * in a 288-album library, "Switch to list view" landed on the first album of the
 * sort.
 *
 * [AlbumScrollAnchor] is what carries that place across, and these are the cases
 * that make it correct rather than merely present — above all that the anchor is
 * the *album*, so it survives the list changing underneath it, which an index
 * would not.
 */
class AlbumScrollAnchorTest {

    private val albums: List<Album> = SampleLibrary.albums

    @Test
    fun `a fresh anchor opens at the top`() {
        assertNull(AlbumScrollAnchor().albumKey)
        assertEquals(0, AlbumScrollAnchor().indexIn(albums))
    }

    @Test
    fun `the album at the top of one layout is where the other opens`() {
        val anchor = AlbumScrollAnchor()

        anchor.record(albums, firstVisibleItemIndex = 6)

        assertEquals(SampleLibrary.albumMbid("flyte").value, anchor.albumKey)
        assertEquals(6, anchor.indexIn(albums))
    }

    /**
     * The point of anchoring on identity. Four columns of artwork and one column
     * of rows put the same album at different indices only if the list changes;
     * what *does* change under this is a sync, and an index anchor would then
     * point at whatever had moved into that slot.
     */
    @Test
    fun `the anchor follows its album when the list shifts underneath it`() {
        val anchor = AlbumScrollAnchor()
        anchor.record(albums, firstVisibleItemIndex = 6)

        // A delta sync lands two newly added albums at the head of `type=newest`.
        val afterSync: List<Album> = listOf(
            SampleLibrary.album(slug = "new-1", title = "New One", artistName = "Someone"),
            SampleLibrary.album(slug = "new-2", title = "New Two", artistName = "Someone"),
        ) + albums

        assertEquals(8, anchor.indexIn(afterSync))
    }

    @Test
    fun `an album that has left the list opens at the top rather than at a neighbour`() {
        val anchor = AlbumScrollAnchor()
        anchor.record(albums, firstVisibleItemIndex = 6)

        val withoutIt: List<Album> =
            albums.filterNot { it.releaseGroupMbid == SampleLibrary.albumMbid("flyte") }

        assertEquals(0, anchor.indexIn(withoutIt))
    }

    @Test
    fun `recording the first album is the same as having no anchor`() {
        val anchor = AlbumScrollAnchor()

        anchor.record(albums, firstVisibleItemIndex = 0)

        assertEquals(0, anchor.indexIn(albums))
    }

    /**
     * Both come from a layout that is mid-scroll over a list a sync can shorten,
     * so neither is worth an exception.
     */
    @Test
    fun `an index past the end, and an empty list, clear the anchor instead of throwing`() {
        val anchor = AlbumScrollAnchor()
        anchor.record(albums, firstVisibleItemIndex = 6)

        anchor.record(albums, firstVisibleItemIndex = albums.size + 40)
        assertNull(anchor.albumKey)

        anchor.record(albums, firstVisibleItemIndex = 6)
        anchor.record(emptyList(), firstVisibleItemIndex = 0)
        assertNull(anchor.albumKey)
        assertEquals(0, anchor.indexIn(albums))
    }

    @Test
    fun `an anchor can be constructed at an album, which is what the screenshots render`() {
        val anchor = AlbumScrollAnchor(SampleLibrary.albumMbid("heaven").value)

        assertEquals(7, anchor.indexIn(albums))
    }
}
