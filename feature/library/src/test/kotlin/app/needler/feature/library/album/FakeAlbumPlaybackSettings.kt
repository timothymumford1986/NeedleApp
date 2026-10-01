package app.needler.feature.library.album

import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.EqSettings
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackPreferences
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.ScrobblePreferences
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.StreamOverrideScope
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.repository.PlaybackSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The preference store, as the album screen reads it: two mode rungs and the per-album override.
 *
 * Deliberately narrow. Only the members the album screen touches do anything; the rest fail loudly,
 * so a fake that starts being asked for something new says so rather than answering plausibly.
 *
 * It lives beside the album tests rather than in the module's shared `Fakes.kt` because the album
 * screen is the only screen in `:feature:library` that reads playback preferences at all.
 */
internal class FakeAlbumPlaybackSettings(
    preferences: PlaybackPreferences = PlaybackPreferences(),
) : PlaybackSettingsRepository {

    val preferencesFlow: MutableStateFlow<PlaybackPreferences> = MutableStateFlow(preferences)

    /** Overrides, keyed the way the real table's composite primary key is. */
    val overrides: MutableStateFlow<Map<Pair<StreamOverrideScope, String>, StreamRung>> =
        MutableStateFlow(emptyMap())

    override fun observePlaybackPreferences(): Flow<PlaybackPreferences> = preferencesFlow

    override fun observeEqSettings(): Flow<EqSettings> = error("not used by the album screen")

    override fun observeCrossfadeSettings(): Flow<CrossfadeSettings> =
        error("not used by the album screen")

    override fun observeScrobblePreferences(): Flow<ScrobblePreferences> =
        error("not used by the album screen")

    override suspend fun setGaplessEnabled(enabled: Boolean) = error("not used by the album screen")

    override suspend fun setEqSettings(settings: EqSettings) = error("not used by the album screen")

    override suspend fun setCrossfadeSettings(settings: CrossfadeSettings) =
        error("not used by the album screen")

    override suspend fun setPlaybackSpeed(speed: PlaybackSpeed) =
        error("not used by the album screen")

    override suspend fun setSleepTimer(timer: SleepTimer) = error("not used by the album screen")

    override suspend fun setWifiStreamRung(rung: StreamRung) {
        preferencesFlow.value = preferencesFlow.value.copy(wifiQuality = rung)
    }

    override suspend fun setDataStreamRung(rung: StreamRung) {
        preferencesFlow.value = preferencesFlow.value.copy(dataQuality = rung)
    }

    override suspend fun getStreamOverride(scope: StreamOverrideScope, id: String): StreamRung? =
        overrides.value[scope to id]

    override fun observeStreamOverride(
        scope: StreamOverrideScope,
        id: String,
    ): Flow<StreamRung?> = overrides.map { it[scope to id] }

    override suspend fun setStreamOverride(
        scope: StreamOverrideScope,
        id: String,
        rung: StreamRung,
    ) {
        overrides.value = overrides.value + ((scope to id) to rung)
    }

    override suspend fun clearStreamOverride(scope: StreamOverrideScope, id: String) {
        overrides.value = overrides.value - (scope to id)
    }

    override suspend fun setScrobblingEnabled(enabled: Boolean) =
        error("not used by the album screen")

    override suspend fun refreshScrobblePreferences(): Outcome<ScrobblePreferences> =
        error("not used by the album screen")

    override suspend fun submitScrobble(event: ScrobbleEvent): Outcome<Unit> =
        error("not used by the album screen")

    override fun observePersistedQueue(): Flow<PlayQueue> = error("not used by the album screen")

    override suspend fun restorePersistedQueue(): PlayQueue = error("not used by the album screen")

    override suspend fun savePersistedQueue(queue: PlayQueue) =
        error("not used by the album screen")

    override fun observeOutputTargets(): Flow<List<OutputTarget>> =
        error("not used by the album screen")

    override fun observeSelectedOutput(): Flow<OutputTarget> = error("not used by the album screen")

    override suspend fun selectOutput(target: OutputTarget): Outcome<Unit> =
        error("not used by the album screen")
}
