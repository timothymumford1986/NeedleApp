package app.needler.feature.player.output

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.repository.PlaybackSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The "Play on" picker, over [PlaybackSettingsRepository].
 *
 * The picker is the repository's half of output, not the controller's, and the split is on purpose:
 * listing targets, probing Cast reachability and remembering the choice are the repository's, while
 * `PlaybackState.output` reports where the session is actually routing sound - which is not always
 * where the user last pointed it, because a speaker can drop out underneath a running track.
 *
 * So this screen shows [PlaybackSettingsRepository.observeSelectedOutput] as the chosen row, and the
 * player surfaces name `PlaybackState.output` as where sound is going. When a target vanishes those
 * two disagree, and both are telling the truth.
 */
@HiltViewModel
class OutputViewModel @Inject constructor(
    private val settings: PlaybackSettingsRepository,
) : ViewModel() {

    private val failure = MutableStateFlow<app.needler.core.domain.model.NeedlerError?>(null)

    val state: StateFlow<OutputUiState> = combine(
        settings.observeOutputTargets(),
        settings.observeSelectedOutput(),
        failure,
    ) { targets, selected, error ->
        OutputUiState(
            targets = targets,
            selected = selected,
            // Nothing at all, not even this device, means the list has not arrived yet rather than
            // that the phone has no speaker.
            discovering = targets.isEmpty(),
            error = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = OutputUiState(discovering = true),
    )

    /**
     * Picks a target.
     *
     * An unselectable one is refused here as well as being disabled in the list: the row can be
     * tapped through an accessibility action that does not respect the disabled state, and switching
     * to a Cast receiver that cannot reach the server is the exact failure the probe exists to
     * prevent.
     */
    fun select(target: OutputTarget) {
        if (!target.isSelectable) return
        failure.value = null
        viewModelScope.launch {
            val outcome: Outcome<Unit> = settings.selectOutput(target)
            if (outcome is Outcome.Failure) failure.value = outcome.error
        }
    }

    /**
     * Volume.
     *
     * A no-op, deliberately and visibly: `PlaybackController` exposes no volume command and
     * `PlaybackState` carries no level, so there is nothing to set. [OutputUiState.volume] stays null
     * and the slider is not drawn, rather than drawing a control that silently does nothing. When
     * the controller grows `setVolume`, this method and that field are what change.
     */
    fun setVolume(@Suppress("UNUSED_PARAMETER") level: Float) = Unit

    private companion object {
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}
