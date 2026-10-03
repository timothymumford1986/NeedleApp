package app.needler.feature.player.output

import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.playback.OutputRouter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * An [OutputRouter] with no `AudioManager` behind it.
 *
 * The reason it exists is the reason the interface exists: a test must not depend on what is paired with
 * the machine running it. The repository has precedent for exactly this - `WearAudioStore` measured the
 * real disk and its tests tracked how full the host drive was until a `WearFreeSpace` port went in front
 * of it - and a device list is the same hazard with a louder symptom, since the picker's assertions would
 * pass at a desk with nothing paired and fail at one with headphones on.
 *
 * Two flows, scriptable independently, because the picker depends on them disagreeing: the list is what
 * exists and [activeFlow] is where sound is actually going, and a speaker that drops out underneath a
 * running track makes those two different answers that are both true.
 */
class FakeOutputRouter(
    targets: List<OutputTarget> = emptyList(),
    active: OutputTarget? = null,
) : OutputRouter {

    private val targetsFlow = MutableStateFlow(targets)
    private val activeFlow = MutableStateFlow(active)

    override fun observeOutputTargets(): Flow<List<OutputTarget>> = targetsFlow.asStateFlow()

    override fun observeActiveOutput(): Flow<OutputTarget?> = activeFlow.asStateFlow()

    /** A device arriving or leaving mid-session. */
    fun emitTargets(targets: List<OutputTarget>) {
        targetsFlow.value = targets
    }

    /** The platform moving the route, with or without the list changing. */
    fun emitActive(target: OutputTarget?) {
        activeFlow.value = target
    }
}
