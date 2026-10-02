package app.needler.update

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestration: when a check happens, what it persists, and what the listener ends up seeing.
 *
 * Three of the four outcomes a check can have are invisible, so the assertions are mostly about
 * what was *not* drawn and what was written down. That is the feature: an update check is the one
 * thing in the app that runs on its own initiative, and everything about it is built so that
 * failing is indistinguishable, from the outside, from not having happened.
 *
 * The second half is about the one state that used to have no way out at all. See
 * `an install the platform never answers does not strand the banner for ever`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRepositoryTest {

    private val releases: GitHubReleaseClient = mockk()
    private val downloader: ApkDownloader = mockk(relaxed = true)
    private val installer: ApkInstaller = mockk(relaxed = true)
    private val installed: InstalledVersion = mockk()
    private val preferences = FakeUpdatePreferences()

    /**
     * The platform's half of the state, shared by every [repository] in one test rather than built
     * fresh per call — the real [ApkInstaller] is a `@Singleton` and the whole point of the fold is
     * that the two halves are looking at the same thing.
     */
    private val installStatus = MutableStateFlow<InstallStatus>(InstallStatus.Idle)

    /**
     * A clock the test moves, not the one the device has.
     *
     * Two of the three escapes from a stalled install are decisions about elapsed wall-clock time,
     * and without this the only way to reach them would be to wait
     * [UpdateCheckPolicy.INSTALL_HANDOVER_TIMEOUT_MILLIS] for real.
     */
    private var fakeNowMillis: Long = 1_800_000_000_000L
    private val clock: UpdateClock = mockk<UpdateClock>().also { mock ->
        every { mock.nowMillis() } answers { fakeNowMillis }
    }

    /**
     * Whether the committed install session still exists, as the test decides.
     *
     * A field rather than a canned sequence of answers, because how many times the fold consults it
     * is an implementation detail of `combine` and this is not the place to pin that down.
     */
    private var sessionIsGone: Boolean = false

    private val update = AvailableUpdate(
        tagName = "v1.2.3",
        versionName = "1.2.3",
        versionCode = 10_203L,
        downloadUrl = NeedleAppRepository.DOWNLOAD_URL_PREFIX + "v1.2.3/needler-v1.2.3.apk",
        sizeBytes = 4_000_000L,
        releaseNotes = "",
    )

    private fun repository(): UpdateRepository {
        every { installer.status } returns installStatus
        return UpdateRepository(releases, downloader, installer, preferences, installed, clock)
    }

    /**
     * Wire the mocks so that pressing Update actually walks the sequence: permission granted, a
     * download that lands, and an install that commits a session and then goes quiet — which is
     * the device's reported failure, and the one the platform has no event for.
     */
    private fun arrangeStalledInstall() {
        every { installed.versionCode() } returns 10_202L
        coEvery { releases.latestRelease() } returns ReleaseLookup.Found(update)
        every { installer.canInstallPackages() } returns true
        every { installer.committedSessionIsGone() } answers { sessionIsGone }
        coEvery { downloader.download(any(), any()) } returns File("needler-v1.2.3.apk")
        // The real one reports its own progress through [status], and the real one is also what
        // puts this state machine somewhere it used to be unable to leave.
        coEvery { installer.install(any()) } answers { installStatus.value = InstallStatus.Committing }
        every { installer.reset() } answers { installStatus.value = InstallStatus.Idle }
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

    // ---- leaving an install the platform never answered ---------------------

    /**
     * The test this package was missing, and the one a real device paid for.
     *
     * A listener pressed Update, the Play Protect dialogue appeared, the install did not complete,
     * and the app was left showing "Installing 0.0.11" with no action button and no way to dismiss
     * it — permanently, because the state was in memory and the one event that would have cleared
     * it was the install that could not start.
     *
     * It matters more than a stuck bar looks. REQUIREMENTS.md fixes distribution as a "Signed APK
     * on GitHub releases" and names the updater only to say it is a surface that "exists in the
     * code and is specified nowhere in this document", so this class *is* the delivery mechanism.
     * A listener stuck here can never update again by any route the app offers.
     *
     * So: drive the machine into `Installing`, assert the bar really is the dead one the device
     * showed, and assert it gets out on its own.
     */
    @Test
    fun `an install the platform never answers does not strand the banner for ever`() = runTest {
        arrangeStalledInstall()
        val repository = repository()

        val seen = mutableListOf<UpdateState>()
        backgroundScope.launch { repository.observe().collect { state -> seen += state } }
        runCurrent()

        repository.checkForUpdateIfDue()
        repository.downloadAndInstall()
        runCurrent()

        // Exactly the bar the device was stuck on: one line, nothing to press, nothing to dismiss.
        assertEquals(UpdateState.Installing(update), seen.last())
        val stuck = seen.last().toBannerState()
        assertEquals("Installing 1.2.3", stuck.message)
        assertNull(stuck.actionLabel)
        assertFalse(stuck.dismissible)

        // Not `advanceUntilIdle`, which stops as soon as the only work left is in backgroundScope
        // — and the watchdog lives on the banner's collector, which is exactly that.
        advanceTimeBy(UpdateCheckPolicy.INSTALL_HANDOVER_TIMEOUT_MILLIS + 1L)
        runCurrent()

        // And out again, with the whole feature back behind it.
        assertEquals(UpdateState.Available(update), seen.last())
        assertEquals("Update", seen.last().toBannerState().actionLabel)
        assertTrue(seen.last().toBannerState().dismissible)
    }

    /**
     * The timeout is the backstop; a vanished session is the mechanism.
     *
     * `PackageInstaller` reports the outcome of a commit, and a confirmation dialogue that is never
     * answered has no outcome — nothing is broadcast at all. What *is* observable is that the
     * session has stopped existing, so the wait looks rather than only counting, and the listener
     * who pressed "Don't install" gets the offer back in seconds instead of minutes.
     */
    @Test
    fun `a session that has vanished releases the banner long before the timeout`() = runTest {
        arrangeStalledInstall()
        val repository = repository()

        val seen = mutableListOf<UpdateState>()
        backgroundScope.launch { repository.observe().collect { state -> seen += state } }
        runCurrent()

        repository.checkForUpdateIfDue()
        repository.downloadAndInstall()
        runCurrent()
        assertEquals(UpdateState.Installing(update), seen.last())

        // The session is still there, so there is still something to wait for.
        advanceTimeBy(UpdateCheckPolicy.HANDOVER_POLL_MILLIS + 1L)
        runCurrent()
        assertEquals(UpdateState.Installing(update), seen.last())

        // The listener dismissed the dialogue. The platform broadcasts nothing; the session simply
        // stops existing, and looking is the only way the app can find out.
        sessionIsGone = true
        advanceTimeBy(UpdateCheckPolicy.HANDOVER_POLL_MILLIS + 1L)
        runCurrent()
        assertEquals(UpdateState.Available(update), seen.last())

        // Nowhere near the backstop, which is the difference between a fix and a fix worth having.
        assertTrue(currentTime < UpdateCheckPolicy.INSTALL_HANDOVER_TIMEOUT_MILLIS)
    }

    /**
     * The third route to the dead bar, and the one with no waiting involved.
     *
     * The fold derives `Installing` from the platform's half alone, so a `Committing` left behind
     * by something this class never handed over used to be enough to put the unleaveable bar on
     * screen — with no deadline behind it, nothing counting, and nothing left to clear it. An
     * `Installing` with no recorded hand-over is bookkeeping with nothing under it, and the
     * assertion that matters is that it is never *drawn*, not merely that it goes away.
     */
    @Test
    fun `an install state with no hand-over behind it is never drawn`() = runTest {
        arrangeStalledInstall()
        val repository = repository()

        val seen = mutableListOf<UpdateState>()
        backgroundScope.launch { repository.observe().collect { state -> seen += state } }
        runCurrent()

        repository.checkForUpdateIfDue()
        runCurrent()
        assertEquals(UpdateState.Available(update), seen.last())

        // The platform's half claims an install; nothing here ever gave it one.
        installStatus.value = InstallStatus.Committing
        runCurrent()

        assertFalse(seen.contains(UpdateState.Installing(update)))
        assertEquals(UpdateState.Available(update), seen.last())
        assertEquals("Update", seen.last().toBannerState().actionLabel)
    }

    /**
     * Settings' "Check for updates" is the listener's own lever, and it must never be the thing
     * that refuses to move because the state it would fix is wrong.
     *
     * GitHub is unreachable here on purpose. The recovery is the release itself, not the request,
     * so it works on a device with no connection — which matters, because an install that stalled
     * may well have stalled on a train.
     */
    @Test
    fun `the manual check breaks a stalled install, and needs no network to do it`() = runTest {
        arrangeStalledInstall()
        val repository = repository()

        repository.checkForUpdateIfDue()
        repository.downloadAndInstall()
        assertEquals(UpdateState.Installing(update), repository.observe().first())

        fakeNowMillis += UpdateCheckPolicy.INSTALL_HANDOVER_TIMEOUT_MILLIS
        coEvery { releases.latestRelease() } returns ReleaseLookup.Unreachable

        assertEquals(ManualUpdateCheck.UPDATE_AVAILABLE, repository.checkNow())
        assertEquals(UpdateState.Available(update), repository.observe().first())
    }

    /** The ambient once-a-day glance releases it too, so a torn-down watchdog is not the only hope. */
    @Test
    fun `the ambient check releases a stalled install rather than refusing to run`() = runTest {
        arrangeStalledInstall()
        val repository = repository()

        repository.checkForUpdateIfDue()
        repository.downloadAndInstall()
        assertEquals(UpdateState.Installing(update), repository.observe().first())

        fakeNowMillis += UpdateCheckPolicy.INSTALL_HANDOVER_TIMEOUT_MILLIS
        repository.checkForUpdateIfDue()

        assertEquals(UpdateState.Available(update), repository.observe().first())
    }

    /**
     * The escape must not lie in the other direction. Inside the window the platform's dialogue
     * genuinely is up, "an update is already in progress" is the honest answer, and pulling the
     * offer back under a live install would be a second bug wearing the first one's clothes.
     */
    @Test
    fun `an install that is genuinely under way is still reported as busy`() = runTest {
        arrangeStalledInstall()
        val repository = repository()

        repository.checkForUpdateIfDue()
        repository.downloadAndInstall()

        fakeNowMillis += UpdateCheckPolicy.INSTALL_HANDOVER_TIMEOUT_MILLIS - 1L

        assertEquals(ManualUpdateCheck.BUSY, repository.checkNow())
        assertEquals(UpdateState.Installing(update), repository.observe().first())
    }

    /**
     * The worst of the stuck routes, because it was the *successful* one.
     *
     * `STATUS_SUCCESS` used to fold to `Installing`. It is rarely seen — the platform has usually
     * killed this process by then — but on the occasions it is seen, the old code put the one bar
     * with no action and no dismissal in front of a listener whose update had just worked.
     */
    @Test
    fun `a successful install leaves nothing on screen, not an install that never ends`() = runTest {
        arrangeStalledInstall()
        val repository = repository()

        repository.checkForUpdateIfDue()
        repository.downloadAndInstall()
        installStatus.value = InstallStatus.Succeeded

        assertEquals(UpdateState.Idle, repository.observe().first())
    }

    /**
     * A bar already offering a Retry is not an update in progress, and saying so would be the same
     * lie as the stuck banner in a quieter place: the listener presses the row, is told something
     * is happening, and nothing is.
     */
    @Test
    fun `a bar already offering a retry is not reported as an update in progress`() = runTest {
        arrangeStalledInstall()
        coEvery { downloader.download(any(), any()) } returns null
        val repository = repository()

        repository.checkForUpdateIfDue()
        repository.downloadAndInstall()
        assertEquals(UpdateState.DownloadFailed(update), repository.observe().first())

        assertEquals(ManualUpdateCheck.UPDATE_AVAILABLE, repository.checkNow())
    }

    /**
     * Not a stuck route, but the boundary next to one.
     *
     * The caller's scope is a `viewModelScope`, so a listener who leaves the app mid-install
     * cancels this call, and the cancellation surfaces at [ApkInstaller.install]'s `withContext`
     * boundary. Whatever the platform's half then says, this class's half must not be left
     * claiming an intention nobody is acting on: nothing has failed and the verified APK is still
     * on disk, so the offer goes back exactly as it does when a *download* is cancelled.
     *
     * The cancellation has to keep unwinding, too. Swallowing it here would leave the caller's
     * scope thinking the work completed.
     */
    @Test
    fun `a cancelled install puts the offer back and keeps unwinding`() = runTest {
        arrangeStalledInstall()
        coEvery { installer.install(any()) } throws CancellationException("the banner went away")
        val repository = repository()

        repository.checkForUpdateIfDue()
        var unwound = false
        try {
            repository.downloadAndInstall()
        } catch (cancelled: CancellationException) {
            // It has to keep unwinding, not be swallowed; the state is put back on the way out.
            unwound = true
        }

        assertTrue(unwound)
        assertEquals(UpdateState.Available(update), repository.observe().first())
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
