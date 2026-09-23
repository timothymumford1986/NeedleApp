package app.needler.update

import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The whole update story in one object: when to look, what to offer, what to download, and when to
 * hand the result to the package installer.
 *
 * A `@Singleton`, because the decision is about the app rather than about a screen. The banner's
 * view model comes and goes with the composition — a rotation, a tab switch, a trip into album
 * detail — and none of that should restart a check or drop a download in progress.
 *
 * ## No `CoroutineScope` of its own
 *
 * Every method here is `suspend` and runs on the caller's scope, which in practice is the banner
 * view model's `viewModelScope`. A process-wide scope was considered and rejected: it would keep a
 * download alive after the last thing interested in it had gone, and an update is a foreground,
 * listener-initiated action, not background work. If the composition dies mid-download the transfer
 * is cancelled, the part-file is deleted by [ApkDownloader], and the offer is still there next time.
 * Nothing is lost but bytes.
 *
 * This is also why the update check is **not** a `WorkManager` job. REQUIREMENTS.md reserves
 * background work for "pull state polling, metadata sync, downloads" — things that must happen
 * whether or not anyone is looking. A release announcement is only worth anything when there is a
 * screen to announce it on, so it runs when the banner composes and not a moment otherwise.
 *
 * ## Failure is silence
 *
 * No path through this class produces a message about a failed *check*. Offline, rate-limited,
 * malformed, no release at all: the banner stays absent and the app carries on. The listener asked
 * for none of this, and an app that interrupts to report that a thing they did not request did not
 * happen is an app that has misjudged whose time it is spending. Failures with something to say —
 * a download that broke, an install the platform refused — appear only after the listener pressed
 * Update, because then they are waiting for an answer.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val releases: GitHubReleaseClient,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val preferences: UpdatePreferences,
    private val installed: InstalledVersion,
) {

    private val local = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /** Serialises checks, so two Routes composing at once cannot make two requests. */
    private val checkLock = Mutex()

    /**
     * When this process last *attempted* a check, successful or not.
     *
     * In memory on purpose. A persisted attempt time would let one failed check on a train hide the
     * next real opportunity behind a 15-minute wall across a restart; keeping it here means a fresh
     * process always gets one try, which is the behaviour someone force-stopping the app and
     * reopening it would expect.
     */
    private var lastAttemptAtMillis: Long = 0L

    /**
     * What the banner should be showing, folding this class's own state together with the
     * platform's install state.
     *
     * A `Flow` rather than a `StateFlow` because combining two `StateFlow`s needs a scope to hold
     * the result, and this class deliberately has none. The view model calls `stateIn` on its own
     * scope, which is where a UI state belongs anyway.
     */
    fun observe(): Flow<UpdateState> = combine(local, installer.status) { offered, install ->
        merge(offered, install)
    }

    // ---- the check ---------------------------------------------------------

    /**
     * Check GitHub, if enough time has passed and there is nothing already on screen.
     *
     * Safe to call on every composition — that is how it is meant to be called. All three guards
     * (the state check, the mutex, and [UpdateCheckPolicy.isCheckDue]) exist so that the caller can
     * be as naive as `LaunchedEffect(Unit) { check() }` and still make at most one request a day.
     */
    suspend fun checkForUpdateIfDue() {
        // Something is already being offered, downloaded or installed. Re-checking could only
        // replace the offer under the listener's finger.
        if (local.value != UpdateState.Idle) return

        checkLock.withLock {
            if (local.value != UpdateState.Idle) return

            val now = System.currentTimeMillis()
            val due = UpdateCheckPolicy.isCheckDue(
                now = now,
                lastCheckAtMillis = preferences.lastCheckAtMillis(),
                retryNotBeforeMillis = preferences.retryNotBeforeMillis(),
                lastAttemptAtMillis = lastAttemptAtMillis,
            )
            if (!due) return

            lastAttemptAtMillis = now
            when (val lookup = releases.latestRelease()) {
                is ReleaseLookup.Found -> {
                    preferences.recordCheckedAt(now)
                    offer(lookup.release)
                }

                // A complete answer that happens to contain nothing installable. The question was
                // asked and answered, so the ordinary cadence resumes.
                ReleaseLookup.NoUsableRelease -> preferences.recordCheckedAt(now)

                // Persisted, because the limit is per IP address and survives a restart of ours.
                is ReleaseLookup.RateLimited ->
                    preferences.recordRetryNotBefore(now + lookup.retryAfterMillis)

                // Nothing persisted at all: the in-memory attempt throttle is the only wait, so
                // reconnecting to Wi-Fi is noticed within the same sitting.
                ReleaseLookup.Unreachable -> Unit
            }

            // Tidy up after whatever the last install did, now rather than on the next launch.
            downloader.pruneStaleDownloads(installed.versionCode())
        }
    }

    private fun offer(release: AvailableUpdate) {
        val shouldOffer = UpdateCheckPolicy.shouldOffer(
            candidateVersionCode = release.versionCode,
            installedVersionCode = installed.versionCode(),
            dismissedVersionCode = preferences.dismissedVersionCode(),
        )
        if (shouldOffer) local.value = UpdateState.Available(release)
    }

    // ---- the listener's actions --------------------------------------------

    /**
     * Download the offered release and hand it to the package installer.
     *
     * Also the retry path, and the resume-after-granting-permission path: all three are the same
     * sequence from wherever it stopped, which is why the banner's one action button can call this
     * whatever it currently reads.
     */
    suspend fun downloadAndInstall() {
        val update = local.value.updateOrNull ?: return

        // Clear any previous refusal, or a stale `Failed` would still be merged into the state and
        // the banner would report a failure over a download that is now running.
        installer.reset()

        if (!installer.canInstallPackages()) {
            local.value = UpdateState.Available(update)
            installer.requirePermission()
            return
        }

        local.value = UpdateState.Downloading(update, downloadedBytes = 0L, totalBytes = update.sizeBytes)

        val apk = try {
            downloader.download(update) { bytesRead ->
                local.value = UpdateState.Downloading(
                    update = update,
                    downloadedBytes = bytesRead,
                    totalBytes = update.sizeBytes,
                )
            }
        } catch (cancelled: CancellationException) {
            // The composition went away mid-transfer. Put the offer back so it is waiting when the
            // listener returns, and let the cancellation continue to unwind.
            local.value = UpdateState.Available(update)
            throw cancelled
        }

        if (apk == null) {
            local.value = UpdateState.DownloadFailed(update)
            return
        }

        local.value = UpdateState.Installing(update)
        installer.install(apk)
    }

    /**
     * The listener waved the banner away.
     *
     * Records the version so this release stays quiet, and only this release: a later one still
     * gets to speak. There is no way back to a dismissed banner and that is intentional — the
     * update is still on GitHub, and a listener who dismissed it twice has said what they mean.
     */
    fun dismiss() {
        local.value.updateOrNull?.let { update -> preferences.recordDismissed(update.versionCode) }
        installer.reset()
        local.value = UpdateState.Idle
    }

    /** The Settings page that grants install-unknown-apps for this package. */
    fun installPermissionIntent(): Intent = installer.unknownSourcesSettingsIntent()

    // ---- state folding -----------------------------------------------------

    /**
     * One state out of two.
     *
     * The platform's half wins wherever it has something to say, because it is reporting on
     * something that has already happened; this class's half is only ever an intention. An
     * [InstallStatus.Cancelled] is folded back to a plain offer rather than to an error — declining
     * the platform's dialogue is a decision, not a fault.
     */
    private fun merge(offered: UpdateState, install: InstallStatus): UpdateState {
        val update = offered.updateOrNull ?: return UpdateState.Idle
        return when (install) {
            InstallStatus.PermissionRequired -> UpdateState.PermissionRequired(update)
            InstallStatus.AwaitingConfirmation -> UpdateState.AwaitingConfirmation(update)
            InstallStatus.Staging, InstallStatus.Committing -> UpdateState.Installing(update)
            InstallStatus.Succeeded -> UpdateState.Installing(update)
            InstallStatus.Cancelled -> UpdateState.Available(update)
            is InstallStatus.Failed -> UpdateState.InstallFailed(update)
            InstallStatus.Idle -> offered
        }
    }
}

