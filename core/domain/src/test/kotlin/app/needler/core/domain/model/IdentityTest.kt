package app.needler.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which artist ids the catalogue lane can be given.
 *
 * REQUIREMENTS.md "Identity model" is built on artist and release-group MBIDs being real MusicBrainz
 * identifiers, and DroppedNeedle breaks that in exactly one place: an artist it could not match gets
 * a version 5 UUID derived from the name. The catalogue discography route answers
 * `400 Use the local library artist route for a DroppedNeedle artist ID` for one of those, so the
 * check has to happen before the call. These tests are the check.
 *
 * The id in [SERVER_MINTED] is the one seen on the device.
 */
public class IdentityTest {

    // ---------------------------------------------------------------- the version nibble

    @Test
    public fun `the version is the first character of the third group`() {
        assertEquals(4, uuidVersionOf("d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f1"))
        assertEquals(5, uuidVersionOf(SERVER_MINTED))
        assertEquals(1, uuidVersionOf("00000000-0000-1000-8000-000000000000"))
    }

    @Test
    public fun `anything that is not a canonical UUID has no version`() {
        assertNull("too short", uuidVersionOf("d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f"))
        assertNull("too long", uuidVersionOf("d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a2f11"))
        assertNull("hyphen in the wrong place", uuidVersionOf("d2b6bd7d8-d2d-4f33-9a8e-3cbb1cf1a2f1"))
        assertNull("not hex", uuidVersionOf("zzzzzzzz-8d2d-4f33-9a8e-3cbb1cf1a2f1"))
        assertNull("a Subsonic id, prefix and all", uuidVersionOf("ar-d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf"))
        assertNull("a slug", uuidVersionOf("the-marias"))
        assertNull("empty", uuidVersionOf(""))
    }

    /**
     * The version nibble is hex, so a `d` in that position is version 13 and not a malformed 4.
     *
     * Worth asserting because the fixtures this repository's own tests use carry exactly that shape,
     * and a check written as "is it 4?" rather than "is it 5?" would have quietly refused every one
     * of them.
     */
    @Test
    public fun `a hex version above nine still parses`() {
        assertEquals(13, uuidVersionOf("aa11bb22-cc33-dd44-ee55-ff6677889900"))
    }

    // ---------------------------------------------------------------- artist ids

    @Test
    public fun `a name-derived artist id is not a catalogue identifier`() {
        val artist = ArtistMbid(SERVER_MINTED)

        assertTrue(artist.isNameDerived)
        assertFalse(artist.isCatalogueIdentifier)
    }

    @Test
    public fun `a real MusicBrainz artist id is a catalogue identifier`() {
        val artist = ArtistMbid("b3d01a12-9d1d-4e4e-9b1a-7a2f2b49a2c9")

        assertFalse(artist.isNameDerived)
        assertTrue(artist.isCatalogueIdentifier)
    }

    @Test
    public fun `version 3 is name-derived too, for the same reason version 5 is`() {
        assertTrue(ArtistMbid("aaaaaaaa-bbbb-3ccc-8ddd-eeeeeeeeeeee").isNameDerived)
    }

    /**
     * An id of an unrecognised shape is tried rather than refused.
     *
     * The fixtures in this repository's tests and the slugs its screenshots use are both of that
     * kind, and so is whatever a future server release mints. One rejected request is a cheaper
     * mistake than a discography withheld from an artist who has one, and the asymmetry is the whole
     * reason `isCatalogueIdentifier` is the negation of `isNameDerived` rather than a test for 4.
     */
    @Test
    public fun `an id that is not a UUID at all is still offered to the catalogue`() {
        assertTrue(ArtistMbid("ar-marias").isCatalogueIdentifier)
        assertFalse(ArtistMbid("ar-marias").isNameDerived)
    }

    @Test
    public fun `the Subsonic prefix is stripped before the shape is judged`() {
        val parsed: ArtistMbid = requireNotNull(
            ArtistMbid.fromSubsonicArtistId(ArtistMbid.SUBSONIC_PREFIX + SERVER_MINTED),
        )

        assertEquals(SERVER_MINTED, parsed.value)
        assertTrue(parsed.isNameDerived)
    }

    private companion object {
        /**
         * The id from the device log: `8cfce742-445e-5e80-93a8-d8f924d56984`.
         *
         * Third group `5e80`, so version 5. DroppedNeedle computed it from the artist's name, and
         * `/api/v1/artists/{id}/releases` will never accept it.
         */
        const val SERVER_MINTED: String = "8cfce742-445e-5e80-93a8-d8f924d56984"
    }
}
