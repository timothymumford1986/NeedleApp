package app.needler.feature.player.settings

import app.cash.turbine.test
import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.EqBand
import app.needler.core.domain.model.EqPreset
import app.needler.core.domain.model.EqSettings
import app.needler.feature.player.fake.FakePlaybackSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The equaliser, screen 19. */
@OptIn(ExperimentalCoroutinesApi::class)
class EqualiserViewModelTest {

    private val settings = FakePlaybackSettingsRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the equaliser starts off and flat`() = runTest {
        val viewModel = EqualiserViewModel(settings)

        viewModel.state.test {
            val state: EqSettings = awaitItem()
            assertFalse(state.isEnabled)
            assertEquals(EqPreset.FLAT, state.preset)
            assertEquals(EqSettings.FlatGains, state.bandGainsDb)
            assertEquals(EqSettings.BAND_COUNT, state.bandGainsDb.size)
        }
    }

    @Test
    fun `the Vinyl preset is the curve the pack draws`() = runTest {
        val viewModel = EqualiserViewModel(settings)
        viewModel.state.test { awaitItem() }

        viewModel.selectPreset(EqPreset.VINYL)

        // Screen 19's own readouts, band for band, plus its preamp.
        assertEquals(
            listOf(2f, 3f, 1f, 0f, -1f, 0f, 1f, 2f, 3f, 2f),
            settings.currentEq.bandGainsDb,
        )
        assertEquals(-2f, settings.currentEq.preampDb, 0.001f)
        assertEquals(EqPreset.VINYL, settings.currentEq.preset)
    }

    @Test
    fun `every preset supplies exactly ten gains`() {
        EqPresets.offered.forEach { preset ->
            assertEquals(
                EqPresets.label(preset) + " must supply " + EqSettings.BAND_COUNT + " gains",
                EqSettings.BAND_COUNT,
                EqPresets.gains(preset).size,
            )
        }
    }

    @Test
    fun `every preset stays inside the plus or minus twelve decibel range`() {
        EqPresets.offered.forEach { preset ->
            EqPresets.gains(preset).forEach { gain ->
                assertTrue(
                    EqPresets.label(preset) + " has a gain outside the range: " + gain,
                    gain in EqSettings.GAIN_RANGE_DB,
                )
            }
            assertTrue(
                EqPresets.label(preset) + " has a preamp outside the range",
                EqPresets.preampDb(preset) in EqSettings.GAIN_RANGE_DB,
            )
        }
    }

    @Test
    fun `moving a band by hand makes the preset custom`() = runTest {
        val viewModel = EqualiserViewModel(settings)
        viewModel.state.test { awaitItem() }

        viewModel.selectPreset(EqPreset.VINYL)
        viewModel.setBandGain(EqBand.KHZ_4, 7f)

        assertEquals(EqPreset.CUSTOM, settings.currentEq.preset)
        assertEquals(7f, settings.currentEq.gainFor(EqBand.KHZ_4), 0.001f)
        // The bands nobody touched keep the preset's values.
        assertEquals(2f, settings.currentEq.gainFor(EqBand.HZ_31), 0.001f)
    }

    @Test
    fun `a band out of range is clamped rather than rejected`() = runTest {
        val viewModel = EqualiserViewModel(settings)
        viewModel.state.test { awaitItem() }

        viewModel.setBandGain(EqBand.HZ_62, 40f)
        assertEquals(12f, settings.currentEq.gainFor(EqBand.HZ_62), 0.001f)

        viewModel.setBandGain(EqBand.HZ_62, -40f)
        assertEquals(-12f, settings.currentEq.gainFor(EqBand.HZ_62), 0.001f)
    }

    @Test
    fun `the preamp is headroom, not part of the curve`() = runTest {
        val viewModel = EqualiserViewModel(settings)
        viewModel.state.test { awaitItem() }

        viewModel.selectPreset(EqPreset.BASS)
        viewModel.setPreamp(-6f)

        assertEquals(-6f, settings.currentEq.preampDb, 0.001f)
        assertEquals(
            "pulling headroom out of Bass has not stopped it being Bass",
            EqPreset.BASS,
            settings.currentEq.preset,
        )
    }

    @Test
    fun `reset to flat clears the curve and the preamp but not the switch`() = runTest {
        val viewModel = EqualiserViewModel(settings)
        viewModel.state.test { awaitItem() }

        viewModel.setEnabled(true)
        viewModel.selectPreset(EqPreset.BRIGHT)
        viewModel.resetToFlat()

        assertEquals(EqSettings.FlatGains, settings.currentEq.bandGainsDb)
        assertEquals(0f, settings.currentEq.preampDb, 0.001f)
        assertEquals(EqPreset.FLAT, settings.currentEq.preset)
        assertTrue("the equaliser must stay switched on", settings.currentEq.isEnabled)
    }
}

/** Crossfade, screen 20. */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossfadeViewModelTest {

    private val settings = FakePlaybackSettingsRepository()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `crossfade is off by default with its sub-toggles on`() = runTest {
        val viewModel = CrossfadeViewModel(settings)

        viewModel.state.test {
            val state: CrossfadeSettings = awaitItem()
            assertEquals(CrossfadeDuration.OFF, state.duration)
            assertFalse(state.isEnabled)
            // REQUIREMENTS.md: crossfading a segued album is a bug to most users.
            assertTrue(state.suppressWithinAlbum)
            assertTrue(state.fadeOnSkip)
            assertTrue(state.fadeOnPause)
        }
    }

    @Test
    fun `the four lengths the pack offers all reach the repository`() = runTest {
        val viewModel = CrossfadeViewModel(settings)
        viewModel.state.test { awaitItem() }

        CrossfadeDuration.entries.forEach { duration ->
            viewModel.setDuration(duration)
            assertEquals(duration, settings.currentCrossfade.duration)
        }
    }

    @Test
    fun `turning the length off keeps the sub-toggles as they were`() = runTest {
        val viewModel = CrossfadeViewModel(settings)
        viewModel.state.test { awaitItem() }

        viewModel.setDuration(CrossfadeDuration.SIX_SECONDS)
        viewModel.setSuppressWithinAlbum(false)
        viewModel.setFadeOnPause(false)
        viewModel.setDuration(CrossfadeDuration.OFF)

        assertFalse(settings.currentCrossfade.isEnabled)
        assertFalse(
            "turning crossfade back on must restore what was there",
            settings.currentCrossfade.suppressWithinAlbum,
        )
        assertFalse(settings.currentCrossfade.fadeOnPause)
        assertTrue(settings.currentCrossfade.fadeOnSkip)
    }

    @Test
    fun `the readout says what the slider is at`() {
        assertEquals("Off", durationLabel(CrossfadeDuration.OFF))
        assertEquals("4 s", durationLabel(CrossfadeDuration.FOUR_SECONDS))
        assertEquals("6 s", durationLabel(CrossfadeDuration.SIX_SECONDS))
        assertEquals("12 s", durationLabel(CrossfadeDuration.TWELVE_SECONDS))
    }
}
