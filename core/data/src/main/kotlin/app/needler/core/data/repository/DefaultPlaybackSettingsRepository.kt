package app.needler.core.data.repository

import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.TrackEntity
import app.needler.core.data.mapper.EntityMappers
import app.needler.core.data.mapper.SubsonicIds
import app.needler.core.data.mapper.networkCall
import app.needler.core.data.platform.AppStateStore
import app.needler.core.data.platform.NetworkMonitor
import app.needler.core.data.platform.PersistedQueue
import app.needler.core.data.settings.NeedlerSettingsStore
import app.needler.core.data.settings.StreamQuality
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.EqPreset
import app.needler.core.domain.model.EqSettings
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackPreferences
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.QueueItem
import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.ScrobblePreferences
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.StreamQualityPreference
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.WriteOperation
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.ScrobblePreferencesDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.needler.core.data.settings.CrossfadeSettings as StoredCrossfade
import app.needler.core.data.settings.EqPreset as StoredEqPreset
import app.needler.core.data.settings.EqualiserSettings as StoredEqualiser
import app.needler.core.data.settings.PlaybackSettings as StoredPlayback

/**
 * Player preferences, the persisted crate, and the output target.
 *
 * All device-local: EQ state is never synced, and server-side queue sync via `savePlayQueue` is out
 * of v1 scope.
 *
 * ## This repository does not own the queue
 *
 * It owns the *persisted* crate - the copy that survives a restart. `PlaybackController` owns the
 * live crate, which is the session's own queue and therefore the only one that describes what is
 * playing. Two owners of one queue is not a style question: it is a race whose visible symptom is the
 * crate rearranging itself under the user's finger. That is why there is no enqueue or move here.
 *
 * The persisted crate is stored as **track keys**, not file ids, so a queue restored after a
 * server-side quality upgrade resolves to the new files rather than to rows the server has replaced.
 */
