package app.needler.background

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.data.background.BackgroundStateStore
import app.needler.core.data.background.BackgroundWorkScheduler
import app.needler.core.data.background.NeedlerNotifier
import app.needler.core.data.background.NotificationPermissionPolicy
import app.needler.core.data.background.NotificationPermissionState
import app.needler.core.data.background.NotificationPermissionTrigger
import app.needler.core.data.background.PollSchedule
import app.needler.core.data.background.SyncTrigger
import app.needler.core.data.background.SyncTriggerResolver
import app.needler.core.domain.model.PullBucket
import app.needler.core.domain.repository.PullRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Everything the running app owes the background half.
 *
 * Three jobs, and they are together because all three happen at the same moment - the app coming to
 * the foreground - and all three are about work that outlives the screen that asked for it:
 *
 *  1. **Keep the schedule alive.** The periodic poller is re-enqueued on every foreground at the
 *     cadence the local pull table implies. `WorkManager` keeps its own schedule across reboots,
 *     but not across an app update or a force-stop, and a poller that quietly stopped is
 *     indistinguishable from a server with nothing to say.
 *  2. **Sync when something other than a screen should cause one.** Until this existed the only
 *     trigger was the Library screen's own refresh, so a fresh install showed an empty library
 *     until the user happened to open that screen.
 *  3. **Ask for the notification permission at a moment that means something.** Never on launch:
 *     the first time the app has something to deliver later, which is the user's first pull.
 */
@HiltViewModel
class BackgroundViewModel @Inject constructor(
    private val pullRepository: PullRepository,
    private val scheduler: BackgroundWorkScheduler,
    private val syncTriggers: SyncTriggerResolver,
    private val backgroundState: BackgroundStateStore,
    private val notifier: NeedlerNotifier,
) : ViewModel() {

    /**
     * The Pulls tab badge: active pulls plus unseen completions.
     *
     * Read straight from the mirror, so it needs no permission, no notification and no successful
     * poll to be correct. REQUIREMENTS.md calls it the reliable channel for exactly that reason,
     * and it is what makes a refused notification permission a cosmetic loss rather than a
     * functional one.
     */
    val pullsBadgeCount: StateFlow<Int> = pullRepository.observePullBadgeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    private val permissionRequest = MutableStateFlow(false)

    /** True when the system permission dialog should be shown now. */
    val shouldRequestNotificationPermission: StateFlow<Boolean> = permissionRequest.asStateFlow()

    /**
     * Called whenever the app comes forward.
     *
     * Deliberately cheap and deliberately not awaited by anything the user can see: it enqueues
     * work and returns. The sync itself runs in a Worker with a network constraint, so a foreground
     * with no connection costs nothing and resumes on its own.
     */
    fun onAppForegrounded() {
        viewModelScope.launch {
            // Nothing is scheduled before onboarding. A poller armed on a fresh install would wake
            // every six hours only to fail at building a URL it has no host for.
            if (!syncTriggers.hasServer()) return@launch

            val active = pullRepository.observePulls(PullBucket.ACTIVE).first()
            scheduler.schedulePeriodicPoll(PollSchedule.cadenceFor(active.isNotEmpty()))

            val trigger: SyncTrigger = syncTriggers.resolve()
            scheduler.scheduleSync(trigger)

            maybeAskForNotificationPermission()
        }
    }

    /**
     * Called when the user turns on one of the three notification switches.
     *
     * The second of the two moments at which asking makes sense: they have just said they want to
     * be told something, so a dialog asking whether they may be is answerable.
     */
    fun onNotificationSettingEnabled() {
        viewModelScope.launch {
            ask(NotificationPermissionTrigger.ENABLED_A_NOTIFICATION)
        }
    }

    /**
     * Records the answer, whatever it was.
     *
     * A refusal is remembered so the app does not ask again - Android stops showing the dialog
     * after two refusals anyway, and an app that keeps trying trains the user to dismiss it. There
     * is deliberately nothing else to do here: the poller, the mirror and the badge all carry on
     * exactly as before, because none of them needs this permission.
     */
    fun onNotificationPermissionResult(granted: Boolean) {
        permissionRequest.value = false
        viewModelScope.launch { backgroundState.markNotificationPermissionAsked() }
    }

    /**
     * The first-pull trigger.
     *
     * A pull the user has just placed is the first time this app has something worth interrupting
     * them for later, which is what makes the request answerable. Asking on launch, before they
     * have asked the app for anything, is the version of this that gets refused.
     */
    private suspend fun maybeAskForNotificationPermission() {
        if (!backgroundState.firstPullPlaced()) {
            val anyPull = pullRepository.observePulls().first().isNotEmpty()
            if (!anyPull) return
            backgroundState.markPullPlaced()
        }
        ask(NotificationPermissionTrigger.PLACED_FIRST_PULL)
    }

    private suspend fun ask(trigger: NotificationPermissionTrigger) {
        val state: NotificationPermissionState = notifier.permissionState()
        val asked: Boolean = backgroundState.notificationPermissionAsked()
        permissionRequest.value = NotificationPermissionPolicy.shouldAsk(state, trigger, asked)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
