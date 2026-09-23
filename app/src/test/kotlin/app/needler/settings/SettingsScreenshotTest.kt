// kotlinx.datetime.Instant is a deprecated typealias for kotlin.time.Instant from kotlinx-datetime
// 0.7.0 onwards. See SettingsUiStateTest for the same note.
@file:OptIn(ExperimentalTime::class)

package app.needler.settings

import android.app.Application
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EqPreset
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.screenshot.NeedlerDevice
import app.needler.screenshot.NeedlerScreenshots
import app.needler.screenshot.assertRendered
import app.needler.screenshot.captureNeedlerScreen
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders Settings - screen 12 - in each state it has to explain.
 *
 * There is one image per condition that changes what the screen *says*, not merely how it looks,
 * because that is the set of states worth regression-testing: a fresh install with no server, a
 * server that cannot transcode and therefore hides a whole row, a device below its free-space
 * floor, a destructive action waiting for its second tap, and a companion session inside its last
 * five days.
 *
 * `application = Application::class` keeps Hilt out of it, exactly as `ConnectScreenshotTest` does.
 * Everything here renders the stateless [SettingsScreen] from a literal [SettingsUiState] with
 * [INERT] callbacks; nothing needs a dependency graph, a `DataStore`, a cache index or a server.
 *
 * ## The goldens do not exist yet
 *
 * These tests write PNGs on their first run and cannot be verified against committed images until
 * someone with a JDK 21 and the Android SDK has run `./gradlew :app:recordScreenshots` and
 * committed what came out. Until then CI's `test -Pneedler.screenshots.verify` step has nothing to
 * compare against for the six names below.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [NeedlerScreenshots.SDK], application = Application::class)
class SettingsScreenshotTest {

    @Test
    fun `settings on a phone`() {
        capture("settings", NeedlerDevice.Phone, FULL)
    }

    @Test
    fun `settings on a tablet`() {
        capture("settings", NeedlerDevice.Tablet, FULL)
    }

    @Test
    fun `a fresh install, before a server has been connected`() {
        capture("settings-fresh", NeedlerDevice.Phone, SettingsUiState(loading = false))
    }

    @Test
    fun `a server that cannot transcode hides the mobile-data row entirely`() {
        // REQUIREMENTS.md, rule 3 of "Streaming": hidden, not disabled - on a server without ffmpeg
        // there is nothing for a user to go and enable.
        capture(
            "settings-no-transcoding",
            NeedlerDevice.Phone,
            FULL.copy(playing = FULL.playing.copy(transcodingAvailable = false)),
        )
    }

    @Test
    fun `a device below its free-space floor is warned rather than emptied`() {
        capture(
            "settings-low-on-space",
            NeedlerDevice.Phone,
            FULL.copy(
                storage = FULL.storage.copy(
                    deviceFreeBytes = 1_288_490_188L,
                    lowOnSpace = true,
                    shortfallBytes = 805_306_368L,
                ),
            ),
        )
    }

    @Test
    fun `remove all from device waits for a second tap`() {
        capture(
            "settings-confirm-remove-all",
            NeedlerDevice.Phone,
            FULL.copy(armedAction = DestructiveSettingsAction.RemoveAllFromDevice),
        )
    }

    @Test
    fun `a session in its last five days says so`() {
        capture(
            "settings-session-expiring",
            NeedlerDevice.Phone,
            FULL.copy(
                server = FULL.server.copy(
                    sessionNotice = "This device's sign-in expires in 4 days. Sign in again to " +
                        "keep search and pulls working.",
                ),
            ),
        )
    }

    private fun capture(name: String, device: NeedlerDevice, state: SettingsUiState) {
        val file = captureNeedlerScreen(name, device) {
            SettingsScreen(
                state = state,
                callbacks = INERT,
                widthSizeClass = when (device) {
                    NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                    NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
                },
            )
        }
        assertRendered(file, device)
    }

    private companion object {

        /** `2026-09-23T10:00:00Z`, and two minutes earlier, so "Last synced" reads as the pack does. */
        val NOW: Instant = Instant.fromEpochSeconds(1_790_157_600L)
        val TWO_MINUTES_AGO: Instant = Instant.fromEpochSeconds(1_790_157_480L)

        /**
         * Every callback, wired to nothing.
         *
         * Spelled out rather than defaulted, because [SettingsCallbacks] deliberately has no
         * defaults: a `{}` default is the inert tap target the whole project refuses to create, and
         * a test is the one place where inert is the point.
         */
        val INERT = SettingsCallbacks(
            onSyncNow = {},
            onChangeServer = {},
            onGaplessChange = {},
            onOpenCrossfade = {},
            onOpenEqualiser = {},
            onTranscodeOnMobileDataChange = {},
            onScrobblingChange = {},
            onNotifyPullFinishedChange = {},
            onNotifyPullFailedChange = {},
            onNotifyNewReleaseChange = {},
            onKeepPulledAlbumsChange = {},
            onWifiOnlyDownloadsChange = {},
            onRemoveDownload = {},
            onClearCachedMusic = {},
            onCheckForUpdates = {},
            onArmDestructiveAction = {},
            onCancelDestructiveAction = {},
            onConfirmDestructiveAction = {},
            onOpenLicences = {},
        )

        /** The pack's own placeholder values, plus the figures REQUIREMENTS.md's Storage rewrite adds. */
        val FULL = SettingsUiState(
            loading = false,
            server = ServerSectionState(
                host = "music.yourhome.net",
                username = "tim",
                lastSyncedAt = TWO_MINUTES_AGO,
                renderedAt = NOW,
            ),
            playing = PlayingSectionState(
                gaplessEnabled = true,
                crossfade = CrossfadeDuration.OFF,
                equaliserEnabled = true,
                equaliserPreset = EqPreset.FLAT,
                scrobblingEnabled = false,
                scrobbleTargets = listOf("ListenBrainz"),
                transcodeOnMobileData = true,
                transcodingAvailable = true,
            ),
            notifications = NotificationSectionState(
                pullFinished = true,
                pullFailed = true,
                newReleaseFromFollowedArtist = false,
            ),
            storage = StorageSectionState(
                downloadedBytes = 2_254_857_830L,
                cachedBytes = 671_088_640L,
                artworkBytes = 46_137_344L,
                deviceFreeBytes = 32_212_254_720L,
                downloadedAlbums = listOf(
                    downloaded("Submarine", "The Marías", 1_181_116_006L, "0a1b"),
                    downloaded("Con Todo El Mundo", "Khruangbin", 734_003_200L, "1b2c"),
                    downloaded("Fetch the Bolt Cutters", "Fiona Apple", 339_738_624L, "2c3d"),
                ),
                keepPulledAlbumsOnDevice = true,
                downloadToDeviceOnWifiOnly = true,
            ),
            about = AboutSectionState(versionName = "0.1.0", versionCode = 10_100L),
        )

        fun downloaded(
            title: String,
            artist: String,
            sizeBytes: Long,
            suffix: String,
        ): DownloadedAlbum = DownloadedAlbum(
            releaseGroupMbid = ReleaseGroupMbid("d2b6bd7d-8d2d-4f33-9a8e-3cbb1cf1a$suffix"),
            title = title,
            artistName = artist,
            sizeBytes = sizeBytes,
            pinnedAt = TWO_MINUTES_AGO,
        )
    }
}
