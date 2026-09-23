package app.needler.update

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestration: when a check happens, what it persists, and what the listener ends up seeing.
 *
 * Three of the four outcomes a check can have are invisible, so the assertions are mostly about
 * what was *not* drawn and what was written down. That is the feature: an update check is the one
 * thing in the app that runs on its own initiative, and everything about it is built so that
 * failing is indistinguishable, from the outside, from not having happened.
 */
class UpdateRepositoryTest {

    private val releases: GitHubReleaseClient = mockk()
    private val downloader: ApkDownloader = mockk(relaxed = true)
    private val installer: ApkInstaller = mockk(relaxed = true)
    private val installed: InstalledVersion = mockk()
    private val preferences = FakeUpdatePreferences()

    private val update = AvailableUpdate(
        tagName = "v1.2.3",
        versionName = "1.2.3",
        versionCode = 10_203L,
        downloadUrl = NeedleAppRepository.DOWNLOAD_URL_PREFIX + "v1.2.3/needler-v1.2.3.apk",
        sizeBytes = 4_000_000L,
        releaseNotes = "",
    )

    private fun repository(): UpdateRepository {
        every { installer.status } returns MutableStateFlow(InstallStatus.Idle)
        return UpdateRepository(releases, downloader, installer, preferences, installed)
    }

    @Test
    fun `a newer release is offered, and the check is written down`() = runTest {
        every { installed.versionCode() } returns 10_202L
        coEvery { releases.latestRelease() } returns ReleaseLookup.Found(update)

        val repository = repository()
        repository.checkForUpdateIfDue()

        assertEquals(UpdateState.Available(update), repository.observe().first())
        assertTrue(preferences.lastCheckAtMillis() > 0L)
    }

    @Test
    fun `the release already installed is not an update, and nothing is drawn`() = runTest {
        every { installed.versionCode() } returns 10_203L
        coEvery { releases.latestRelease() } returns ReleaseLookup.Found(update)

        val repository = repository()
        repository.checkForUpdateIfDue()

        assertEquals(UpdateState.Idle, repository.observe().first())
        // Still a completed check: the question was asked and answered.
        assertTrue(preferences.lastCheckAtMillis() > 0L)
    }

    @Test
    fun `a second check on the same day does not go out`() = runTest {
        every { installed.versionCode() } returns 10_203L
        coEvery { releases.latestRelease() } returns ReleaseLookup.Found(update)

        val repository = repository()
        repository.checkForUpdateIfDue()
        repository.checkForUpdateIfDue()

        coVerify(exactly = 1) { releases.latestRelease() }
    }

    /**
     * The unauthenticated allowance is 60 requests an hour per IP address, shared by everything
     * behind one NAT. Being refused is not a fault of this app's, and the wait is persisted because
     * restarting the process does not restore the allowance.
     */
    @Test
    fun `a rate-limited check says nothing and persists the wait`() = runTest {
        every { installed.versionCode() } returns 10_202L
        coEvery { releases.latestRelease() } returns ReleaseLookup.RateLimited(retryAfterMillis = 3_600_000L)

        val repository = repository()
        repository.checkForUpdateIfDue()

        assertEquals(UpdateState.Idle, repository.observe().first())
        assertTrue(preferences.retryNotBeforeMillis() > System.currentTimeMillis())
        // Not a completed check: the ordinary cadence must not start from a refusal.
        assertEquals(0L, preferences.lastCheckAtMillis())
    }

    /**
     * Nothing is persisted for an unreachable check, so a device that reconnects to Wi-Fi ten
     * minutes later is only held off by the short in-memory throttle rather than until tomorrow.
     */
    @Test
    fun `an unreachable check says nothing and persists nothing`() = runTest {
        every { installed.versionCode() } returns 10_202L
        coEvery { releases.latestRelease() } returns ReleaseLookup.Unreachable

        val repository = repository()
        repository.checkForUpdateIfDue()

        assertEquals(UpdateState.Idle, repository.observe().first())
        assertEquals(0L, preferences.lastCheckAtMillis())
        assertEquals(0L, preferences.retryNotBeforeMillis())
    }

    @Test
    fun `dismissing records the version so this release stays quiet`() = runTest {
        every { installed.versionCode() } returns 10_202L
        coEvery { releases.latestRelease() } returns ReleaseLookup.Found(update)

        val repository = repository()
        repository.checkForUpdateIfDue()
        repository.dismiss()

        assertEquals(UpdateState.Idle, repository.observe().first())
        assertEquals(10_203L, preferences.dismissedVersionCode())
    }

    @Test
    fun `a dismissed release is not offered again by a later check`() = runTest {
        every { installed.versionCode() } returns 10_202L
        coEvery { releases.latestRelease() } returns ReleaseLookup.Found(update)
        preferences.recordDismissed(10_203L)

        val repository = repository()
        repository.checkForUpdateIfDue()

        assertEquals(UpdateState.Idle, repository.observe().first())
    }
}

/**
 * [UpdatePreferences] in memory.
 *
 * The interface exists for this: the scheduling decisions are worth testing and a
 * `SharedPreferences` is not available to a plain JVM unit test — `isReturnDefaultValues = true`
 * would hand back a stub that silently forgets every write, which is precisely the behaviour these
 * tests need to catch.
 */
class FakeUpdatePreferences : UpdatePreferences {

    private var lastCheckAt: Long = 0L
    private var retryNotBefore: Long = 0L
    private var dismissed: Long = 0L

    override fun lastCheckAtMillis(): Long = lastCheckAt

    override fun recordCheckedAt(millis: Long) {
        lastCheckAt = millis
    }

    override fun retryNotBeforeMillis(): Long = retryNotBefore

    override fun recordRetryNotBefore(millis: Long) {
        retryNotBefore = millis
    }

    override fun dismissedVersionCode(): Long = dismissed

    override fun recordDismissed(versionCode: Long) {
        dismissed = versionCode
    }
}
