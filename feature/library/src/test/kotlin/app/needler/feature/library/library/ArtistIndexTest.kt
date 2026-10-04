package app.needler.feature.library.library

import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which bucket an artist falls in, and what the strip is made of.
 *
 * Plain JVM, no Robolectric: this is the decision. `ArtistIndexStripTest` asserts that the rendered
 * control reads it, which is the other half and the part a wiring mistake breaks silently.
 *
 * Every case here is a name shape the reference library actually contains, or one that would file an
 * artist under the wrong letter if the fold were dropped - which is the failure this whole file
 * exists to pin down, because an index that sends "The Marías" to T on a list that sorts them under
 * M scrolls to the wrong place by a third of the alphabet and looks like a broken scroll rather than
 * a broken letter.
 */
class ArtistIndexTest {

    // ---- the letter ---------------------------------------------------------

    @Test
    fun `an ordinary name files under its first letter`() {
        assertEquals("B", ArtistIndex.bucketOf(artist("Big Thief")))
        assertEquals("K", ArtistIndex.bucketOf(artist("Khruangbin")))
    }

    /**
     * A leading article is stripped, as the mirror's own ordering strips it.
     *
     * `sortName` defaults to `name`, so this is the case that decides whether the strip agrees with
     * the list: an `Artist` built anywhere other than the entity mappers carries the display name.
     */
    @Test
    fun `a leading article is not the letter`() {
        assertEquals("M", ArtistIndex.bucketOf(artist("The Marías")))
        assertEquals("T", ArtistIndex.bucketOf(artist("A Tribe Called Quest")))
        assertEquals("H", ArtistIndex.bucketOf(artist("An Horse")))
    }

    /** And an already-normalised `sortName` is what the mirror actually supplies. */
    @Test
    fun `a sort name the mirror already normalised is used as it stands`() {
        assertEquals(
            "M",
            ArtistIndex.bucketOf(Artist(mbid = ArtistMbid("ar-1"), name = "The Marías", sortName = "marías")),
        )
    }

    /** "The The" strips to "the", which is still a T and not an empty bucket. */
    @Test
    fun `a name that is nothing but an article keeps its letter`() {
        assertEquals("T", ArtistIndex.bucketOf(artist("The The")))
    }

    // ---- accents and other scripts -----------------------------------------

    @Test
    fun `an accented first letter files under its base letter`() {
        assertEquals("O", ArtistIndex.bucketOf(artist("Ólafur Arnalds")))
        assertEquals("E", ArtistIndex.bucketOf(artist("Édith Piaf")))
        assertEquals("A", ArtistIndex.bucketOf(artist("Ângelo")))
        // The accent is not on the first letter here, so this is the case that would still pass with
        // no fold at all - kept so the pair reads as "accents never move the letter".
        assertEquals("S", ArtistIndex.bucketOf(artist("Sigur Rós")))
    }

    /**
     * A letter that does not decompose goes to `#` rather than to a guess.
     *
     * Documented in [ArtistIndex] as the known limit of the fold. Asserted rather than left implicit
     * because the tempting fix - a hand-written table of stroked and ligatured letters - is the one
     * that files an artist under a letter they are not at.
     */
    @Test
    fun `a letter with no decomposition is not guessed at`() {
        assertEquals(ArtistIndex.OTHER, ArtistIndex.bucketOf(artist("Ørjan Nilsen")))
    }

    @Test
    fun `digits and punctuation go to the catch-all`() {
        assertEquals(ArtistIndex.OTHER, ArtistIndex.bucketOf(artist("!!!")))
        assertEquals(ArtistIndex.OTHER, ArtistIndex.bucketOf(artist("65daysofstatic")))
        assertEquals(ArtistIndex.OTHER, ArtistIndex.bucketOf(artist("...And You Will Know Us")))
    }

    @Test
    fun `a non-Latin name goes to the catch-all rather than to a bucket of its own`() {
        assertEquals(ArtistIndex.OTHER, ArtistIndex.bucketOf(artist("東京事変")))
        assertEquals(ArtistIndex.OTHER, ArtistIndex.bucketOf(artist("Мумий Тролль")))
    }

