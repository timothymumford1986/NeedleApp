package app.needler.core.data.mapper

import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subsonic's type-prefixed ids, taken apart and put back together.
 *
 * This is the join between the two server lanes and the reason one `Album` can describe both an
 * owned album and a catalogue-only one: strip `al-` and what is left is exactly the key
 * `POST /api/v1/requests/new` accepts. Every one of these assertions is a bug the moment the strip
 * is done at a call site instead of here.
 */
public class SubsonicIdsTest {

    private val releaseGroup = "d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f1"
    private val artist = "aa11bb22-cc33-dd44-ee55-ff6677889900"

    // ------------------------------------------------------------- round trips

    @Test
    public fun `an album id round-trips through the release-group MBID`() {
        val id: String = SubsonicIds.albumId(ReleaseGroupMbid(releaseGroup))

        assertEquals("al-" + releaseGroup, id)
        assertEquals(releaseGroup, SubsonicIds.releaseGroupMbid(id)?.value)
    }

    @Test
    public fun `a bare MBID is accepted where an album id is expected`() {
        // The request lane hands back bare MBIDs and the Subsonic lane hands back prefixed ones; a
        // mapper that accepted only one of those would drop half the catalogue.
        assertEquals(releaseGroup, SubsonicIds.releaseGroupMbid(releaseGroup)?.value)
    }

    @Test
    public fun `an artist id round-trips`() {
        val id: String = SubsonicIds.artistId(ArtistMbid(artist))

        assertEquals("ar-" + artist, id)
        assertEquals(artist, SubsonicIds.artistMbid(id)?.value)
    }

    @Test
    public fun `a track id round-trips through the file id`() {
        val id: String = SubsonicIds.trackId(FileId("8801"))

        assertEquals("tr-8801", id)
        assertEquals("8801", SubsonicIds.fileId(id)?.value)
    }

    @Test
    public fun `a playlist id round-trips`() {
        assertEquals("pl-42", SubsonicIds.playlistId(PlaylistId("42")))
        assertEquals("42", SubsonicIds.playlistIdOrNull("pl-42")?.value)
    }

    @Test
    public fun `a genre id round-trips through its slug`() {
        assertEquals("ge-post-rock", SubsonicIds.genreId("post-rock"))
        assertEquals("post-rock", SubsonicIds.genreSlug("ge-post-rock"))
    }

    @Test
    public fun `building an id is idempotent, so a double prefix is impossible`() {
        // Every call site that already holds a prefixed id can pass it through without checking.
        assertEquals("al-" + releaseGroup, SubsonicIds.albumId("al-" + releaseGroup))
        assertEquals("tr-8801", SubsonicIds.trackId("tr-8801"))
    }

    // ------------------------------------------------------------------ absence

    @Test
    public fun `an empty id is null rather than a blank identity`() {
        // The value classes refuse a blank value, so a mapper that did not check would throw on a
        // server that omitted the field rather than skipping the row.
        assertNull(SubsonicIds.releaseGroupMbid(""))
        assertNull(SubsonicIds.releaseGroupMbid("al-"))
        assertNull(SubsonicIds.releaseGroupMbid(null))
        assertNull(SubsonicIds.fileId("tr-"))
        assertNull(SubsonicIds.artistMbid("  "))
    }

    @Test
    public fun `the fallback is used when the primary id is missing`() {
        // Album entries carry both `id` and `musicBrainzId`; a server that stops sending one still
        // maps, which is what keeps the join key available.
        assertEquals(releaseGroup, SubsonicIds.releaseGroupMbid(null, releaseGroup)?.value)
        assertEquals(releaseGroup, SubsonicIds.releaseGroupMbid("", "al-" + releaseGroup)?.value)
    }

    // ------------------------------------------------------------------ routing

    @Test
    public fun `ids route by prefix, which is how a mixed star call is split`() {
        val album: String = SubsonicIds.albumId(ReleaseGroupMbid(releaseGroup))
        val artistId: String = SubsonicIds.artistId(ArtistMbid(artist))
        val track: String = SubsonicIds.trackId(FileId("8801"))

        assertTrue(SubsonicIds.isAlbumId(album))
        assertFalse(SubsonicIds.isArtistId(album))

        assertTrue(SubsonicIds.isArtistId(artistId))
        assertFalse(SubsonicIds.isTrackId(artistId))

        assertTrue(SubsonicIds.isTrackId(track))
        assertFalse(SubsonicIds.isAlbumId(track))
    }
}
