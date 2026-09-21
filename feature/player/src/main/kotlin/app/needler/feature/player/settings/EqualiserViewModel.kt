package app.needler.feature.player.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.EqBand
import app.needler.core.domain.model.EqPreset
import app.needler.core.domain.model.EqSettings
import app.needler.core.domain.repository.PlaybackSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The equaliser, over [PlaybackSettingsRepository].
 *
 * EQ is device-local and never synced - that is stated on the screen and in the model - so there is
 * one writer and no reconciliation to do: every edit is a whole [EqSettings] handed to the
 * repository, which both the UI and the player service then observe. The audio processor chain that
 * acts on it lives in `:player:service`; nothing here knows it exists.
 */
@HiltViewModel
class EqualiserViewModel @Inject constructor(
    private val settings: PlaybackSettingsRepository,
) : ViewModel() {

    val state: StateFlow<EqSettings> = settings.observeEqSettings().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = EqSettings.Default,
    )

    fun setEnabled(enabled: Boolean) = write(state.value.copy(isEnabled = enabled))

    /** A pill: the preset's curve and its preamp together, leaving the on/off switch alone. */
    fun selectPreset(preset: EqPreset) = write(EqPresets.apply(preset, state.value))

    /**
     * One band moved by hand.
     *
     * The preset becomes [EqPreset.CUSTOM] the moment a slider moves, because the curve is no longer
     * the one the pill names and leaving "Vinyl" lit would be a lie about what is being applied.
     */
    fun setBandGain(band: EqBand, gainDb: Float) {
        val current: EqSettings = state.value
        val gains: List<Float> = current.bandGainsDb.toMutableList().apply {
            this[band.ordinal] = gainDb.coerceIn(EqSettings.GAIN_RANGE_DB)
        }
        write(current.copy(bandGainsDb = gains, preset = EqPreset.CUSTOM))
    }

    /**
     * The preamp.
     *
     * Moving it does *not* make the preset custom: the preamp is the headroom control, not part of
     * the curve, and pulling 3 dB out of a clipping Bass preset has not stopped it being Bass.
     */
    fun setPreamp(preampDb: Float) =
        write(state.value.copy(preampDb = preampDb.coerceIn(EqSettings.GAIN_RANGE_DB)))

    /** Reset to flat: the curve and the preamp, with the equaliser left switched as it was. */
    fun resetToFlat() = write(EqPresets.apply(EqPreset.FLAT, state.value))

    private fun write(next: EqSettings) {
        viewModelScope.launch { settings.setEqSettings(next) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}
