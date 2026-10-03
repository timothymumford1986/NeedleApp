package app.needler.feature.library.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wording TalkBack speaks for a favourite, asserted exactly.
 *
 * `favouriteContentDescription` is internal rather than private so this test can exist: it is the only
 * place in the app where the state of a favourite is announced with the subject's name, and "Starred"
 * versus "Star" is one character away from telling the user the opposite of the truth.
 *
 * The glyph changed from a five-pointed star to the shared heart in `:core:design`; the vocabulary
 * deliberately did not. It matches the server's own `star` and `unstar` verbs and `getStarred2`, and
 * the library's sort control already offers a "Starred" option. REQUIREMENTS.md "Playlists" forbids
 * star *ratings* because `setRating` on this server "validates and returns success without persisting
 * anything" - the hazard there is the five-pointed picture implying four more points, not the verb.
 * This test is what makes that a decision rather than an oversight: changing the wording has to break
 * something.
 */
class FavouriteControlTest {

    @Test
    fun `a starred subject leads with its state and offers the way out`() {
        assertEquals(
            "Starred. Remove Revolver from your favourites",
            favouriteContentDescription(isFavourite = true, name = "Revolver"),
        )
    }

    @Test
    fun `an unstarred subject is an invitation, not a state`() {
        assertEquals(
            "Star Revolver",
            favouriteContentDescription(isFavourite = false, name = "Revolver"),
        )
    }

    /**
     * The two states never read the same.
     *
     * The failure this guards against is not a typo but a refactor that drops the state word, leaving
     * TalkBack to say "Revolver" twice and the user with no way to tell on from off by ear.
     */
    @Test
    fun `the two states are distinguishable by ear`() {
        val starred = favouriteContentDescription(isFavourite = true, name = "Revolver")
        val unstarred = favouriteContentDescription(isFavourite = false, name = "Revolver")

        assertEquals(false, starred == unstarred)
        assertEquals(true, starred.startsWith("Starred"))
    }

    /** An untitled subject still reads as a sentence: the label is the caller's, not this function's. */
    @Test
    fun `the name is interpolated verbatim`() {
        assertEquals(
            "Star Untitled album",
            favouriteContentDescription(isFavourite = false, name = "Untitled album"),
        )
    }
}
