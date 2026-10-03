package app.needler.feature.player.output

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.playback.OutputRouter
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
 * The "Play on" picker, over two dependencies that answer two different questions.
 *
 * [OutputRouter] says what exists and where sound is going; it is implemented by `:player:service`,
 * because REQUIREMENTS.md "Output" puts route discovery in the layer that owns the Media3 session - "a
 * repository over Room and HTTP that opened a route-discovery callback would be keeping the radio awake
 * from the wrong place entirely". [PlaybackSettingsRepository] remembers the choice, which is device
 * state and genuinely is a repository's job.
 *
 * ## Which row is ticked, and why it is not simply the remembered one
 *
 * The live route, falling back to the remembered selection only when the platform has not said. Those
 * are different facts and the difference is visible: a connected Bluetooth speaker is not necessarily
 * the one receiving audio, so ticking the remembered row would put the tick on a speaker that is silent
 * while the one actually playing sits unticked two rows down. REQUIREMENTS.md "Output" asks that a user
 * "never wonders where sound is going", and a picker that marks the wrong row is worse at that than one
 * that marks none.
 *
 * ## The subscription is the sheet's
 *
 * `WhileSubscribed` here is not only about recomposition cost. [OutputRouter.observeOutputTargets] is a
 * cold flow whose collection is what registers the platform's device callback, so this `stateIn` is
 * where that callback's lifetime is decided: it begins when the sheet is first collected and ends five
 * seconds after it is dismissed. Nothing is held behind a backgrounded app, which is what
 * REQUIREMENTS.md "Battery and data" asks for.
 */
@HiltViewModel
class OutputViewModel @Inject constructor(
    private val router: OutputRouter,
    private val settings: PlaybackSettingsRepository,
) : ViewModel() {

    private val failure = MutableStateFlow<NeedlerError?>(null)

    val state: StateFlow<OutputUiState> = combine(
        router.observeOutputTargets(),
        router.observeActiveOutput(),
        settings.observeSelectedOutput(),
        failure,
    ) { targets, active, remembered, error ->
        OutputUiState(
            targets = targets,
            selected = active ?: remembered,
            // Nothing at all, not even this device, means the list has not arrived yet rather than
            // that the phone has no speaker. The router always emits this device once it has read
            // anything, so an empty list only ever describes the gap before the first reading.
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
     *
     * ## What picking a Bluetooth row can and cannot do
     *
     * It records the choice, and the record is all there is. Android gives an app no way to move
     * **media** audio to a named A2DP sink: `setCommunicationDevice` applies to call audio only, and
     * moving the media route is the system output switcher's privilege. So a tap here is remembered,
     * and the tick follows the live route reported by [OutputRouter.observeActiveOutput] rather than
     * this write - which is why the two are separate facts in [OutputUiState] and why a tap on a row
     * that is already playing elsewhere does not appear to do anything. That gap is in the module
     * report rather than hidden behind a spinner.
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
