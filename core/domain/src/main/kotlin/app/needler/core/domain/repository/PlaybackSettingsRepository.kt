package app.needler.core.domain.repository

import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.EqSettings
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.OutputTarget
import app.needler.core.domain.model.PlayQueue
import app.needler.core.domain.model.PlaybackPreferences
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.ScrobbleEvent
import app.needler.core.domain.model.ScrobblePreferences
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.StreamOverrideScope
import app.needler.core.domain.model.StreamRung
import kotlinx.coroutines.flow.Flow

/**
 * Player preferences, the persisted crate, and the output target.
 *
 * All of it is device-local: EQ state is stored per device and never synced, and server-side queue sync
 * via `savePlayQueue` is out of v1 scope. The crate is persisted here rather than in its own
 * repository because it is device state with the same lifetime and the same single writer - the
 * player service.
 *
 * ## This repository does not own the queue
 *
 * It owns the *persisted* crate: the copy that survives a restart.
 * [app.needler.core.domain.playback.PlaybackController] owns the *live* crate, which is the session's
 * own queue and therefore the only one that describes what is playing. Restoring reads from here;
 * every edit goes through the controller and comes back as a [savePersistedQueue]. Two owners of one
 * queue is not a style question - it is a race with a visible symptom, the crate rearranging itself
 * under the user's finger.
 *
 * The output target splits the same way and for the same reason. The picker is here - listing
 * targets, probing Cast reachability, remembering the choice - while
 * [app.needler.core.domain.playback.PlaybackState.output] reports where the session is actually
 * routing sound, which is not always where the user last pointed it.
 */
public interface PlaybackSettingsRepository {

    /** Everything on the playback half of settings, as one value so nothing can disagree. */
    public fun observePlaybackPreferences(): Flow<PlaybackPreferences>

    public fun observeEqSettings(): Flow<EqSettings>

    public fun observeCrossfadeSettings(): Flow<CrossfadeSettings>

    /**
     * The scrobble toggle plus the destinations the *server* is configured to forward to, read from
     * `GET /api/v1/me/scrobble-preferences`.
     *
     * The toggle governs whether Needler reports plays at all; where they go is the server's business,
     * so the label must come from these targets rather than hard-coding ListenBrainz.
     */
    public fun observeScrobblePreferences(): Flow<ScrobblePreferences>

    public suspend fun setGaplessEnabled(enabled: Boolean)

    public suspend fun setEqSettings(settings: EqSettings)

    public suspend fun setCrossfadeSettings(settings: CrossfadeSettings)

    public suspend fun setPlaybackSpeed(speed: PlaybackSpeed)

    public suspend fun setSleepTimer(timer: SleepTimer)

    /**
     * The ceiling for streaming on an unmetered connection.
     *
     * Any rung but [StreamRung.ORIGINAL] must only be offered when
     * [app.needler.core.domain.model.ServerCapabilities.transcodingAvailable] is true; the server caps
     * transcoding at one per user and two in total, so it is a scarce resource rather than a free
     * setting. The gate is the caller's, because hiding the control is better than failing the tap.
     */
    public suspend fun setWifiStreamRung(rung: StreamRung)

    /** The same ceiling for a metered connection. Defaults to [StreamRung.MP3_320]. */
    public suspend fun setDataStreamRung(rung: StreamRung)

    /**
     * The rung pinned to one track or album, or null when it has none.
     *
     * A one-shot read, for the resolver: it runs once per track on the streaming path only, and a
     * `Flow` there would mean a subscription per play.
     *
     * @param id [app.needler.core.domain.model.TrackKey.canonicalString] for
     *   [StreamOverrideScope.TRACK], the release-group MBID for [StreamOverrideScope.ALBUM].
     */
    public suspend fun getStreamOverride(scope: StreamOverrideScope, id: String): StreamRung?

    /** The same value, observed, for a screen that draws what pressing play would fetch. */
    public fun observeStreamOverride(scope: StreamOverrideScope, id: String): Flow<StreamRung?>

    /**
     * Pins a rung to one track or album, replacing any rung already pinned to it.
     *
     * An override is **absolute** - one value for every connection - and it is **kept** when the item
     * is downloaded rather than cleared: a local copy always wins while it exists, so the override is
     * simply dormant, and freeing disk space should not silently revoke a preference the user never
     * withdrew. See [app.needler.core.domain.model.StreamOverride].
     */
    public suspend fun setStreamOverride(scope: StreamOverrideScope, id: String, rung: StreamRung)

    /** Removes the override, returning the item to its album's, or to the mode default. */
    public suspend fun clearStreamOverride(scope: StreamOverrideScope, id: String)

    public suspend fun setScrobblingEnabled(enabled: Boolean)

    /** Re-reads the server's scrobble destinations. */
    public suspend fun refreshScrobblePreferences(): Outcome<ScrobblePreferences>

    /**
     * Queues a scrobble for submission.
     *
     * Scrobbles accrued offline are journalled and submitted with their original timestamps on
     * reconnect, which is why the event carries its own [ScrobbleEvent.playedAt].
     */
    public suspend fun submitScrobble(event: ScrobbleEvent): Outcome<Unit>

    /**
     * The crate as it was last persisted. Survives process death and restarts.
     *
     * **This is a restore point, not the live queue.** The live queue belongs to
     * [app.needler.core.domain.playback.PlaybackController], because it is the session's queue and
     * the session is what is actually playing. Read this to restore the crate at cold start, or to
     * render it on a surface with no session connected yet - a widget drawn before the service
     * starts. Anything that *changes* the crate calls the controller, which persists the result
     * through [savePersistedQueue].
     */
    public fun observePersistedQueue(): Flow<PlayQueue>

    /** A one-shot read of the persisted crate: what the player service restores into the session. */
    public suspend fun restorePersistedQueue(): PlayQueue

    /**
     * Persists the crate. **Called by the player service and by nothing else.**
     *
     * One writer is the whole point. Two components that both believe they own the queue produce a
     * crate that changes under the user - a reorder saved here and then overwritten by the session's
     * own copy a tick later - and the resulting bug looks like a race in the UI rather than what it
     * is. That is why there are no `enqueue`, `moveQueueItem`, `removeQueueItem` or `clearQueue`
     * members on this interface: those are edits to the live queue, so they live on
     * [app.needler.core.domain.playback.PlaybackController] and arrive here only as a whole saved
     * queue.
     */
    public suspend fun savePersistedQueue(queue: PlayQueue)

    /**
     * Output targets for the single "Play on" picker: this device, Bluetooth sinks and Cast receivers
     * in one list.
     *
     * Cast entries carry their probed [app.needler.core.domain.model.CastAvailability], because a
     * receiver fetches audio itself and cannot reach a VPN-only, self-signed or plain-HTTP server - the
     * picker must say so rather than failing after the user picks a speaker.
     */
    public fun observeOutputTargets(): Flow<List<OutputTarget>>

    public fun observeSelectedOutput(): Flow<OutputTarget>

    public suspend fun selectOutput(target: OutputTarget): Outcome<Unit>
}