public class DefaultPlaybackSettingsRepository(
    private val settingsStore: NeedlerSettingsStore,
    private val appStateStore: AppStateStore,
    private val trackDao: TrackDao,
    private val writeQueue: WriteQueue,
    private val networkMonitor: NetworkMonitor,
    private val subsonic: SubsonicApi,
    private val v1: V1Api,
) : PlaybackSettingsRepository {

    private val scrobbleTargets: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    private val selectedOutput: MutableStateFlow<OutputTarget> =
        MutableStateFlow(OutputTarget.ThisDevice(displayName = THIS_DEVICE_NAME))

    // ----------------------------------------------------------------- preferences

    override fun observePlaybackPreferences(): Flow<PlaybackPreferences> =
        settingsStore.settings.map { settings ->
            PlaybackPreferences(
                gaplessEnabled = settings.playback.gaplessEnabled,
                crossfade = toDomain(settings.crossfade),
                eq = toDomain(settings.equaliser),
                speed = PlaybackSpeed.Normal,
                sleepTimer = SleepTimer.Off,
                streamQuality = toStreamPreference(settings.playback),
                scrobblingEnabled = settings.playback.scrobbleEnabled,
            )
        }

    override fun observeEqSettings(): Flow<EqSettings> = settingsStore.equaliser.map(::toDomain)

    override fun observeCrossfadeSettings(): Flow<CrossfadeSettings> =
        settingsStore.crossfade.map(::toDomain)

    /**
     * The scrobble toggle plus the destinations the **server** forwards to.
     *
     * The toggle governs whether Needler reports plays at all; where they go is the server's
     * business, so the label comes from these targets rather than hard-coding ListenBrainz.
     */
    override fun observeScrobblePreferences(): Flow<ScrobblePreferences> =
        settingsStore.playback.map { playback: StoredPlayback ->
            ScrobblePreferences(
                reportingEnabled = playback.scrobbleEnabled,
                serverTargets = scrobbleTargets.value,
            )
        }

    override suspend fun setGaplessEnabled(enabled: Boolean) {
        settingsStore.setGaplessEnabled(enabled)
    }

    override suspend fun setEqSettings(settings: EqSettings) {
        settingsStore.setEqualiserEnabled(settings.isEnabled)
        settingsStore.setEqBands(settings.bandGainsDb.map { it.toInt() })
        settingsStore.setEqPreampDb(settings.preampDb.toInt())
    }

    override suspend fun setCrossfadeSettings(settings: CrossfadeSettings) {
        settingsStore.setCrossfadeSeconds(settings.duration.duration.inWholeSeconds.toInt())
        settingsStore.setSkipFadeInsideAlbum(settings.suppressWithinAlbum)
        settingsStore.setFadeOnSkip(settings.fadeOnSkip)
        settingsStore.setFadeOnPause(settings.fadeOnPause)
    }

    /**
     * Playback speed and the sleep timer are **session state, not settings**.
     *
     * They belong to the thing that is playing and die with it: a speed persisted across a restart
     * would have the next album start at 1.5x with no visible cause. Both are accepted here because
     * the domain interface declares them, and both are applied through the controller.
     */
    override suspend fun setPlaybackSpeed(speed: PlaybackSpeed): Unit = Unit

    override suspend fun setSleepTimer(timer: SleepTimer): Unit = Unit

    /**
     * Sets stream quality.
     *
     * [StreamQualityPreference.MP3_320_ON_METERED] must only be offered when the server advertises
     * `transcoding:1` **and** reports transcoding enabled: the server allows one transcode per user
     * and two in total, so it is a scarce resource rather than a free setting. The gate is the
     * caller's, because hiding the control is better than failing the tap.
     */
    override suspend fun setStreamQuality(preference: StreamQualityPreference) {
        when (preference) {
            StreamQualityPreference.ORIGINAL -> settingsStore.setStreamQuality(StreamQuality.ORIGINAL)
            StreamQualityPreference.MP3_320_ON_METERED -> {
                settingsStore.setStreamQuality(StreamQuality.ORIGINAL)
                settingsStore.setMobileDataStreamQuality(StreamQuality.MP3_320)
            }
        }
    }

    override suspend fun setScrobblingEnabled(enabled: Boolean) {
        settingsStore.setScrobbleEnabled(enabled)
    }

    override suspend fun refreshScrobblePreferences(): Outcome<ScrobblePreferences> {
        val call: Outcome<ScrobblePreferencesDto> = networkCall { v1.scrobblePreferences() }
        return when (call) {
            is Outcome.Failure -> call
            is Outcome.Success -> {
                val targets: List<String> = buildList {
                    if (call.value.scrobbleToListenbrainz) add(LISTENBRAINZ)
                    if (call.value.scrobbleToLastfm) add(LASTFM)
                }
                scrobbleTargets.value = targets
                Outcome.Success(
                    ScrobblePreferences(
                        reportingEnabled = settingsStore.playback.first().scrobbleEnabled,
                        serverTargets = targets,
                    ),
                )
            }
        }
    }

    /**
     * Queues a scrobble for submission.
     *
     * Offline it is journalled with its **original timestamp**, which is why the event carries
     * `playedAt` rather than letting the submission use "now": a commute's worth of listening
     * submitted on arrival would otherwise all land in the same minute.
     */
    override suspend fun submitScrobble(event: ScrobbleEvent): Outcome<Unit> {
        if (!settingsStore.playback.first().scrobbleEnabled) return Outcome.Ok
        if (!networkMonitor.current().isOnline) {
            writeQueue.enqueue(WriteOperation.SubmitScrobble(event), supersede = false)
            return Outcome.Ok
        }
        val id: String = resolveTrackId(event) ?: return Outcome.Ok
        val call: Outcome<Unit> = networkCall {
            subsonic.scrobble(
                ids = listOf(id),
                timesMillis = listOf(event.playedAt.toEpochMilliseconds()),
                submission = event.submission,
            )
        }
        return when (call) {
            is Outcome.Success -> Outcome.Ok
            is Outcome.Failure -> if (call.error.isRetryable) {
                writeQueue.enqueue(WriteOperation.SubmitScrobble(event), supersede = false)
                Outcome.Ok
            } else {
                call
            }
        }
    }

    // ----------------------------------------------------------------- the crate

    override fun observePersistedQueue(): Flow<PlayQueue> =
        appStateStore.observePersistedQueue().map { persisted -> resolve(persisted) }

    override suspend fun restorePersistedQueue(): PlayQueue =
        resolve(appStateStore.observePersistedQueue().first())

    /**
     * Persists the crate. Called by the player service and by nothing else.
     *
     * One writer is the whole point; see the class documentation.
     */
    override suspend fun savePersistedQueue(queue: PlayQueue) {
        appStateStore.savePersistedQueue(
            PersistedQueue(
                keys = queue.items.map { EntityMappers.trackKeyDb(it.track.key) },
                currentIndex = queue.currentIndex,
            ),
        )
    }

    // --------------------------------------------------------------------- output

    /**
     * Output targets for the "Play on" picker.
     *
     * **Only this device is enumerated here.** Bluetooth sinks come from `AudioManager`'s device
     * list and Cast receivers from a `MediaRouter` discovery session, and both of those belong to the
     * layer that owns the Media3 session rather than to a repository over Room and HTTP - a
     * repository that opened a route-discovery callback would keep the radio awake from the wrong
     * place entirely. This member is therefore deliberately incomplete and is documented as such in
     * the module's report; the picker is wired when `:player:service` supplies the routes.
     */
    override fun observeOutputTargets(): Flow<List<OutputTarget>> =
        kotlinx.coroutines.flow.flowOf(listOf(OutputTarget.ThisDevice(displayName = THIS_DEVICE_NAME)))

    override fun observeSelectedOutput(): Flow<OutputTarget> = selectedOutput

    override suspend fun selectOutput(target: OutputTarget): Outcome<Unit> {
        if (!target.isSelectable) {
            // A Cast receiver fetches audio itself and cannot reach a VPN-only, self-signed or
            // plain-HTTP server. The picker says so rather than failing after the user picks it.
            return Outcome.Failure(
                app.needler.core.domain.model.NeedlerError.CapabilityUnavailable(
                    "output " + target.displayName + " is not reachable",
                ),
            )
        }
        selectedOutput.value = target
        return Outcome.Ok
    }

    // ------------------------------------------------------------------ internals

    private suspend fun resolve(persisted: PersistedQueue): PlayQueue {
        if (persisted.keys.isEmpty()) return PlayQueue.Empty
        val rows: List<TrackEntity> =
            trackDao.getTracksByCanonicalKeys(persisted.keys.map { it.canonical })
        val byKey: Map<String, TrackEntity> = rows.associateBy { row ->
            row.releaseGroupMbid + "/" + row.discNo + "/" + row.trackNo
        }
        val items: List<QueueItem> = persisted.keys.mapNotNull { key ->
            val row: TrackEntity = byKey[key.canonical] ?: return@mapNotNull null
            val track: Track = EntityMappers.track(row)
            QueueItem(
                id = key.canonical,
                track = track,
                source = app.needler.core.domain.model.QueueItemSource.RESTORED,
            )
        }
        // The index is clamped rather than trusted: a track can have left the library between the
        // save and the restore, and a stale index would resume on the wrong song.
        val index: Int? = persisted.currentIndex?.coerceIn(0, maxOf(items.size - 1, 0))
        return PlayQueue(items = items, currentIndex = index?.takeIf { items.isNotEmpty() })
    }

    private suspend fun resolveTrackId(event: ScrobbleEvent): String? {
        val row: TrackEntity = trackDao.getTrack(
            releaseGroupMbid = event.trackKey.releaseGroupMbid.value,
            discNo = event.trackKey.discNumber,
            trackNo = event.trackKey.trackNumber,
        ) ?: return event.fetchHandle.subsonicTrackId.takeIf {
            event.fetchHandle.fileId.value != EntityMappers.MISSING_FILE_ID
        }
        if (!EntityMappers.hasPlayableFile(row)) return null
        return SubsonicIds.trackId(row.fileId!!)
    }

    private fun toDomain(settings: StoredCrossfade): CrossfadeSettings = CrossfadeSettings(
        duration = CrossfadeDuration.entries
            .firstOrNull { it.duration.inWholeSeconds.toInt() == settings.seconds }
            ?: CrossfadeDuration.OFF,
        suppressWithinAlbum = settings.skipFadeInsideAlbum,
        fadeOnSkip = settings.fadeOnSkip,
        fadeOnPause = settings.fadeOnPause,
    )

    private fun toDomain(settings: StoredEqualiser): EqSettings = EqSettings(
        isEnabled = settings.enabled,
        bandGainsDb = settings.bandGainsDb.map { it.toFloat() },
        preampDb = settings.preampDb.toFloat(),
        preset = when (settings.preset) {
            StoredEqPreset.FLAT -> EqPreset.FLAT
            StoredEqPreset.BASS -> EqPreset.BASS
            StoredEqPreset.VOCAL -> EqPreset.VOCAL
            StoredEqPreset.BRIGHT -> EqPreset.BRIGHT
            StoredEqPreset.VINYL -> EqPreset.VINYL
            StoredEqPreset.CUSTOM -> EqPreset.CUSTOM
        },
    )

    /**
     * The settings store keeps two quality values - one for any connection and one for mobile data -
     * while the domain models the single choice the screen actually offers. `MP3_320_ON_METERED` is
     * "original, except transcode on metered", which is exactly that pair.
     */
    private fun toStreamPreference(playback: StoredPlayback): StreamQualityPreference =
        if (playback.streamQuality == StreamQuality.ORIGINAL &&
            playback.mobileDataStreamQuality.isTranscode
        ) {
            StreamQualityPreference.MP3_320_ON_METERED
        } else {
            StreamQualityPreference.ORIGINAL
        }

    public companion object {
        public const val THIS_DEVICE_NAME: String = "This device"
        private const val LISTENBRAINZ: String = "ListenBrainz"
        private const val LASTFM: String = "Last.fm"
    }
}
