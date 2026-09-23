package app.needler.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often the app is allowed to talk to GitHub, and when it is allowed to speak up.
 *
 * These rules decide how much of someone's battery and mobile data is spent on a question they
 * never asked, so they are worth pinning. The clock cases are here because wall-clock time moves
 * backwards more often than anyone expects — first boot before NTP, a corrected timezone, a
 * hand-set date — and the failure mode of getting them wrong is an app that silently stops checking
 * for updates and never says why.
 */
class UpdateCheckPolicyTest {

    private val now: Long = 1_800_000_000_000L
    private val day: Long = UpdateCheckPolicy.CHECK_INTERVAL_MILLIS

    @Test
    fun `a device that has never checked, checks`() {
        assertTrue(due(lastCheckAt = 0L))
    }

    @Test
    fun `a check an hour ago is not repeated`() {
        assertFalse(due(lastCheckAt = now - 60L * 60L * 1_000L))
    }

    @Test
    fun `a check a day ago is repeated`() {
        assertTrue(due(lastCheckAt = now - day))
    }

    /**
     * The 24 hours run from the last *answer*, not the last attempt, and the throttle that covers
     * failures is short and lives only in memory. A morning on a train must not cost the whole day.
     */
    @Test
    fun `a failed attempt holds off for minutes, not for a day`() {
        val justFailed = now - 60_000L
        assertFalse(due(lastCheckAt = 0L, lastAttemptAt = justFailed))

        val failedAWhileAgo = now - UpdateCheckPolicy.OFFLINE_RETRY_MILLIS - 1L
        assertTrue(due(lastCheckAt = 0L, lastAttemptAt = failedAWhileAgo))
    }

    @Test
    fun `a rate-limited response is honoured until it expires`() {
        assertFalse(due(lastCheckAt = 0L, retryNotBefore = now + 60_000L))
        assertTrue(due(lastCheckAt = 0L, retryNotBefore = now - 1L))
    }

    /**
     * `X-RateLimit-Reset` is an absolute epoch, so a device whose clock is a year behind computes a
     * year-long wait from a perfectly correct header. Believing it would take the app out of
     * service until someone noticed.
     */
    @Test
    fun `a backoff further away than the ordinary cadence is not believed`() {
        assertTrue(due(lastCheckAt = 0L, retryNotBefore = now + day + 1L))
    }

    @Test
    fun `a stored check time in the future means the clock moved, so check`() {
        assertTrue(due(lastCheckAt = now + day))
    }

    @Test
    fun `an attempt time in the future does not suppress the next attempt`() {
        assertTrue(due(lastCheckAt = 0L, lastAttemptAt = now + day))
    }

    // ---- what gets offered --------------------------------------------------

    @Test
    fun `a newer release is offered`() {
        assertTrue(shouldOffer(candidate = 10_203L, installed = 10_202L))
    }

    @Test
    fun `the release already running is not an update`() {
        assertFalse(shouldOffer(candidate = 10_203L, installed = 10_203L))
    }

    /**
     * Android refuses a lower `versionCode` over a higher one, so offering a downgrade could only
     * ever produce a banner whose one possible outcome is a failed install.
     */
    @Test
    fun `a downgrade is never offered`() {
        assertFalse(shouldOffer(candidate = 10_202L, installed = 10_203L))
    }

    @Test
    fun `a dismissed version stays dismissed`() {
        assertFalse(shouldOffer(candidate = 10_203L, installed = 10_202L, dismissed = 10_203L))
    }

    /** Dismissal is "not this one", never "never again". */
    @Test
    fun `a release newer than the dismissed one still gets to speak`() {
        assertTrue(shouldOffer(candidate = 10_300L, installed = 10_202L, dismissed = 10_203L))
    }

    private fun due(
        lastCheckAt: Long,
        retryNotBefore: Long = 0L,
        lastAttemptAt: Long = 0L,
    ): Boolean = UpdateCheckPolicy.isCheckDue(
        now = now,
        lastCheckAtMillis = lastCheckAt,
        retryNotBeforeMillis = retryNotBefore,
        lastAttemptAtMillis = lastAttemptAt,
    )

    private fun shouldOffer(
        candidate: Long,
        installed: Long,
        dismissed: Long = 0L,
    ): Boolean = UpdateCheckPolicy.shouldOffer(
        candidateVersionCode = candidate,
        installedVersionCode = installed,
        dismissedVersionCode = dismissed,
    )
}
