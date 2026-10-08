// kotlinx.datetime.Instant is a deprecated typealias for kotlin.time.Instant from kotlinx-datetime
// 0.7.0 onwards. See SettingsUiStateTest for the same note.
@file:OptIn(ExperimentalTime::class)

package app.needler.settings

import android.app.Application
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.EqPreset
import app.needler.core.domain.model.StreamRung
import app.needler.screenshot.NeedlerDevice
import app.needler.screenshot.NeedlerScreenshots
import app.needler.screenshot.assertRendered
import app.needler.screenshot.captureNeedlerScreen
import java.io.File
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.Assert.assertFalse
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
 * ## Two of these used to be pictures of the wrong thing
 *
 * `settings-low-on-space-phone.png` and `settings-confirm-remove-all-phone.png` were **byte-identical
 * to `settings-phone.png`**, verified by md5. Both states render below the fold on the pack's 390x844
 * artboard, Roborazzi captures the window, and the window never reached either of them - so the
 * confirmation in front of "Remove all from device", the most destructive action in the app, had a
 * regression test that would have passed with the confirmation deleted.
 *
 * Both are captured scrolled now, through [SettingsScreen]'s `listState`, and [assertScrolledPast] is
 * what stops them silently going back: it re-renders the unscrolled screen and fails if the golden
 * matches it, which is exactly the condition that held before.
 *
 * ## There is still no golden for the downloaded-album list
 *
 * Scrolling makes it reachable; it does not make it worth an image. The truncation rule is a pure
 * function on [StorageSectionState] - `downloadedAlbumsInline` and `downloadedAlbumsTruncated` - and
 * is asserted in `SettingsUiStateTest`, where three albums, fourteen and fourteen-with-no-destination
 * can be told apart by their values rather than by eye. The list itself has its own images in
 * `DownloadsScreenshotTest`, where it is the whole screen.
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
        // there is nothing for a user to go and enable. `transcodingNegotiated` is named explicitly
        // because that is what distinguishes this from the test below: the server answered, and the
        // answer was no.
        capture(
            "settings-no-transcoding",
            NeedlerDevice.Phone,
            FULL.copy(
                playing = FULL.playing.copy(
                    transcodingAvailable = false,
                    transcodingNegotiated = true,
                ),
            ),
        )
    }

    @Test
    fun `a server that has not been asked still offers both rungs`() {
        // The state the device was in: capabilities are negotiated at sign-in, held in memory, and
        // never re-read on launch, so after a restart `transcodingAvailable` is false without the
        // server having refused anything. Drawing the refusal there made the finished eight-rung
        // ladder unreachable. Both pickers are drawn, with one line saying the answer is not in yet.
        capture(
            "settings-transcoding-unconfirmed",
            NeedlerDevice.Phone,
            FULL.copy(
                playing = FULL.playing.copy(
                    transcodingAvailable = false,
                    transcodingNegotiated = false,
                ),
            ),
        )
    }

    @Test
    fun `a device below its free-space floor is warned rather than emptied`() {
        assertScrolledPast(
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
                // The warning sits under the four usage figures inside Storage, so the section has
                // to be at the top of the window for the capture to contain it.
                scrolledTo = STORAGE_ITEM,
            ),
        )
    }

    @Test
    fun `remove all from device waits for a second tap`() {
        assertScrolledPast(
            capture(
                "settings-confirm-remove-all",
                NeedlerDevice.Phone,
                FULL.copy(armedAction = DestructiveSettingsAction.RemoveAllFromDevice),
                // The albums header, so the capture carries the three rows above the confirmation as
                // well as the confirmation: a prompt about removing everything is worth seeing with
                // some of "everything" still on screen.
                scrolledTo = ALBUMS_HEADER_ITEM,
            ),
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

    private fun capture(
        name: String,
        device: NeedlerDevice,
        state: SettingsUiState,
        scrolledTo: Int = 0,
    ): File {
        val file = captureNeedlerScreen(name, device) {
            SettingsScreen(
                state = state,
                callbacks = INERT,
                widthSizeClass = when (device) {
                    NeedlerDevice.Phone -> WindowWidthSizeClass.Compact
                    NeedlerDevice.Tablet -> WindowWidthSizeClass.Expanded
                },
                listState = LazyListState(firstVisibleItemIndex = scrolledTo),
            )
        }
        assertRendered(file, device)
        return file
    }

    /**
     * Fails if [golden] is the same image as the unscrolled screen.
     *
     * The whole point of the two scrolled captures is that their subject is below the fold, and the
     * way that stopped being true before was silent: the scroll was never applied, the window showed
     * the top of the list, and two goldens settled at the same bytes as `settings-phone.png` while
     * still being named for states neither of them contained. An index that drifts as items are added
     * or removed from the list would do it again.
     *
     * So the baseline is re-rendered here rather than read from disk. Rendering is deterministic -
     * `reducedMotion` leaves nothing animating and every value in [FULL] is a literal - so this writes
     * the same bytes `settings on a phone` writes, and comparing against it compares against *this*
     * build's top-of-screen image rather than against whatever was committed last.
     */
    private fun assertScrolledPast(golden: File) {
        val top: File = capture("settings", NeedlerDevice.Phone, FULL)
        assertFalse(
            golden.name + " is byte-identical to " + top.name + ", so it is a picture of the top of " +
                "Settings and not of the state it is named for",
            golden.readBytes().contentEquals(top.readBytes()),
        )
    }

    private companion object {

        /**
         * The Storage section's index in [SettingsScreen]'s list.
         *
         * The list is title, server, playing, notifications, storage, then the album block, then the
         * storage actions, About and Sign out. These two constants are the only place the ordering is
         * written down twice, which is why [assertScrolledPast] exists: a drift here produces a golden
         * of the wrong part of the screen, and that is the one failure these two images already had.
         */
        const val STORAGE_ITEM = 4

        /** The "Albums on this device" header, directly after the Storage section. */
        const val ALBUMS_HEADER_ITEM = 5

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
            onWifiRungChange = {},
            onDataRungChange = {},
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
            // Both optional rows are wired here so the goldens show them. Left null they vanish,
            // which is the point of their being nullable - and a screenshot of a screen with two
            // rows missing is a screenshot of a wiring gap rather than of the screen.
            onOpenDiagnostics = {},
            onOpenLicences = {},
            // Wired for the same reason, with the opposite consequence: null does not remove
            // anything here, it draws every downloaded album inline. See the unwired test, which is
            // the only one that passes null.
            onOpenDownloads = {},
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
                // Empty, so every golden shows "Report plays to your server" with "Your server
                // decides where they go." under it. Seven of these images used to read "Scrobble to
                // ListenBrainz", which is what REQUIREMENTS.md's design-pack discrepancy table lists
                // against screen 12 as the thing to stop doing: the server "forwards to ListenBrainz
                // or Last.fm according to the user's server-side preferences", so a pack of goldens
                // that all name one of them teaches a Last.fm user that their plays go somewhere
                // they do not. The label that does name a target is a pure function of this list and
                // is asserted, in all three of its shapes, in `SettingsUiStateTest`.
                scrobbleTargets = emptyList(),
                wifiRung = StreamRung.ORIGINAL,
                dataRung = StreamRung.MP3_320,
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
                // A handful, which is what the device this was reported from holds: Settings draws
                // all three inline and offers no "See all". The bigger library has its own test.
                downloadedAlbums = DownloadedAlbumFixtures.FEW,
                keepPulledAlbumsOnDevice = true,
                downloadToDeviceOnWifiOnly = true,
            ),
            about = AboutSectionState(versionName = "0.1.0", versionCode = 10_100L),
        )
    }
}