/**
 * What there is to say about an update, if anything.
 *
 * [UpdateState.Idle] is by far the most common value and is the reason the banner is a slot that
 * composes to nothing rather than a screen with an empty state: for all but a few minutes in the
 * life of an install, the honest rendering of this state is no pixels at all.
 */
sealed interface UpdateState {

    /** Nothing found, nothing offered, nothing running. Draws nothing. */
    data object Idle : UpdateState

    /** A newer release exists and has not been dismissed. */
    data class Available(val update: AvailableUpdate) : UpdateState

    data class Downloading(
        val update: AvailableUpdate,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateState

    /** Staged or committed; the platform has it. */
    data class Installing(val update: AvailableUpdate) : UpdateState

    /** Install-unknown-apps has not been granted for Needler. */
    data class PermissionRequired(val update: AvailableUpdate) : UpdateState

    /** The platform's own confirmation dialogue is up. */
    data class AwaitingConfirmation(val update: AvailableUpdate) : UpdateState

    /** The transfer broke. Retryable, and the listener is waiting for an answer, so it is shown. */
    data class DownloadFailed(val update: AvailableUpdate) : UpdateState

    /** The platform refused the install. Retryable. Never a prompt to uninstall anything. */
    data class InstallFailed(val update: AvailableUpdate) : UpdateState
}

/**
 * The release a state is about, or `null` for [UpdateState.Idle].
 *
 * An extension rather than a member of the interface so the subtypes can keep their own
 * non-nullable `update` property without overriding a nullable one — which would be the same name
 * meaning two subtly different things, and the exhaustive `when` here is clearer than that trade.
 */
val UpdateState.updateOrNull: AvailableUpdate?
    get() = when (this) {
        UpdateState.Idle -> null
        is UpdateState.Available -> update
        is UpdateState.Downloading -> update
        is UpdateState.Installing -> update
        is UpdateState.PermissionRequired -> update
        is UpdateState.AwaitingConfirmation -> update
        is UpdateState.DownloadFailed -> update
        is UpdateState.InstallFailed -> update
    }
