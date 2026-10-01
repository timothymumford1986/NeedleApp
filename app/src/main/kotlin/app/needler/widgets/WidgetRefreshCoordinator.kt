package app.needler.widgets

import android.content.Context
import app.needler.core.domain.model.PullActivitySummary
import app.needler.core.domain.model.SyncPhase
import app.needler.core.domain.model.SyncState
import app.needler.core.domain.playback.PlaybackController
import app.needler.core.domain.playback.PlaybackState
import app.needler.core.domain.repository.PullRepository
import app.needler.core.domain.repository.SyncRepository
import app.needler.widget.NeedlerWidgets
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pushes the home-screen widgets when the thing they draw changes.
 *
 * ## Why this class exists at all
 *
 * A Glance widget composes in this app's process and publishes a `RemoteViews` the launcher then
 * draws on its own. While the process is alive each widget follows its source flows unaided. When the
 * process is *not* alive - which, for a music player nobody is currently using, is most of the time -
 * the launcher keeps showing whatever was published last. Without a push from something that is still
 * running, a widget sits on a stale card indefinitely: the now-playing card names a track that
 * stopped yesterday, the recently-added card names an album that is no longer the newest, and the
 * pull card shows a percentage for a download that finished hours ago.
 *
 * `NeedlerWidgets` exposes the push and deliberately does not call it. Its KDoc says why: `:app`
 * depends on `:widget`, so `:widget` must not reach back into its consumer to schedule work, or the
 * module graph gains a cycle for one function call. This is the other side of that seam, and `:app`
 * is the only module that can hold it - it is the one place that sees `:widget`, `:player:service`
 * and the domain repositories at once.
 *
 * ## Three signals, not one
 *
 * `NeedlerWidgets.refresh` redraws all three widgets and costs three compositions and up to three
 * Binder pushes. Each card has its own trigger, and using the targeted calls keeps an unrelated
 * change from waking the other two:
 *
 * | Widget | Redrawn when |
 * | --- | --- |
 * | Now playing | the session's current item or transport state changes |
 * | Recently added | a sync **finishes** - not once per album written |
 * | Pulls | the activity summary's `revision` moves |
 *
 * The sync trigger is a completion edge rather than the state flow itself, because a delta sync
 * writes many albums and emits throughout; redrawing per write would push the launcher dozens of
 * times for one card that changes at most once. The pull trigger is the `revision` for the reason
 * REQUIREMENTS.md "Polling schedule" gives it: "The `revision` field makes an unchanged poll almost
 * free, so the app can compare revisions and skip all downstream work when nothing has moved."
 *
 * ## The cost that needs measuring on a device
 *
 * Collecting [PlaybackController.observeState] holds a `MediaController` bound to the session, and
 * REQUIREMENTS.md "Battery and data" forbids long-lived connections while backgrounded. That rule is
 * about *network* connections and this is a local binder, but the binding still keeps the playback
 * service from being torn down, so the two are not unrelated.
 *
 * It is accepted here on the grounds that the now-playing widget is worthless when stale - a
 * transport control that acts on a track that is not playing is worse than no widget - and that the
 * controller is already bound whenever anything is playing, which is the only time this flow emits
 * anything new. **This has not been measured on a device.** If it proves to hold the service alive
 * with nothing playing, the fix is to move the now-playing push into `:player:service`, which owns
 * the session and knows when it goes idle, and leave only the two repository-driven pushes here.
 */
@Singleton
public class WidgetRefreshCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackController: PlaybackController,
    private val syncRepository: SyncRepository,
    private val pullRepository: PullRepository,
) {

    /**
     * Starts watching. Safe to call once, from `Application.onCreate`.
     *
     * [scope] must outlive every screen - an application scope, not a view model's. A widget push
     * that was cancelled because the user left the app is the exact case this class exists to cover.
     */
    public fun start(scope: CoroutineScope) {
        scope.launch {
            playbackController.observeState()
                .map { state -> state.widgetIdentity() }
                .distinctUntilChanged()
                .collect { NeedlerWidgets.refreshNowPlaying(context) }
        }

        scope.launch {
            syncRepository.observeSyncState()
                .map(SyncState::syncCompletionMark)
                .distinctUntilChanged()
                .collect { NeedlerWidgets.refreshRecentlyAdded(context) }
        }

        scope.launch {
            pullRepository.observeActivitySummary()
                .map { summary: PullActivitySummary? -> summary?.revision }
                .distinctUntilChanged()
                .collect { NeedlerWidgets.refreshPulls(context) }
        }
    }

    private companion object {

        /**
         * What the now-playing card actually draws, reduced to a value that can be compared.
         *
         * Position is excluded on purpose. It ticks several times a second and the card shows no
         * scrubber, so including it would push the launcher continuously for the whole of every
         * track - the same reasoning `WearPlaybackProtocol` gives for keeping position off the
         * watch's wire.
         */
        private fun PlaybackState.widgetIdentity(): String =
            (currentItem?.track?.key?.canonicalString ?: "none") + "/" + isPlaying

        /**
         * A value that changes exactly once per completed sync.
         *
         * Both timestamps are needed: a delta sync moves only [SyncState.lastDeltaSyncAt] and a full
         * sync moves both, so keying on either alone misses one of them. The phase is folded in so
         * that a sync which starts, fails and leaves the timestamps untouched still settles back to a
         * distinct value rather than leaving the flow mid-sync.
         */
        private fun SyncState.syncCompletionMark(): String =
            lastFullSyncAt.toString() + "/" + lastDeltaSyncAt.toString() + "/" + (phase == SyncPhase.IDLE)
    }
}
