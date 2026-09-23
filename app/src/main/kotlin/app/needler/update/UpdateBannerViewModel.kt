package app.needler.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the update banner.
 *
 * Thin on purpose. The scheduling, the network, the file and the package installer all live in
 * [UpdateRepository] and the classes under it, because none of that has anything to do with a
 * screen — and because the repository is a `@Singleton`, a download survives this view model being
 * torn down and rebuilt by a rotation. What is left here is the seam: turning [UpdateState] into
 * [UpdateBannerUiState], and turning taps into suspending calls on a scope.
 *
 * ## Why the check runs from a view model at all
 *
 * There is no `WorkManager` job and no `Application.onCreate` hook. REQUIREMENTS.md budgets cold
 * start to library content at under 1.2 s and `NeedlerApplication` is explicit that "nothing that
 * touches the network, the database or the credential store runs in `onCreate`" — an HTTP request
 * to github.com on the critical path would be exactly the kind of thing that budget exists to keep
 * out. Running it when the banner first composes puts it after the first frame, on a screen that is
 * already interactive, where an extra request costs nobody anything.
 *
 * [onShown] is therefore safe to call on every composition; [UpdateRepository.checkForUpdateIfDue]
 * has the cadence, the mutex and the in-memory throttle behind it.
 */
@HiltViewModel
class UpdateBannerViewModel @Inject constructor(
    private val updates: UpdateRepository,
) : ViewModel() {

    val state: StateFlow<UpdateBannerUiState> = updates.observe()
        .map { updateState -> updateState.toBannerState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            // Absent until proven otherwise, so the first frame never flashes a bar.
            initialValue = UpdateBannerUiState(),
        )

    /** The banner has composed. Check GitHub if the cadence allows it. */
    fun onShown() {
        viewModelScope.launch { updates.checkForUpdateIfDue() }
    }

    /**
     * Update, Allow or Retry — all the same call.
     *
     * The permission case is the only one the Route handles differently, and it handles it *as
     * well as* this, not instead: it launches Settings, and returning from Settings comes back
     * through [onInstallPermissionSettingsClosed] to carry on from where this stopped.
     */
    fun onAction() {
        viewModelScope.launch { updates.downloadAndInstall() }
    }

    fun onDismiss() {
        updates.dismiss()
    }

    /** The per-package install-unknown-apps Settings page, for the Route to launch. */
    fun installPermissionIntent(): Intent = updates.installPermissionIntent()

    /**
     * The listener has come back from the Settings page.
     *
     * The result code is not consulted, because that page does not report one: whether the toggle
     * was flipped is only knowable by asking `canRequestPackageInstalls()` again, which is what
     * this call ends up doing. If they granted it, the update proceeds; if they did not, the banner
     * goes back to asking, and nothing has been downloaded either way.
     */
    fun onInstallPermissionSettingsClosed() {
        viewModelScope.launch { updates.downloadAndInstall() }
    }

    private companion object {
        /**
         * Keep the state alive briefly after the last subscriber leaves, so a rotation or a trip
         * into album detail does not drop a download's progress flow and restart the fold.
         * The same five seconds `LibraryViewModel` uses, for the same reason.
         */
        const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}
