package app.needler.feature.player.fake

import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.EqSettings
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackPreferences
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.ScrobblePreferences
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.StreamQualityPreference
import app.needler.core.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [PlaybackSettingsRepository] for the three settings screens.
 *
 * Every setter writes straight through to the flow the screen is bound to, which is how the real
 * one behaves and what makes "move a slider, see the value arrive back" a meaningful assertion.
 *
 * [selectOutputResult] is the seam the output picker's failure path needs: switching output is the
 * one action on these screens that can fail, and REQUIREMENTS.md cares that the failure is explained
 * rather than swallowed.
 */
class FakePlaybackSettingsRepository(
    eq: EqSettings = EqSettings.Default,
    crossfade: CrossfadeSettings = CrossfadeSettings(),
    targets: List<OutputTarget> = emptyList(),
    selected: OutputTarget = OutputTarget.ThisDevice("This phone"),
    queue: PlayQueue = PlayQueue.Empty,
) : PlaybackSettingsRepository {

    private val eqFlow = MutableStateFlow(eq)
    private val crossfadeFlow = MutableStateFlow(crossfade)
    private val targetsFlow = MutableStateFlow(targets)
    private val selectedFlow = MutableStateFlow(selected)
    private val queueFlow = MutableStateFlow(queue)
    private val preferencesFlow = MutableStateFlow(PlaybackPreferences())
    private val scrobbleFlow = MutableStateFlow(ScrobblePreferences(reportingEnabled = true))

    /** What [selectOutput] returns. Set it to a failure to exercise the picker's error line. */
    var selectOutputResult: Outcome<Unit> = Outcome.Ok

    /** Every output the picker asked for, in order. */
    val selectedOutputs: MutableList<String> = mutableListOf()

    override fun observePlaybackPreferences(): Flow<PlaybackPreferences> =
        preferencesFlow.asStateFlow()

    override fun observeEqSettings(): Flow<EqSettings> = eqFlow.asStateFlow()

    override fun observeCrossfadeSettings(): Flow<CrossfadeSettings> = crossfadeFlow.asStateFlow()

    override fun observeScrobblePreferences(): Flow<ScrobblePreferences> = scrobbleFlow.asStateFlow()

    override suspend fun setGaplessEnabled(enabled: Boolean) {
        preferencesFlow.value = preferencesFlow.value.copy(gaplessEnabled = enabled)
    }

    override suspend fun setEqSettings(settings: EqSettings) {
        eqFlow.value = settings
        preferencesFlow.value = preferencesFlow.value.copy(eq = settings)
    }

    override suspend fun setCrossfadeSettings(settings: CrossfadeSettings) {
        crossfadeFlow.value = settings
        preferencesFlow.value = preferencesFlow.value.copy(crossfade = settings)
    }

    override suspend fun setPlaybackSpeed(speed: PlaybackSpeed) {
        preferencesFlow.value = preferencesFlow.value.copy(speed = speed)
    }

    override suspend fun setSleepTimer(timer: SleepTimer) {
        preferencesFlow.value = preferencesFlow.value.copy(sleepTimer = timer)
    }

    override suspend fun setStreamQuality(preference: StreamQualityPreference) {
        preferencesFlow.value = preferencesFlow.value.copy(streamQuality = preference)
    }

    override suspend fun setScrobblingEnabled(enabled: Boolean) {
        scrobbleFlow.value = scrobbleFlow.value.copy(reportingEnabled = enabled)
    }

    override suspend fun refreshScrobblePreferences(): Outcome<ScrobblePreferences> =
        Outcome.Success(scrobbleFlow.value)

    override suspend fun submitScrobble(event: ScrobbleEvent): Outcome<Unit> = Outcome.Ok

    override fun observePersistedQueue(): Flow<PlayQueue> = queueFlow.asStateFlow()

    override suspend fun restorePersistedQueue(): PlayQueue = queueFlow.value

    override suspend fun savePersistedQueue(queue: PlayQueue) {
        queueFlow.value = queue
    }

    override fun observeOutputTargets(): Flow<List<OutputTarget>> = targetsFlow.asStateFlow()

    override fun observeSelectedOutput(): Flow<OutputTarget> = selectedFlow.asStateFlow()

    override suspend fun selectOutput(target: OutputTarget): Outcome<Unit> {
        selectedOutputs += target.id
        val result: Outcome<Unit> = selectOutputResult
        if (result is Outcome.Success) selectedFlow.value = target
        return result
    }

    // ---- what a test drives from the other side ----------------------------

    fun emitTargets(targets: List<OutputTarget>) {
        targetsFlow.value = targets
    }

    /** A convenience for asserting on the current eq without collecting the flow. */
    val currentEq: EqSettings get() = eqFlow.value

    /** The same for crossfade. */
    val currentCrossfade: CrossfadeSettings get() = crossfadeFlow.value

    /** The selected output's name, as a flow, for tests that want to watch it change. */
    fun observeSelectedName(): Flow<String> = selectedFlow.map { it.displayName }

    /** A failure the picker has to explain, for the error-path test. */
    companion object {
        val OutputFailure: Outcome<Unit> =
            Outcome.Failure(NeedlerError.Offline(app.needler.core.domain.model.OfflineCause.TIMEOUT))
    }
}
