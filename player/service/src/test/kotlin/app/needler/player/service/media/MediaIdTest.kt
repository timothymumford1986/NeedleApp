package app.needler.player.service.media

import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid
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
     * Every level of the Auto tree has to survive the round trip, because `onGetChildren` is handed nothing
     * but the id it gave out and has to decide the whole branch from it.
     */
    @Test
    fun `every browse node id decodes back to its node`() {
        val fixed: Map<String, BrowseNode> = mapOf(
            MediaId.BROWSE_ROOT to BrowseNode.Root,
            MediaId.BROWSE_LIBRARY to BrowseNode.Library,
            MediaId.BROWSE_ALBUMS to BrowseNode.Albums,
            MediaId.BROWSE_ARTISTS to BrowseNode.Artists,
            MediaId.BROWSE_SONGS to BrowseNode.Songs,
            MediaId.BROWSE_GENRES to BrowseNode.Genres,
            MediaId.BROWSE_RECENTLY_ADDED to BrowseNode.RecentlyAdded,
            MediaId.BROWSE_PLAYLISTS to BrowseNode.Playlists,
            MediaId.BROWSE_FAVOURITES to BrowseNode.Favourites,
            MediaId.BROWSE_ON_DEVICE to BrowseNode.OnDevice,
        )

        for ((mediaId, node) in fixed) {
            assertEquals(mediaId, node, MediaId.toBrowseNode(mediaId))
            assertTrue(mediaId, MediaId.isBrowseId(mediaId))
            assertNull(mediaId, MediaId.toTrackKey(mediaId))
        }
    }

    /** Identities travel bare, exactly as REQUIREMENTS.md "Identity model" stores them. */
    @Test
    fun `a node that names one thing carries that thing's identity without a Subsonic prefix`() {
        val album = ReleaseGroupMbid(Fixtures.ALBUM_A)
        val artist = ArtistMbid(Fixtures.ARTIST_A)
        val playlist = PlaylistId("7")

        assertEquals("needler:album/" + Fixtures.ALBUM_A, MediaId.forAlbum(album))
        assertEquals(BrowseNode.OneAlbum(album), MediaId.toBrowseNode(MediaId.forAlbum(album)))
        assertEquals(BrowseNode.OneArtist(artist), MediaId.toBrowseNode(MediaId.forArtist(artist)))
        assertEquals(BrowseNode.OnePlaylist(playlist), MediaId.toBrowseNode(MediaId.forPlaylist(playlist)))
    }

    /**
     * A genre name is user data: it has spaces, ampersands and slashes in it. The id is parsed as prefix plus
     * tail rather than split on the separator precisely so none of that needs escaping.
     */
    @Test
    fun `a genre id round-trips a name containing a slash`() {
        for (name in listOf("Jazz", "Drum & Bass", "Hip-Hop/Rap", "Folk, World, & Country")) {
            assertEquals(name, BrowseNode.OneGenre(name), MediaId.toBrowseNode(MediaId.forGenre(name)))
        }
    }

    /**
     * Auto keeps ids across app updates - a tab it had open, a shortcut a user pinned - so a node this build
     * does not have is a normal event, and the answer to it is an empty list rather than an error in a car.
     */
    @Test
    fun `an unknown or empty browse id decodes to null`() {
        assertNull(MediaId.toBrowseNode("needler:pulls"))
        assertNull(MediaId.toBrowseNode("needler:album/"))
        assertNull(MediaId.toBrowseNode("needler:genre/   "))
        assertNull(MediaId.toBrowseNode(MediaId.forTrack(key)))
        assertNull(MediaId.toBrowseNode(MediaId.forQueueRow(key, sequence = 3L)))
        assertNull(MediaId.toBrowseNode(null))
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
