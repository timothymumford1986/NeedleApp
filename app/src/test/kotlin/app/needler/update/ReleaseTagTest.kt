package app.needler.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tag arithmetic, checked against the shell it is a copy of.
 *
 * This is the single most load-bearing piece of arithmetic in the update path: get it wrong high
 * and the app offers a release the platform will refuse as a downgrade; get it wrong low and the
 * app goes quiet forever while releases pile up on GitHub. Neither failure announces itself, which
 * is why the quirks are pinned here as tests rather than left as comments — a future tidy-up that
 * "fixes" the two-part-tag case would break agreement with the workflow, and this is what would
 * say so.
 */
class ReleaseTagTest {

    @Test
    fun `the documented example from the release workflow`() {
        assertEquals(10_203L, ReleaseTag.versionCodeOf("v1.2.3"))
    }

    @Test
    fun `the leading v is optional, because the shell strips at most one`() {
        assertEquals(10_203L, ReleaseTag.versionCodeOf("1.2.3"))
    }

    @Test
    fun `ordering is numeric, which is the whole reason strings are never compared`() {
        val nine = ReleaseTag.versionCodeOf("v1.9.0")!!
        val ten = ReleaseTag.versionCodeOf("v1.10.0")!!
        // Lexicographically "1.10.0" sorts below "1.9.0". Numerically it does not, and numerically
        // is what the platform will use.
        assertEquals(10_900L, nine)
        assertEquals(11_000L, ten)
        assertTrue(ten > nine)
    }

    @Test
    fun `a prerelease suffix is stripped from the patch, exactly as the workflow does`() {
        assertEquals(10_203L, ReleaseTag.versionCodeOf("v1.2.3-beta.1"))
        assertEquals(10_203L, ReleaseTag.versionCodeOf("v1.2.3-rc1"))
    }

    @Test
    fun `zeroes are ordinary`() {
        assertEquals(0L, ReleaseTag.versionCodeOf("v0.0.0"))
        assertEquals(10_000L, ReleaseTag.versionCodeOf("v1.0.0"))
    }

    /**
     * Reproduced, not corrected. `${REST#*.}` leaves `REST` untouched when it holds no dot, so the
     * workflow reads the minor component twice and `v1.2` becomes 10202. Anything else here would
     * disagree with the number actually built into that release's APK.
     */
    @Test
    fun `a two-part tag repeats the minor as the patch, as the shell does`() {
        assertEquals(10_202L, ReleaseTag.versionCodeOf("v1.2"))
    }

    /** Same quirk, one level further down: a bare `v3` reads every component as 3. */
    @Test
    fun `a one-part tag repeats the major throughout`() {
        assertEquals(30_303L, ReleaseTag.versionCodeOf("v3"))
    }

    /**
     * The collision the formula cannot avoid. Documented as a known property of the workflow rather
     * than fixed here; fixing it means changing release.yml first.
     */
    @Test
    fun `a patch of one hundred collides with the next minor, as the formula must`() {
        assertEquals(ReleaseTag.versionCodeOf("v1.1.0"), ReleaseTag.versionCodeOf("v1.0.100"))
    }

    @Test
    fun `a four-part tag is refused, because the workflow would have refused it too`() {
        assertNull(ReleaseTag.versionCodeOf("v1.2.3.4"))
    }

    @Test
    fun `anything that is not digits is refused`() {
        assertNull(ReleaseTag.versionCodeOf("latest"))
        assertNull(ReleaseTag.versionCodeOf("v1.x.3"))
        assertNull(ReleaseTag.versionCodeOf("v-1.2.3"))
        assertNull(ReleaseTag.versionCodeOf(""))
        assertNull(ReleaseTag.versionCodeOf("v"))
    }

    @Test
    fun `a component large enough to overflow a platform versionCode is refused`() {
        assertNull(ReleaseTag.versionCodeOf("v9999999.0.0"))
    }

    @Test
    fun `the version name is the tag without its v, which is what the APK will report`() {
        assertEquals("1.2.3", ReleaseTag.versionNameOf("v1.2.3"))
        assertEquals("1.2.3", ReleaseTag.versionNameOf("1.2.3"))
    }
}
