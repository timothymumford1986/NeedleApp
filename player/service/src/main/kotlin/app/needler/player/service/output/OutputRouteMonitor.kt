package app.needler.player.service.output

import app.needler.core.domain.model.OutputTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * One `AudioDeviceCallback` for the whole app, and none while nobody is looking.
 *
 * ## Why the sharing is the point
 *
 * Two surfaces want routes and they want them for different lengths of time: the "Play on" sheet wants
 * the list for as long as it is open, and the player, the lock screen, the widgets and Wear want the
 * name of the live route for as long as any of them is drawing - REQUIREMENTS.md "Output": "The current
 * output is always named in the player ... so a user never wonders where sound is going." Collected
 * directly, that is a platform callback registered once per surface.
 *
 * `shareIn` with [SharingStarted.WhileSubscribed] makes it one registration no matter how many
 * surfaces are watching, and **no registration at all** once the last one leaves. REQUIREMENTS.md
 * "Battery and data" forbids long-lived connections behind a backgrounded app; this is not a connection,
 * but the rule's intent is that nothing survives the surface that needed it, and reference-counting the
 * subscription is a tighter binding than tying it to the session's lifetime would have been. A session
 * outlives the UI by design - it is still playing with the app swiped away - so registering in
 * `NeedlerPlaybackService.onCreate` would have held the callback through exactly the backgrounded case
 * the rule is about.
 *
 * The five-second stop timeout is a rotation, not a grace period: a configuration change tears the
 * sheet down and rebuilds it, and re-registering across that would re-read the device list for nothing.
 * It matches the stop timeout `OutputViewModel` already uses for the same reason.
 *
 * [replay] of one is what lets a reopened sheet draw its rows in the first frame instead of flashing
 * "Looking for speakers nearby" at a user whose speaker has not moved.
 */
public class OutputRouteMonitor(
    devices: AudioOutputDevices,
    names: OutputNames,
    /**
     * The scope the shared subscription lives in.
     *
     * Injected so a test can hand in `runTest`'s own `backgroundScope` and keep the whole flow on the
     * test dispatcher. A monitor that created its own scope on [Dispatchers.Default] would put the
     * platform read on a real thread while the test waited on a virtual clock, which is a flake rather
     * than a test.
     */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /**
     * The picker's rows and the live row, from the platform's device list.
     *
     * `catch` rather than a crash: this flow is folded into `PlaybackState`, so a failure here would
     * stop the lock screen, the widgets and Wear from seeing playback at all. [OutputRoutes.Unknown]
     * degrades to the picker that shipped before this work - one row, this device - which is a worse
     * picker and not a broken player.
     */
    public val routes: SharedFlow<OutputRoutes> = devices.observeDevices()
        .map { snapshot -> OutputTargetMapper.routes(snapshot, names) }
        .catch { emit(OutputRoutes.Unknown) }
        .distinctUntilChanged()
        .shareIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = STOP_TIMEOUT_MS),
            replay = 1,
        )

    /**
     * Every target the picker lists.
     *
     * `distinctUntilChanged` after the `map` as well as before it, because a change of live route
     * changes [OutputRoutes] without changing the list of rows - and a sheet that re-diffs its list
     * every time the tick moves is work for nothing.
     */
    public fun observeTargets(): Flow<List<OutputTarget>> =
        routes.map { it.targets }.distinctUntilChanged()

    /** Where sound is actually going, or null before the first snapshot has arrived. */
    public fun observeActive(): Flow<OutputTarget?> =
        routes.map { it.active }.distinctUntilChanged()

    private companion object {
        /** Long enough to ride out a rotation, short enough that a dismissed sheet lets go. */
        const val STOP_TIMEOUT_MS: Long = 5_000L
    }
}