    @Test
    fun `a blank name has somewhere to go`() {
        assertEquals(ArtistIndex.OTHER, ArtistIndex.bucketOf(artist("")))
        assertEquals(ArtistIndex.OTHER, ArtistIndex.bucketOf(artist("   ")))
    }

    // ---- the strip ----------------------------------------------------------

    /** Twenty-six letters always, and `#` only when something is in it. */
    @Test
    fun `the strip is the whole alphabet, with no hash when nothing needs one`() {
        val entries: List<ArtistIndexEntry> = ArtistIndex.entriesFor(
            listOf(artist("Alvvays"), artist("Big Thief")),
        )

        assertEquals(26, entries.size)
        assertEquals(ArtistIndex.LETTERS, entries.map { it.label })
    }

    /** `#` leads, because the list it indexes puts those names first. */
    @Test
    fun `the hash bucket is the first chip when the library needs one`() {
        val entries: List<ArtistIndexEntry> = ArtistIndex.entriesFor(
            listOf(artist("!!!"), artist("Alvvays")),
        )

        assertEquals(27, entries.size)
        assertEquals(ArtistIndex.OTHER, entries.first().label)
        assertEquals(0, entries.first().firstArtistIndex)
    }

    /** Each letter points at the first artist under it, not at the last or at a count. */
    @Test
    fun `a letter points at the first artist under it`() {
        val entries: List<ArtistIndexEntry> = ArtistIndex.entriesFor(
            listOf(
                artist("Alvvays"),
                artist("Above & Beyond"),
                artist("Big Thief"),
                artist("Beach House"),
            ),
        )

        assertEquals(0, entries.single { it.label == "A" }.firstArtistIndex)
        assertEquals(2, entries.single { it.label == "B" }.firstArtistIndex)
    }

    /**
     * An empty letter is present and has nowhere to go, which is what the strip draws as disabled.
     *
     * The alternative - leaving it out - is rejected in [ArtistIndex] because it shifts every letter
     * after the gap, so the strip rearranges itself the first time a sync adds the library's first D.
     */
    @Test
    fun `a letter with no artists is kept and marked empty`() {
        val entries: List<ArtistIndexEntry> = ArtistIndex.entriesFor(listOf(artist("Alvvays")))

        val d: ArtistIndexEntry = entries.single { it.label == "D" }
        assertTrue("D should be empty", d.isEmpty)
        assertNull(d.firstArtistIndex)

        val a: ArtistIndexEntry = entries.single { it.label == "A" }
        assertFalse("A should not be empty", a.isEmpty)
    }

    /** An empty library still draws an alphabet, all of it empty. */
    @Test
    fun `an empty library still gives a full alphabet`() {
        val entries: List<ArtistIndexEntry> = ArtistIndex.entriesFor(emptyList())

        assertEquals(26, entries.size)
        assertTrue("every letter should be empty", entries.all { it.isEmpty })
    }

    // ---- what a screen reader hears -----------------------------------------

    /**
     * The spoken label says what a tap does, and says when a tap does nothing.
     *
     * A one-character visible label is the whole reason this exists: "A" names neither the control
     * nor its effect, and REQUIREMENTS.md "Accessibility" requires a description on every control.
     * The empty case needs it twice over - the only other signal that a letter is empty is the muted
     * colour, and the same section refuses colour as the only carrier of a state.
     */
    @Test
    fun `the spoken label distinguishes a jump from an empty letter`() {
        val entries: List<ArtistIndexEntry> = ArtistIndex.entriesFor(
            listOf(artist("!!!"), artist("Alvvays")),
        )

        assertEquals("Jump to A", entries.single { it.label == "A" }.spokenLabel)
        assertEquals("No artists under D", entries.single { it.label == "D" }.spokenLabel)
        assertEquals(
            "Jump to artists whose name does not start with a letter",
            entries.single { it.label == ArtistIndex.OTHER }.spokenLabel,
        )
    }

    private fun artist(name: String): Artist =
        Artist(mbid = ArtistMbid("ar-" + name.hashCode()), name = name)
}
