package app.needler.feature.player.screenshot

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.playback.PlaybackProgress
import app.needler.core.domain.playback.RepeatMode
import app.needler.feature.player.PlayerUiState
import app.needler.feature.player.crate.CrateScreen
import app.needler.feature.player.crate.CrateUiState
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.nowplaying.MiniPlayer
import app.needler.feature.player.nowplaying.NowPlayingScreen
import app.needler.feature.player.sidebar.PlayerSidebarContent
import app.needler.feature.player.ui.SleepTimerChoice
import app.needler.feature.player.ui.SleepTimerChoices
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Now Playing, the crate, the mini player and the tablet sidebar, rendered to PNGs.
 *
 * Every screen is rendered in at least two states: with something playing, which is what the design
 * pack draws, and with nothing playing, which REQUIREMENTS.md says is the commonest state of these
 * surfaces and therefore the one most worth looking at.
 *
 * `application = Application::class` keeps Hilt out of it. These are the stateless screens, rendered
 * from literal states - no view model, no session, no Media3.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [PlayerScreenshots.SDK], application = Application::class)
class PlayerScreenshotTest {

    // ---- Now playing (07) ---------------------------------------------------

    @Test
    fun `now playing, as the pack draws it`() {
        capture("player-now-playing", PlayerDevice.Phone) {
            NowPlayingScreen(
                state = PLAYING,
                progress = { PROGRESS },
                onClose = {},
                onOpenCrate = {},
                onPlayPause = {},
                onNext = {},
                onPrevious = {},
                onSeek = {},
                onToggleShuffle = {},
                onCycleRepeat = {},
                onChooseOutput = {},
                onToggleFavourite = {},
                onChooseSleepTimer = {},
                onOpenArtist = {},
            )
        }
    }

    @Test
    fun `now playing, paused, with shuffle and repeat on`() {
        capture("player-now-playing-paused", PlayerDevice.Phone) {
            NowPlayingScreen(
                state = PLAYING.copy(
                    isPlaying = false,
                    shuffleEnabled = true,
                    repeatMode = RepeatMode.ALL,
                ),
                progress = { PROGRESS },
                onClose = {},
                onOpenCrate = {},
                onPlayPause = {},
                onNext = {},
                onPrevious = {},
                onSeek = {},
                onToggleShuffle = {},
                onCycleRepeat = {},
                onChooseOutput = {},
                onToggleFavourite = {},
                onChooseSleepTimer = {},
                onOpenArtist = {},
            )
        }
    }

    @Test
    fun `now playing with nothing playing`() {
        capture("player-now-playing-idle", PlayerDevice.Phone) {
            NowPlayingScreen(
                state = PlayerUiState.Idle,
                progress = { PlaybackProgress.Zero },
                onClose = {},
                onOpenCrate = {},
                onPlayPause = {},
                onNext = {},
                onPrevious = {},
                onSeek = {},
                onToggleShuffle = {},
                onCycleRepeat = {},
                onChooseOutput = {},
                onToggleFavourite = {},
                onChooseSleepTimer = {},
                onOpenArtist = {},
            )
        }
    }

    @Test
    fun `now playing when the track will not play`() {
        capture("player-now-playing-error", PlayerDevice.Phone) {
            NowPlayingScreen(
                state = PLAYING.copy(
                    isPlaying = false,
                    isBuffering = true,
                    error = NeedlerError.Offline(OfflineCause.TIMEOUT),
                ),
                progress = { PROGRESS },
                onClose = {},
                onOpenCrate = {},
                onPlayPause = {},
                onNext = {},
                onPrevious = {},
                onSeek = {},
                onToggleShuffle = {},
                onCycleRepeat = {},
                onChooseOutput = {},
                onToggleFavourite = {},
                onChooseSleepTimer = {},
                onOpenArtist = {},
            )
        }
    }

    // ---- The crate (08) -----------------------------------------------------

    @Test
    fun `the crate, as the pack draws it`() {
        capture("player-crate", PlayerDevice.Phone) {
            CrateScreen(
                state = CrateUiState(queue = PlayerFixtures.crate, isPlaying = true),
                onBack = {},
                onClear = {},
                onPlayItem = {},
                onMove = { _, _ -> },
                onRemove = {},
            )
        }
    }

