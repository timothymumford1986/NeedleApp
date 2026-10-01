package app.needler.core.data.repository

import app.needler.core.data.local.dao.StreamOverrideDao
import app.needler.core.data.local.dao.TrackDao
import app.needler.core.data.local.entity.StreamOverrideEntity
import app.needler.core.data.local.entity.StreamOverrideScopeDb
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
import app.needler.core.domain.model.StreamOverrideScope
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.WriteOperation
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.core.network.subsonic.SubsonicApi
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.ScrobblePreferencesDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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
 *
 * ## Two stores, because there are two kinds of quality preference
 *
 * The **mode rungs** - one for Wi-Fi, one for mobile data - are settings, and live in `DataStore`
 * beside gapless and crossfade. The **per-item overrides** are a row per track or album, so they live
 * in Room: `DataStore` holds settings, not a table. Both are read here so that nothing else has to
 * know there are two places, and `ResolvePlayableSourceUseCase` sees one repository.
 */
public class DefaultPlaybackSettingsRepository(
    private val settingsStore: NeedlerSettingsStore,
    private val appStateStore: AppStateStore,
    private val trackDao: TrackDao,
    private val streamOverrideDao: StreamOverrideDao,
    private val writeQueue: WriteQueue,
    private val networkMonitor: NetworkMonitor,
    private val subsonic: SubsonicApi,
    private val v1: V1Api,
    private val now: () -> Long = { System.currentTimeMillis() },
) : PlaybackSettingsRepository {

    private val scrobbleTargets: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    private val selectedOutput: MutableStateFlow<OutputTarget> =
        MutableStateFlow(OutputTarget.ThisDevice(displayName = THIS_DEVICE_NAME))

    /**
     * Playback speed and the sleep timer: session state, held in memory and never written to
     * `DataStore`.
     *
     * See [setSleepTimer] for why they are not settings. They are `StateFlow`s rather than fields
     * because [observePlaybackPreferences] is what the player service collects, and the service is the
     * thing that has to act on them - a field nothing can observe is how the sleep timer came to be
     * unreachable. One process holds both: the `MediaLibraryService` runs in the app's process and this
     * repository is a singleton, so the UI's write and the service's read are the same object.
     */
    private val sessionSpeed: MutableStateFlow<PlaybackSpeed> = MutableStateFlow(PlaybackSpeed.Normal)
    private val sessionSleepTimer: MutableStateFlow<SleepTimer> = MutableStateFlow(SleepTimer.Off)

    // ----------------------------------------------------------------- preferences

    /**
     * The persisted settings and the two session values, as one observable.
     *
     * The player service collects this and nothing else, so the sleep timer has to arrive here or it
     * arrives nowhere: `PlaybackCoordinator` reads `sleepTimer` off each emission and evaluates it on
     * its own position tick. That is why the timer is combined in rather than read from a field.
     */
    override fun observePlaybackPreferences(): Flow<PlaybackPreferences> = combine(
        settingsStore.settings,
        sessionSpeed,
        sessionSleepTimer,
    ) { settings, speed, timer ->
        PlaybackPreferences(
            gaplessEnabled = settings.playback.gaplessEnabled,
            crossfade = toDomain(settings.crossfade),
            eq = toDomain(settings.equaliser),
            speed = speed,
            sleepTimer = timer,
            wifiQuality = toRung(settings.playback.streamQuality),
            dataQuality = toRung(settings.playback.mobileDataStreamQuality),
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
    override fun observeScrobblePreferences(): Flow<ScrobblePreferences> = combine(
        settingsStore.playback,
        scrobbleTargets,
    ) { playback: StoredPlayback, targets: List<String> ->
        ScrobblePreferences(
            reportingEnabled = playback.scrobbleEnabled,
            // The destinations come from the server, so a refresh that learns about Last.fm must
            // re-emit here rather than waiting for an unrelated settings change.
            serverTargets = targets,
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
     * They belong to the thing that is playing and die with it, so neither reaches `DataStore`. A speed
     * persisted across a restart would have the next album start at 1.5x with no visible cause; a timer
     * persisted overnight is armed and already elapsed by morning, so the first track of the day stops
     * itself, which reads as the app refusing to play. `PlaybackCoordinator` carries a guard against
     * exactly that, and it is a guard against a persistence this repository must not add.
     *
     * Both are therefore held in [sessionSpeed] and [sessionSleepTimer] - in memory, for the life of
     * the process - and both are published on [observePlaybackPreferences]. **That publication is the
     * point.** These two setters were `= Unit` for a while, on the reasoning that the values were
     * "applied through the controller" and so needed no store. Half of that was true: speed does reach
     * the session directly, through `setPlaybackSpeed` on the Media3 controller. The sleep timer cannot,
     * because nothing in the session implements one - the timer is evaluated by the player service
     * against the flow this repository emits, and a no-op setter meant that flow never changed. The
     * result was a timer that was modelled, decided, tested and acted upon, and impossible to turn on.
     *
     * A write here also keeps the emitted speed honest for the coordinator, which re-applies
     * `preferences.speed` to the player on every emission: with a hard-coded `Normal` on this flow, any
     * unrelated settings change - arming the sleep timer among them - would quietly drop playback back
     * to 1x.
     */
    override suspend fun setPlaybackSpeed(speed: PlaybackSpeed) {
        sessionSpeed.value = speed
    }

    override suspend fun setSleepTimer(timer: SleepTimer) {
        sessionSleepTimer.value = timer
    }

    /**
     * Sets the unmetered ceiling.
     *
     * ## One key, one write - which is what the old shape could not do
     *
     * This replaced a `setStreamQuality(StreamQualityPreference)` that took a two-valued enum
     * meaning "original" or "original on Wi-Fi, MP3 320 on mobile data", and it was **write-only in
     * one direction**: writing `ORIGINAL` set the unmetered key and left `mobile_data_stream_quality`
     * at its `MP3_320` default, which this repository then read straight back as
     * `MP3_320_ON_METERED`. The Settings screen carried a documented workaround - a second write
     * through `NeedlerSettingsStore` - to make its own switch turn off. Two keys behind one setter is
     * what caused that, so there are two setters now and each writes exactly the key it names.
     *
     * Any rung but [StreamRung.ORIGINAL] must only be offered when the server advertises
     * `transcoding:1` **and** reports transcoding enabled: the server allows one transcode per user
     * and two in total, so it is a scarce resource rather than a free setting. The gate is the
     * caller's, because hiding the control is better than failing the tap.
     */
    override suspend fun setWifiStreamRung(rung: StreamRung) {
        settingsStore.setStreamQuality(toStored(rung))
    }

    override suspend fun setDataStreamRung(rung: StreamRung) {
        settingsStore.setMobileDataStreamQuality(toStored(rung))
    }

    // ------------------------------------------------------------- per-item overrides

    override suspend fun getStreamOverride(scope: StreamOverrideScope, id: String): StreamRung? =
        streamOverrideDao.get(scope = toDb(scope), itemId = id)?.let { row -> toRung(row.rung) }

    override fun observeStreamOverride(
        scope: StreamOverrideScope,
        id: String,
    ): Flow<StreamRung?> = streamOverrideDao
        .observe(scope = toDb(scope), itemId = id)
        .map { row -> row?.let { toRung(it.rung) } }

    override suspend fun setStreamOverride(
        scope: StreamOverrideScope,
        id: String,
        rung: StreamRung,
    ) {
        streamOverrideDao.upsert(
            StreamOverrideEntity(
                scope = toDb(scope),
                itemId = id,
                rung = toStored(rung),
                updatedAt = now(),
            ),
        )
    }

    override suspend fun clearStreamOverride(scope: StreamOverrideScope, id: String) {
        streamOverrideDao.delete(scope = toDb(scope), itemId = id)
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
     * A stored rung as the domain's rung. Exhaustive, so a rung added to one ladder and forgotten in
     * the other fails to compile rather than silently resolving to Original.
     *
     * ## Why no migration was needed, and what "the migration" actually is
     *
     * The store has always held **two** keys - `stream_quality` and `mobile_data_stream_quality` -
     * with defaults `original` and `mp3_320`. What changed is that the domain used to collapse them
     * into one two-valued preference and now carries both. So an existing install lands where it
     * should by reading the keys it already has:
     *
     * | Install | `stream_quality` | `mobile_data_stream_quality` | Reads as |
     * | --- | --- | --- | --- |
     * | Toggle was **on** | `original` (or absent) | `mp3_320` (or absent) | Wi-Fi Original, Data MP3 320 |
     * | Toggle was **off** | `original` (or absent) | `original` | Wi-Fi Original, Data Original |
     * | Fresh install | absent | absent | Wi-Fi Original, Data MP3 320 |
     *
     * The off case is the one that could have gone wrong, and it is why the old Settings screen wrote
     * `mobile_data_stream_quality = original` explicitly when the switch was turned off: that write is
     * what makes "off" distinguishable from "never touched". `PlaybackSettingsRepositoryTest` pins
     * both directions.
     *
     * No rung value is rewritten and no key is renamed, so there is nothing to run once and nothing
     * that can half-run.
     */
    private fun toRung(stored: StreamQuality): StreamRung = when (stored) {
        StreamQuality.ORIGINAL -> StreamRung.ORIGINAL
        StreamQuality.OPUS_192 -> StreamRung.OPUS_192
        StreamQuality.OPUS_128 -> StreamRung.OPUS_128
        StreamQuality.OPUS_96 -> StreamRung.OPUS_96
        StreamQuality.MP3_320 -> StreamRung.MP3_320
        StreamQuality.MP3_256 -> StreamRung.MP3_256
        StreamQuality.MP3_192 -> StreamRung.MP3_192
        StreamQuality.MP3_128 -> StreamRung.MP3_128
    }

    /** The same mapping the other way, for a write. Exhaustive for the same reason. */
    private fun toStored(rung: StreamRung): StreamQuality = when (rung) {
        StreamRung.ORIGINAL -> StreamQuality.ORIGINAL
        StreamRung.OPUS_192 -> StreamQuality.OPUS_192
        StreamRung.OPUS_128 -> StreamQuality.OPUS_128
        StreamRung.OPUS_96 -> StreamQuality.OPUS_96
        StreamRung.MP3_320 -> StreamQuality.MP3_320
        StreamRung.MP3_256 -> StreamQuality.MP3_256
        StreamRung.MP3_192 -> StreamQuality.MP3_192
        StreamRung.MP3_128 -> StreamQuality.MP3_128
    }

    private fun toDb(scope: StreamOverrideScope): StreamOverrideScopeDb = when (scope) {
        StreamOverrideScope.TRACK -> StreamOverrideScopeDb.TRACK
        StreamOverrideScope.ALBUM -> StreamOverrideScopeDb.ALBUM
    }

    public companion object {
        public const val THIS_DEVICE_NAME: String = "This device"
        private const val LISTENBRAINZ: String = "ListenBrainz"
        private const val LASTFM: String = "Last.fm"
    }
}
