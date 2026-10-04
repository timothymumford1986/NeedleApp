package app.needler.core.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The genre column's encoding, and the splitting that was missing from it.
 *
 * The device reported 138 genres with `Acoustic Rock;Alternative Rock;Folk Rock;Indie Rock;Pop Rock`
 * among them as a single row owning a single album. Three things were wrong at once - an inflated
 * count, an unreadable row, and five real genres with no bucket - and all three came from one field
 * being stored whole.
 *
 * What is pinned here is therefore both halves of the decision: that the semicolon *is* split on,
 * and that the slash and the comma are *not*. The second half is the one with no symptom to notice
 * when it breaks: splitting "Hip-Hop/Rap" invents a genre called "Rap" that no album is tagged with
 * and loses the one they are.
 */
public class GenreCodecTest {

    // ----------------------------------------------------------------- splitting

    @Test
    public fun `a semicolon-joined field names every genre in it`() {
        assertEquals(
            listOf("Acoustic Rock", "Alternative Rock", "Folk Rock", "Indie Rock", "Pop Rock"),
            GenreCodec.splitComposite("Acoustic Rock;Alternative Rock;Folk Rock;Indie Rock;Pop Rock"),
        )
    }

    @Test
    public fun `the pieces are trimmed and the empty ones dropped`() {
        assertEquals(listOf("Rock", "Pop"), GenreCodec.splitComposite(" Rock ; ; Pop ;"))
    }

    @Test
    public fun `a single genre survives the split untouched`() {
        assertEquals(listOf("Post-Rock"), GenreCodec.splitComposite("Post-Rock"))
    }

    /**
     * A slash is part of a name on this server, not a separator.
     *
     * `MediaId` and `BrowseNode` are both written around round-tripping a genre called "Hip-Hop/Rap",
     * and `GenresRoute` encodes the route argument for "Rock/Pop". Splitting on it would turn one
     * bucket the library has into two it does not.
     */
    @Test
    public fun `a slash inside a genre name is not a separator`() {
        assertEquals(listOf("Hip-Hop/Rap"), GenreCodec.splitComposite("Hip-Hop/Rap"))
    }

    /**
     * Nor is a comma.
     *
     * "Folk, World, & Country" is a real genre, and the comma is also how a sort name and a band
     * name are punctuated - so a server that files "Crosby, Stills & Nash" under `genre` would turn
     * into three genres the moment the comma was treated as a separator.
     */
    @Test
    public fun `a comma inside a genre name is not a separator`() {
        assertEquals(
            listOf("Folk, World, & Country"),
            GenreCodec.splitComposite("Folk, World, & Country"),
        )
    }

    // -------------------------------------------------------------------- folding

    @Test
    public fun `case and whitespace variants fold to one key`() {
        assertEquals(GenreCodec.fold("Indie Rock"), GenreCodec.fold("indie rock"))
        assertEquals(GenreCodec.fold("Indie Rock"), GenreCodec.fold("  INDIE   ROCK  "))
    }

    /** Punctuation is kept, or "R&B" and "IDM" fold into things nobody is tagged with. */
    @Test
    public fun `folding keeps punctuation`() {
        assertEquals("r&b", GenreCodec.fold("R&B"))
    }

    // ------------------------------------------------------------------- encoding

    @Test
    public fun `encoding splits a composite value into delimited genres`() {
        assertEquals("|Rock|Pop|", GenreCodec.encode(listOf("Rock;Pop")))
    }

    @Test
    public fun `encoding folds the duplicates the two wire fields produce`() {
        // The legacy `genre` string and the server's own `genres` index both name the same genre,
        // differently spelled. One bucket, and the first spelling is the one stored.
        assertEquals("|Indie Rock|", GenreCodec.encode(listOf("Indie Rock", "indie rock")))
    }

    @Test
    public fun `encoding a single genre is unchanged`() {
        assertEquals("|post-rock|slowcore|", GenreCodec.encode(listOf("post-rock", "slowcore")))
    }

    @Test
    public fun `encoding nothing usable leaves the column null rather than empty delimiters`() {
        assertNull(GenreCodec.encode(emptyList()))
        assertNull(GenreCodec.encode(listOf(" ", ";", "")))
    }

    // ------------------------------------------------------------------- decoding

    /**
     * A column written before the split reads correctly anyway.
     *
     * This is what makes the Genres screen right on the upgrade itself rather than on the next time
     * the server happens to re-report the album - a delta sync rewrites only what changed, so for an
     * untouched album that is never.
     */
    @Test
    public fun `decoding splits a composite column written before the fix`() {
        assertEquals(
            listOf("Acoustic Rock", "Indie Rock"),
            GenreCodec.decode("|Acoustic Rock;Indie Rock|"),
        )
    }

    @Test
    public fun `decoding folds a genre a column names twice`() {
        assertEquals(listOf("Rock"), GenreCodec.decode("|Rock|rock|"))
    }

    @Test
    public fun `an encoded column round-trips`() {
        val genres: List<String> = listOf("Hip-Hop/Rap", "Folk, World, & Country")

        assertEquals(genres, GenreCodec.decode(GenreCodec.encode(genres)))
    }

    @Test
    public fun `the like pattern still brackets the term so rock does not match rockabilly`() {
        val pattern: String = GenreCodec.likePattern("rock")

        assertEquals("%|rock|%", pattern)
        assertTrue(GenreCodec.encode(listOf("rock"))!!.contains("|rock|"))
    }
}