    @Test
    fun `the crate with nothing in it`() {
        capture("player-crate-empty", PlayerDevice.Phone) {
            CrateScreen(
                state = CrateUiState.Empty,
                onBack = {},
                onClear = {},
                onPlayItem = {},
                onMove = { _, _ -> },
                onRemove = {},
            )
        }
    }

    @Test
    fun `the crate on the last track, with nothing up next`() {
        capture("player-crate-last-track", PlayerDevice.Phone) {
            CrateScreen(
                state = CrateUiState(
                    queue = PlayQueue(
                        items = PlayerFixtures.crate.items,
                        currentIndex = PlayerFixtures.crate.items.lastIndex,
                    ),
                    isPlaying = true,
                ),
                onBack = {},
                onClear = {},
                onPlayItem = {},
                onMove = { _, _ -> },
                onRemove = {},
            )
        }
    }

    // ---- The mini player (06, 13) -------------------------------------------

    @Test
    fun `the mini player`() {
        capture("player-mini", PlayerDevice.Bar) {
            Box(modifier = Modifier.fillMaxSize().background(NeedlerTheme.colors.canvas)) {
                MiniPlayer(state = PLAYING, onExpand = {}, onPlayPause = {}, onNext = {})
            }
        }
    }

    @Test
    fun `the mini player, paused`() {
        capture("player-mini-paused", PlayerDevice.Bar) {
            Box(modifier = Modifier.fillMaxSize().background(NeedlerTheme.colors.canvas)) {
                MiniPlayer(
                    state = PLAYING.copy(isPlaying = false),
                    onExpand = {},
                    onPlayPause = {},
                    onNext = {},
                )
            }
        }
    }

    @Test
    fun `the mini player with nothing playing`() {
        capture("player-mini-idle", PlayerDevice.Bar) {
            Box(modifier = Modifier.fillMaxSize().background(NeedlerTheme.colors.canvas)) {
                MiniPlayer(
                    state = PlayerUiState.Idle,
                    onExpand = {},
                    onPlayPause = {},
                    onNext = {},
                )
            }
        }
    }

    // ---- The tablet sidebar (09) --------------------------------------------

    @Test
    fun `the tablet sidebar`() {
        capture("player-sidebar", PlayerDevice.Sidebar) {
            PlayerSidebarContent(
                state = PLAYING.copy(
                    output = PlayerFixtures.thisTablet,
                    upNextCount = 4,
                ),
                crate = CrateUiState(queue = PlayerFixtures.crate, isPlaying = true),
                progress = { PROGRESS },
                onPlayPause = {},
                onNext = {},
                onPrevious = {},
                onSeek = {},
                onToggleShuffle = {},
                onCycleRepeat = {},
                onChooseOutput = {},
                onToggleFavourite = {},
                onChooseSleepTimer = {},
                onOpenArtist = {},
                onPlayItem = {},
                onMove = { _, _ -> },
                onRemove = {},
            )
        }
    }

    @Test
    fun `the tablet sidebar with nothing playing`() {
        capture("player-sidebar-idle", PlayerDevice.Sidebar) {
            PlayerSidebarContent(
                state = PlayerUiState.Idle,
                crate = CrateUiState.Empty,
                progress = { PlaybackProgress.Zero },
                onPlayPause = {},
                onNext = {},
                onPrevious = {},
                onSeek = {},
                onToggleShuffle = {},
                onCycleRepeat = {},
                onChooseOutput = {},
                onToggleFavourite = {},
                onChooseSleepTimer = {},
                onOpenArtist = {},
                onPlayItem = {},
                onMove = { _, _ -> },
                onRemove = {},
            )
        }
    }

    // ---- 200% text (REQUIREMENTS.md, Accessibility) -------------------------

    @Test
    fun `now playing at 200 percent text`() {
        capture("player-now-playing-large-text", PlayerDevice.Phone, fontScale = 2f) {
            NowPlayingScreen(
                state = PLAYING.copy(
                    item = PlayerFixtures.item(
                        "q1",
                        PlayerFixtures.sienna.copy(title = "After the Earthquake"),
                    ),
                ),
                progress = { PROGRESS },
                onClose = {},
                onOpenCrate = {},
                onPlayPause = {},
                onNext = {},
                onPrevious = {},
                onSeek = {},
                onToggleShuffle = {},
                onCycleRepeat = {},
                onChooseOutput = {},
                onToggleFavourite = {},
                onChooseSleepTimer = {},
                onOpenArtist = {},
            )
        }
    }

