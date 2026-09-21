package app.needler.feature.player.output

import app.cash.turbine.test
import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.OutputTarget
import app.needler.feature.player.fake.FakePlaybackSettingsRepository
import app.needler.feature.player.fake.PlayerFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The "Play on" picker, screen 21.
 *
 * The behaviour REQUIREMENTS.md is specific about: a Cast receiver that cannot reach the server is
 * *listed with the reason* rather than hidden or allowed to fail after the user picks it. Both
 * halves of that are tested here - it stays in the list, and picking it does nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutputViewModelTest {

    private val settings = FakePlaybackSettingsRepository(
        targets = listOf(
            PlayerFixtures.livingRoomSpeaker,
            PlayerFixtures.pixelBuds,
            PlayerFixtures.thisPhone,
            PlayerFixtures.kitchen,
        ),
        selected = PlayerFixtures.livingRoomSpeaker,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Cast, Bluetooth and this device arrive in one list`() = runTest {
        val viewModel = OutputViewModel(settings)

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()

            assertEquals(4, state.targets.size)
            assertTrue(state.targets.any { it is OutputTarget.Cast })
            assertTrue(state.targets.any { it is OutputTarget.Bluetooth })
            assertTrue(state.targets.any { it is OutputTarget.ThisDevice })
            assertTrue(state.isSelected(PlayerFixtures.livingRoomSpeaker))
            assertFalse(state.isSelected(PlayerFixtures.kitchen))
        }
    }

    @Test
    fun `an empty list means still looking, not that the phone has no speaker`() = runTest {
        val empty = FakePlaybackSettingsRepository()
        val viewModel = OutputViewModel(empty)

        viewModel.state.test {
            val state: OutputUiState = awaitItem()
            assertTrue(state.discovering)
            assertFalse(state.isEmpty)
        }
    }

    @Test
    fun `picking a target switches output`() = runTest {
        val viewModel = OutputViewModel(settings)
        viewModel.state.test { awaitItem() }

        viewModel.select(PlayerFixtures.pixelBuds)

        assertEquals(listOf("bt-pixel-buds"), settings.selectedOutputs)
    }

    @Test
    fun `a Cast target that cannot reach the server is listed and refused`() = runTest {
        settings.emitTargets(
            listOf(
                PlayerFixtures.thisPhone,
                PlayerFixtures.unreachableCast(CastAvailability.SERVER_NOT_REACHABLE),
            ),
        )
        val viewModel = OutputViewModel(settings)

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()

            val cast: OutputTarget = state.targets.first { it is OutputTarget.Cast }
            assertFalse("it must not be selectable", cast.isSelectable)
            assertNotNull(
                "the picker has to say why rather than failing later",
                app.needler.feature.player.ui.PlayerFormat.unavailableReason(cast),
            )

            viewModel.select(cast)
            assertTrue("no switch should have been attempted", settings.selectedOutputs.isEmpty())
        }
    }

    @Test
    fun `an unprobed Cast target is flagged while the probe runs`() = runTest {
        settings.emitTargets(
            listOf(
                PlayerFixtures.thisPhone,
                PlayerFixtures.unreachableCast(CastAvailability.UNKNOWN),
            ),
        )
        val viewModel = OutputViewModel(settings)

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()
            assertTrue(state.hasPendingProbe)
        }
    }

    @Test
    fun `a failed switch is explained in the sheet`() = runTest {
        settings.selectOutputResult = FakePlaybackSettingsRepository.OutputFailure
        val viewModel = OutputViewModel(settings)

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()
            assertNull(state.error)

            viewModel.select(PlayerFixtures.pixelBuds)

            assertNotNull(awaitItem().error)
        }
    }

    @Test
    fun `volume is not offered, because nothing can set it`() = runTest {
        val viewModel = OutputViewModel(settings)

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()
            // PlaybackController has no volume command; drawing a slider that did nothing would be
            // worse than not drawing one. See OutputUiState.volume.
            assertNull(state.volume)
        }
        viewModel.setVolume(0.5f)
    }
}
