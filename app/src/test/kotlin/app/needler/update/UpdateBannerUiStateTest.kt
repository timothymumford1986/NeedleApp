package app.needler.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The banner's one line, and the states in which there is no line at all.
 *
 * The first test is the one the navigation scaffold depends on: the update slot has to cost nothing
 * when there is no update, or every launch would carry a blank strip of chrome above the bottom bar
 * for a feature that has nothing to say on all but a handful of days a year.
 */
class UpdateBannerUiStateTest {

    private val update = AvailableUpdate(
        tagName = "v1.2.3",
        versionName = "1.2.3",
        versionCode = 10_203L,
        downloadUrl = NeedleAppRepository.DOWNLOAD_URL_PREFIX + "v1.2.3/needler-v1.2.3.apk",
        sizeBytes = 4_000_000L,
        releaseNotes = "Gapless playback.",
    )

    @Test
    fun `with nothing to say the banner is absent, not empty`() {
        val state = UpdateState.Idle.toBannerState()

        assertFalse(state.visible)
        assertEquals("", state.message)
        assertNull(state.actionLabel)
    }

    @Test
    fun `an available release states a fact and offers one action`() {
        val state = UpdateState.Available(update).toBannerState()

        assertTrue(state.visible)
        assertEquals("Needler 1.2.3 is available", state.message)
        assertEquals("Update", state.actionLabel)
        assertTrue(state.dismissible)
        assertFalse(state.showProgress)
    }

    @Test
    fun `a download reports determinate progress, because the asset size is always known`() {
        val state = UpdateState.Downloading(update, downloadedBytes = 1_000_000L, totalBytes = 4_000_000L)
            .toBannerState()

        assertTrue(state.showProgress)
        assertEquals(0.25f, state.progress, 0.0001f)
        // Nothing to press while bytes are moving; the only control left is the dismissal.
        assertNull(state.actionLabel)
    }

    @Test
    fun `progress cannot escape the track, whatever the byte counts say`() {
        val over = UpdateState.Downloading(update, downloadedBytes = 9L, totalBytes = 4L).toBannerState()
        assertEquals(1f, over.progress, 0.0001f)

        val unknown = UpdateState.Downloading(update, downloadedBytes = 5L, totalBytes = 0L).toBannerState()
        assertEquals(0f, unknown.progress, 0.0001f)
    }

    @Test
    fun `a missing permission asks for it rather than reporting a failure`() {
        val state = UpdateState.PermissionRequired(update).toBannerState()

        assertEquals("Allow Needler to install updates", state.message)
        assertEquals("Allow", state.actionLabel)
    }

    /**
     * Once the session is committed the app is no longer the one in charge, so a dismiss control
     * that could not actually stop anything would be a lie.
     */
    @Test
    fun `an install already handed to the platform cannot be dismissed`() {
        assertFalse(UpdateState.Installing(update).toBannerState().dismissible)
        assertFalse(UpdateState.AwaitingConfirmation(update).toBannerState().dismissible)
    }

    /**
     * The failure message and its action are the single most sensitive strings in this package.
     * "Retry" is the only thing offered, and nothing anywhere suggests uninstalling: an uninstall
     * destroys the Keystore master key behind `SecureCredentialStore`, and with it the companion
     * bearer, the app-password, the mirror and every downloaded album. A failed update costs
     * nothing at all by comparison, and the working install must be left exactly as it is.
     */
    @Test
    fun `a failure offers a retry and never suggests uninstalling`() {
        for (state in listOf(UpdateState.DownloadFailed(update), UpdateState.InstallFailed(update))) {
            val banner = state.toBannerState()
            assertEquals("Retry", banner.actionLabel)
            assertTrue(banner.dismissible)
            assertFalse(banner.message.contains("uninstall", ignoreCase = true))
            assertFalse(banner.message.contains("reinstall", ignoreCase = true))
        }
    }

    @Test
    fun `a broken download and a refused install read the same, because the listener's options are`() {
        assertEquals(
            UpdateState.DownloadFailed(update).toBannerState(),
            UpdateState.InstallFailed(update).toBannerState(),
        )
    }
}
