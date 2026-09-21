package app.needler.feature.player.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.repository.PlaybackSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Crossfade, over [PlaybackSettingsRepository].
 *
 * The three sub-toggles keep their values when the length goes to Off - the settings object carries
 * them whatever the duration is - so turning crossfade back on restores the arrangement the listener
 * had rather than the defaults. That is why "off" is a duration here and not a fourth boolean:
 * `CrossfadeSettings.isEnabled` is derived from the length, and there is one thing to remember.
 */
@HiltViewModel
class CrossfadeViewModel @Inject constructor(
    private val settings: PlaybackSettingsRepository,
) : ViewModel() {

    val state: StateFlow<CrossfadeSettings> = settings.observeCrossfadeSettings().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = CrossfadeSettings(),
    )

    fun setDuration(duration: CrossfadeDuration) = write(state.value.copy(duration = duration))

    fun setSuppressWithinAlbum(enabled: Boolean) =
        write(state.value.copy(suppressWithinAlbum = enabled))

    fun setFadeOnSkip(enabled: Boolean) = write(state.value.copy(fadeOnSkip = enabled))

    fun setFadeOnPause(enabled: Boolean) = write(state.value.copy(fadeOnPause = enabled))

    private fun write(next: CrossfadeSettings) {
        viewModelScope.launch { settings.setCrossfadeSettings(next) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}