    @Test
    fun `the crate at 200 percent text`() {
        capture("player-crate-large-text", PlayerDevice.Phone, fontScale = 2f) {
            CrateScreen(
                state = CrateUiState(queue = PlayerFixtures.crate, isPlaying = true),
                onBack = {},
                onClear = {},
                onPlayItem = {},
                onMove = { _, _ -> },
                onRemove = {},
            )
        }
    }

    @Test
    fun `the mini player at 200 percent text`() {
        capture("player-mini-large-text", PlayerDevice.Bar, fontScale = 2f) {
            Box(modifier = Modifier.fillMaxSize().background(NeedlerTheme.colors.canvas)) {
                MiniPlayer(state = PLAYING, onExpand = {}, onPlayPause = {}, onNext = {})
            }
        }
    }

    // ---- The landscape sidebar (not drawn; the P0 this file exists to catch) ----

    @Test
    fun `the sidebar at a landscape phone's height keeps its controls`() {
        capture("player-sidebar-short", PlayerDevice.SidebarShort) {
            PlayerSidebarContent(
                state = PLAYING.copy(output = PlayerFixtures.thisPhone, upNextCount = 4),
                crate = CrateUiState(queue = PlayerFixtures.crate, isPlaying = true),
                progress = { PROGRESS },
                onPlayPause = {},
                onNext = {},
                onPrevious = {},
                onSeek = {},
                onToggleShuffle = {},
                onCycleRepeat = {},
                onChooseOutput = {},
                onToggleFavourite = {},
                onChooseSleepTimer = {},
                onOpenArtist = {},
                onPlayItem = {},
                onMove = { _, _ -> },
                onRemove = {},
            )
        }
    }

    // ---- The controls the pack does not draw --------------------------------

    @Test
    fun `now playing with the track favourited and a sleep timer running`() {
        capture("player-now-playing-favourited", PlayerDevice.Phone) {
            NowPlayingScreen(
                state = PLAYING.copy(
                    isFavourite = true,
                    // EndOfTrack rather than a timed stop: a timed stop's label is a countdown against
                    // the wall clock, and an image recorded from one would differ by a minute depending
                    // on when it was taken. The countdown's wording is asserted in PlayerFormatTest,
                    // where a fixed clock makes it arithmetic.
                    sleepTimer = SleepTimer.EndOfTrack,
                ),
                progress = { PROGRESS },
                onClose = {},
                onOpenCrate = {},
                onPlayPause = {},
                onNext = {},
                onPrevious = {},
                onSeek = {},
                onToggleShuffle = {},
                onCycleRepeat = {},
                onChooseOutput = {},
                onToggleFavourite = {},
                onChooseSleepTimer = {},
                onOpenArtist = {},
            )
        }
    }

    @Test
    fun `the sleep timer's choices`() {
        capture("player-sleep-timer-choices", PlayerDevice.Phone) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NeedlerTheme.colors.canvas)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                SleepTimerChoices(selected = SleepTimerChoice.MINUTES_30, onChoose = {})
            }
        }
    }

    private fun capture(
        name: String,
        device: PlayerDevice,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        val file = capturePlayerScreen(name, device, fontScale) {
            Box(modifier = Modifier.fillMaxSize().background(NeedlerTheme.colors.canvas)) {
                content()
            }
        }
        assertRendered(file, device)
    }

    private companion object {
        /** What screen 07 is drawn around: Sienna, playing on the living room speaker. */
        val PLAYING = PlayerUiState(
            item = PlayerFixtures.playingItem,
            isPlaying = true,
            durationMs = 200_000L,
            output = PlayerFixtures.livingRoomSpeaker,
            upNextCount = 6,
        )

        /** 1:16 of 3:20, which is the 38% the pack draws the thumb at. */
        val PROGRESS = PlaybackProgress(positionMs = 76_000L, bufferedPositionMs = 120_000L)
    }
}
