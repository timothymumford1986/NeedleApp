package app.needler.feature.player.screenshot

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.EqPreset
import app.needler.core.domain.model.EqSettings
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.nowplaying.NowPlayingScreen
import app.needler.feature.player.output.OutputPickerScreen
import app.needler.feature.player.output.OutputUiState
import app.needler.feature.player.settings.CrossfadeScreen
import app.needler.feature.player.settings.EqPresets
import app.needler.feature.player.settings.EqualiserScreen
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The three settings screens the player owns: 19, 20 and 21.
 *
 * Each is rendered in the state the pack draws and in the state that matters for behaviour - the
 * equaliser switched off, crossfade off, and a Cast target the picker has to explain rather than
 * offer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class PlayerSettingsScreenshotTest {

    // ---- The equaliser (19) -------------------------------------------------

    @Test
    fun `the equaliser, on Vinyl, as the pack draws it`() {
        capture("player-equaliser", PlayerDevice.Phone) {
            EqualiserScreen(
                settings = VINYL,
                onEnabledChange = {},
                onPresetSelected = {},
                onBandChange = { _, _ -> },
                onPreampChange = {},
                onResetToFlat = {},
                onBack = {},
            )
        }
    }

    @Test
    fun `the equaliser switched off`() {
        capture("player-equaliser-off", PlayerDevice.Phone) {
            EqualiserScreen(
                settings = EqSettings.Default,
                onEnabledChange = {},
                onPresetSelected = {},
                onBandChange = { _, _ -> },
                onPreampChange = {},
                onResetToFlat = {},
                onBack = {},
            )
        }
    }

    // ---- Crossfade (20) -----------------------------------------------------

    @Test
    fun `crossfade at four seconds, as the pack draws it`() {
        capture("player-crossfade", PlayerDevice.Phone) {
            CrossfadeScreen(
                settings = CrossfadeSettings(
                    duration = CrossfadeDuration.FOUR_SECONDS,
                    fadeOnPause = false,
                ),
                onDurationChange = {},
                onSuppressWithinAlbumChange = {},
                onFadeOnSkipChange = {},
                onFadeOnPauseChange = {},
                onBack = {},
                previewFrom = "Sienna",
                previewTo = "Hamptons",
            )
        }
    }

    @Test
    fun `crossfade off, where one track stops and the next starts`() {
        capture("player-crossfade-off", PlayerDevice.Phone) {
            CrossfadeScreen(
                settings = CrossfadeSettings(duration = CrossfadeDuration.OFF),
                onDurationChange = {},
                onSuppressWithinAlbumChange = {},
                onFadeOnSkipChange = {},
                onFadeOnPauseChange = {},
                onBack = {},
                previewFrom = "Sienna",
                previewTo = "Hamptons",
            )
        }
    }

    @Test
    fun `crossfade at its longest`() {
        capture("player-crossfade-twelve", PlayerDevice.Phone) {
            CrossfadeScreen(
                settings = CrossfadeSettings(duration = CrossfadeDuration.TWELVE_SECONDS),
                onDurationChange = {},
                onSuppressWithinAlbumChange = {},
                onFadeOnSkipChange = {},
                onFadeOnPauseChange = {},
                onBack = {},
                previewFrom = "Sienna",
                previewTo = "Hamptons",
            )
        }
    }

    // ---- The output picker (21) ---------------------------------------------

    @Test
    fun `the output picker, as the pack draws it`() {
        capture("player-output", PlayerDevice.Phone) {
            OutputPickerScreen(
                state = OutputUiState(
                    targets = listOf(
                        PlayerFixtures.livingRoomSpeaker,
                        PlayerFixtures.pixelBuds,
                        PlayerFixtures.thisPhone,
                        PlayerFixtures.kitchen,
                    ),
                    selected = PlayerFixtures.livingRoomSpeaker,
                    // The pack draws a volume slider; nothing can set it yet, so it is passed in
                    // here explicitly to render the screen the design shows. See OutputUiState.
                    volume = 0.55f,
                ),
                onSelect = {},
                onVolumeChange = {},
                backdrop = { NowPlayingBackdrop() },
            )
        }
    }

    @Test
    fun `the output picker explaining a Cast target it cannot use`() {
        capture("player-output-cast-unreachable", PlayerDevice.Phone) {
            OutputPickerScreen(
                state = OutputUiState(
                    targets = listOf(
                        PlayerFixtures.thisPhone,
                        PlayerFixtures.unreachableCast(CastAvailability.SERVER_NOT_REACHABLE),
                        OutputTarget.Cast(
                            id = "cast-bedroom",
                            displayName = "Bedroom",
                            availability = CastAvailability.SELF_SIGNED_CERTIFICATE,
                        ),
                    ),
                    selected = PlayerFixtures.thisPhone,
                ),
                onSelect = {},
                onVolumeChange = {},
            )
        }
    }

    @Test
    fun `the output picker while it is still looking`() {
        capture("player-output-discovering", PlayerDevice.Phone) {
            OutputPickerScreen(
                state = OutputUiState(discovering = true),
                onSelect = {},
                onVolumeChange = {},
            )
        }
    }

    /** The dimmed Now Playing the pack shows above the sheet on screen 21. */
    @Composable
    private fun NowPlayingBackdrop() {
        NowPlayingScreen(
            state = PlayerUiState(
                item = PlayerFixtures.playingItem,
                isPlaying = true,
                durationMs = 200_000L,
                output = PlayerFixtures.livingRoomSpeaker,
            ),
            progress = { PlaybackProgress(positionMs = 76_000L) },
            onClose = {},
            onOpenCrate = {},
            onPlayPause = {},
            onNext = {},
            onPrevious = {},
            onSeek = {},
            onToggleShuffle = {},
            onCycleRepeat = {},
            onChooseOutput = {},
        )
    }

    private fun capture(name: String, device: PlayerDevice, content: @Composable () -> Unit) {
        val file = capturePlayerScreen(name, device) {
            Box(modifier = Modifier.fillMaxSize().background(NeedlerTheme.colors.canvas)) {
                content()
            }
        }
        assertRendered(file, device)
    }

    private companion object {
        /** Screen 19's own state: Vinyl selected, the equaliser on, the preamp at -2 dB. */
        val VINYL: EqSettings = EqPresets.apply(
            preset = EqPreset.VINYL,
            to = EqSettings.Default.copy(isEnabled = true),
        )
    }
}
