package app.needler.feature.player.output

import app.cash.turbine.test
import app.needler.core.domain.model.CastAvailability
import app.needler.core.domain.model.OutputTarget
import app.needler.feature.player.fake.FakePlaybackSettingsRepository
import app.needler.feature.player.fake.PlayerFixtures
import app.needler.feature.player.ui.PlayerFormat
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
 * Two dependencies, and the split is the thing most of these tests are about. [FakeOutputRouter] stands
 * for `:player:service`, which enumerates devices because REQUIREMENTS.md "Output" puts route discovery
 * in the layer that owns the Media3 session; [FakePlaybackSettingsRepository] remembers the choice.
 *
 * The behaviour REQUIREMENTS.md is specific about: a Cast receiver that cannot reach the server is
 * *listed with the reason* rather than hidden or allowed to fail after the user picks it, and the output
 * in force is the one sound is actually coming out of rather than the one last tapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OutputViewModelTest {

    private val settings = FakePlaybackSettingsRepository(selected = PlayerFixtures.livingRoomSpeaker)

    private val router = FakeOutputRouter(
        targets = listOf(
            PlayerFixtures.livingRoomSpeaker,
            PlayerFixtures.pixelBuds,
            PlayerFixtures.thisPhone,
            PlayerFixtures.kitchen,
        ),
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
        val viewModel = OutputViewModel(router, settings)

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
        val viewModel = OutputViewModel(FakeOutputRouter(), FakePlaybackSettingsRepository())

        viewModel.state.test {
            val state: OutputUiState = awaitItem()
            assertTrue(state.discovering)
            assertFalse(state.isEmpty)
        }
    }

    @Test
    fun `with no Bluetooth device the picker lists this device and says so`() = runTest {
        // What the router emits on a phone with nothing attached: one row, and it is not an empty list,
        // so the sheet draws a real choice rather than "No speakers found".
        val viewModel = OutputViewModel(
            FakeOutputRouter(targets = listOf(PlayerFixtures.thisPhone), active = PlayerFixtures.thisPhone),
            FakePlaybackSettingsRepository(selected = PlayerFixtures.thisPhone),
        )

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()

            assertEquals(listOf("This phone"), state.targets.map { it.displayName })
            assertFalse(state.discovering)
            assertFalse(state.isEmpty)
            assertTrue(state.isSelected(PlayerFixtures.thisPhone))
        }
    }

    @Test
    fun `one connected speaker is listed as connected and named`() = runTest {
        val viewModel = OutputViewModel(
            FakeOutputRouter(
                targets = listOf(PlayerFixtures.livingRoomSpeaker, PlayerFixtures.thisPhone),
                active = PlayerFixtures.livingRoomSpeaker,
            ),
            settings,
        )

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()

            val speaker = state.targets.first { it is OutputTarget.Bluetooth } as OutputTarget.Bluetooth
            assertEquals("Living room speaker", speaker.displayName)
            assertTrue(speaker.isConnected)
            assertEquals("Bluetooth · connected", PlayerFormat.outputDetail(speaker))
        }
    }

    @Test
    fun `a speaker connecting and disconnecting mid-session moves the list and the tick`() = runTest {
        val live = FakeOutputRouter(targets = listOf(PlayerFixtures.thisPhone), active = PlayerFixtures.thisPhone)
        val viewModel = OutputViewModel(live, FakePlaybackSettingsRepository(selected = PlayerFixtures.thisPhone))

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()
            assertEquals(1, state.targets.size)

            live.emitTargets(listOf(PlayerFixtures.livingRoomSpeaker, PlayerFixtures.thisPhone))
            live.emitActive(PlayerFixtures.livingRoomSpeaker)

            state = expectMostRecentItem()
            assertEquals(2, state.targets.size)
            assertTrue(state.isSelected(PlayerFixtures.livingRoomSpeaker))

            live.emitTargets(listOf(PlayerFixtures.thisPhone))
            live.emitActive(PlayerFixtures.thisPhone)

            state = expectMostRecentItem()
            assertEquals(1, state.targets.size)
            // The tick comes back to this device rather than staying on a speaker that has gone.
            assertTrue(state.isSelected(PlayerFixtures.thisPhone))
        }
    }

    @Test
    fun `a sink with no readable name is still named`() = runTest {
        // What the mapper produces for a device whose name the framework would not give up. The picker
        // must not draw a blank row: "The current output is always named in the player."
        val nameless = OutputTarget.Bluetooth(id = "addr:11", displayName = "Bluetooth device", isConnected = true)
        val viewModel = OutputViewModel(
            FakeOutputRouter(targets = listOf(nameless, PlayerFixtures.thisPhone), active = nameless),
            settings,
        )

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()

            assertEquals("Bluetooth device", state.targets.first().displayName)
            assertTrue(PlayerFormat.outputName(state.selected).isNotBlank())
            assertTrue(state.isSelected(nameless))
        }
    }

    @Test
    fun `the live route wins the tick over the remembered choice`() = runTest {
        // They disagree whenever a speaker drops out from under a running track, and both are true.
        // Ticking the remembered row would put the tick on a speaker that is silent.
        val viewModel = OutputViewModel(
            FakeOutputRouter(
                targets = listOf(PlayerFixtures.livingRoomSpeaker, PlayerFixtures.pixelBuds, PlayerFixtures.thisPhone),
                active = PlayerFixtures.pixelBuds,
            ),
            settings,
        )

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()

            assertTrue(state.isSelected(PlayerFixtures.pixelBuds))
            assertFalse(state.isSelected(PlayerFixtures.livingRoomSpeaker))
        }
    }

    @Test
    fun `with no live route the remembered choice is what the picker ticks`() = runTest {
        // Below API 33 the platform will not say which device is routed, so the router emits null and
        // the remembered selection is the best answer there is.
        val viewModel = OutputViewModel(
            FakeOutputRouter(targets = listOf(PlayerFixtures.livingRoomSpeaker, PlayerFixtures.thisPhone)),
            settings,
        )

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()

            assertTrue(state.isSelected(PlayerFixtures.livingRoomSpeaker))
        }
    }

    @Test
    fun `picking a target switches output`() = runTest {
        val viewModel = OutputViewModel(router, settings)
        viewModel.state.test { awaitItem() }

        viewModel.select(PlayerFixtures.pixelBuds)

        assertEquals(listOf("bt-pixel-buds"), settings.selectedOutputs)
    }

    @Test
    fun `a Cast target that cannot reach the server is listed and refused`() = runTest {
        router.emitTargets(
            listOf(
                PlayerFixtures.thisPhone,
                PlayerFixtures.unreachableCast(CastAvailability.SERVER_NOT_REACHABLE),
            ),
        )
        val viewModel = OutputViewModel(router, settings)

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()

            val cast: OutputTarget = state.targets.first { it is OutputTarget.Cast }
            assertFalse("it must not be selectable", cast.isSelectable)
            assertNotNull(
                "the picker has to say why rather than failing later",
                PlayerFormat.unavailableReason(cast),
            )

            viewModel.select(cast)
            assertTrue("no switch should have been attempted", settings.selectedOutputs.isEmpty())
        }
    }

    @Test
    fun `an unprobed Cast target is flagged while the probe runs`() = runTest {
        router.emitTargets(
            listOf(
                PlayerFixtures.thisPhone,
                PlayerFixtures.unreachableCast(CastAvailability.UNKNOWN),
            ),
        )
        val viewModel = OutputViewModel(router, settings)

        viewModel.state.test {
            var state: OutputUiState = awaitItem()
            if (state.discovering) state = awaitItem()
            assertTrue(state.hasPendingProbe)
        }
    }

    @Test
    fun `a failed switch is explained in the sheet`() = runTest {
        settings.selectOutputResult = FakePlaybackSettingsRepository.OutputFailure
        val viewModel = OutputViewModel(router, settings)

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
        val viewModel = OutputViewModel(router, settings)

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
