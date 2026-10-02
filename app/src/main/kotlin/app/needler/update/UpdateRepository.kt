@file:OptIn(ExperimentalCoroutinesApi::class)

package app.needler.update

import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
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
 *
 * ## No state in here may be unleaveable, and [UpdateState.Installing] was
 *
 * This is the rule the class was missing, and it is worth stating as a rule because of what the
 * delivery model makes of a breach. REQUIREMENTS.md's "Decisions already fixed" settles
 * distribution as a "Signed APK on GitHub releases", and names the in-app updater only to say it
 * is one of two surfaces that "exist in the code and are specified nowhere in this document". That
 * combination means this class *is* the delivery mechanism: there is no store to fall back to, and
 * an update the app cannot offer is an update that cannot be installed by any route the app knows.
 * A permanently stuck state here is therefore not a cosmetic fault, it is the app losing the
 * ability to replace itself.
 *
 * [UpdateState.Installing] has only two honest futures. Either the install succeeds, this process
 * is replaced, and nothing needs clearing because the state died with it; or it did not happen, and
 * the offer belongs back on the banner. There is no third future in which "Installing 1.2.3" with
 * no action and no dismissal is the correct rendering, and there were three ways to reach exactly
 * that:
 *
 *  1. `Committing` or `AwaitingConfirmation` with no broadcast ever arriving. The platform reports
 *     the outcome of a *commit*, and a confirmation dialogue that is never answered has no
 *     outcome — see [UpdateCheckPolicy.INSTALL_HANDOVER_TIMEOUT_MILLIS]. This is the one a device
 *     reported, and the only one the platform is responsible for.
 *  2. `Succeeded` folded to [UpdateState.Installing], which made the *successful* case the most
 *     permanent of all on the rare occasion this process outlives the swap. It now folds to
 *     [UpdateState.Idle]; see [merge].
 *  3. [UpdateState.Installing] held here while the platform's half had gone back to
 *     [InstallStatus.Idle], which the `Idle` branch of [merge] passed straight through. No waiting
 *     involved: that fold has nothing behind it at all, and [observe] releases it on sight.
 *
 * There are three escapes, deliberately, because each covers a different way a listener meets the
 * bar and no one of them covers every case:
 *
 *  * **[observe] watches the hand-over.** A bounded wait, restarted by every genuine platform
 *    event and shortened by [ApkInstaller.committedSessionIsGone], after which the offer goes
 *    back. This is the only escape that works for someone who is simply *looking* at the bar and
 *    will never press anything, which is why it is not merely the manual path.
 *  * **A manual check cannot be refused by it.** [checkNow] releases a stale hand-over before it
 *    decides anything, so Settings' "Check for updates" always has an effect.
 *  * **The ambient check releases it too.** The weakest of the three, because
 *    [UpdateBannerViewModel.onShown] fires once per composition of the chrome and not on a timer,
 *    so it cannot be relied on to come round again — but it costs one comparison and it means a
 *    composition that outlived a torn-down watchdog is not depending on the listener noticing.
 *
 * The banner is *not* made dismissible in this phase. [UpdateBannerUiState.dismissible] stays
 * false for an install already handed over, for the reason it always gave — the app is not the one
 * in charge and a control that stops nothing is a lie. A blanket dismissal would also undo the
 * point of the bar: this feature's one job is that an available release is *visible*, and a control
 * that retires it for good would have hidden a broken updater rather than mended one. The document
 * is no help either way here — it specifies no update mechanism at all, as the note at the top of
 * [UpdateCheckPolicy] sets out. The fix is that the stuck phase stops being that phase, not that
 * the listener is handed a broom.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val releases: GitHubReleaseClient,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val preferences: UpdatePreferences,
    private val installed: InstalledVersion,
    private val clock: UpdateClock,
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
     * When an APK was last handed to the package installer, `0` when none has been.
     *
     * The deadline for the bounded wait described at the top of this class. A wall-clock stamp
     * rather than a countdown, because the wait has to keep running while nothing is observing it:
     * the banner's collector stops a few seconds after the app goes to the background, and a
     * listener who returns an hour later must find the offer already back rather than a fresh
     * three-minute wait starting over.
     *
     * In memory, like [lastAttemptAtMillis], and for a stronger reason than that one. The state
     * this governs is itself in memory, so it cannot outlive the process that holds it; persisting
     * the deadline would mean carrying a wait for an install nothing is waiting on.
     *
     * Volatile because it is written from whichever coroutine ran [downloadAndInstall] and read
     * from the one collecting [observe].
     */
    @Volatile
    private var installHandedOverAtMillis: Long = 0L

    /**
     * What the banner should be showing, folding this class's own state together with the
     * platform's install state, and refusing to show either of them for ever.
     *
     * A `Flow` rather than a `StateFlow` because combining two `StateFlow`s needs a scope to hold
     * the result, and this class deliberately has none. The view model calls `stateIn` on its own
     * scope, which is where a UI state belongs anyway.
     *
     * ## The watchdog, and why it lives in the flow
     *
     * The wait has to run somewhere, and this class has no scope on purpose — the reasoning is at
     * the top of the file and has not changed. Putting it in the flow puts it on the collector's
     * scope, which is the same `viewModelScope` every other suspending call here already borrows,
     * and gives it the one property a scope of this class's own could not: the wait exists exactly
     * while something is looking at the banner. Nothing wakes up to time out a bar nobody is
     * reading.
     *
     * `transformLatest` rather than `transform` is the whole mechanism. Every upstream change
     * cancels the pending wait and starts a new one, so a hand-over that is *progressing* —
     * `Staging` to `Committing` to `AwaitingConfirmation` — keeps resetting its own clock, and only
     * silence expires. Each of those transitions is the platform proving it is still working.
     *
     * Three choices inside worth stating:
     *
     *  * A hand-over that is **already** finished on the first emission is released without being
     *    emitted at all, so coming back to the app after an hour does not flash a dead bar for a
     *    frame on the way to the live one. "Finished" is either stale or *unrecorded*: a
     *    [UpdateState.Installing] with no deadline behind it is bookkeeping with nothing under it,
     *    which is the third of the three stuck routes listed at the top of this file and is also
     *    the brief window after a release in which the two halves of the state have not both
     *    landed yet. Without that arm, a release could restart its own three-minute wait.
     *  * [ApkInstaller.committedSessionIsGone] is polled rather than waited on, because a vanished
     *    session has no event to subscribe to. A timeout is a guess and a missing session is a
     *    fact, so the fact is checked first and often, and the guess is the backstop.
     *  * Releasing is a write to [local] and a reset of [installer], both upstream of this
     *    operator, so the release re-enters here and emits the recovered state. In every state that
     *    reaches this branch at least one of those two writes is a real change, so the early return
     *    can never swallow an emission.
     */
    fun observe(): Flow<UpdateState> = combine(local, installer.status) { offered, install ->
        merge(offered, install)
    }.transformLatest { state ->
        if (!state.isPlatformHandover) {
            emit(state)
            return@transformLatest
        }

        val handedOverAt = installHandedOverAtMillis
        val now = clock.nowMillis()
        if (handedOverAt <= 0L || UpdateCheckPolicy.isHandoverStale(now, handedOverAt)) {
            releaseHandover()
            return@transformLatest
        }

        emit(state)

        var waited = (now - handedOverAt).coerceAtLeast(0L)
        while (waited < UpdateCheckPolicy.INSTALL_HANDOVER_TIMEOUT_MILLIS) {
            if (installer.committedSessionIsGone()) break
            delay(UpdateCheckPolicy.HANDOVER_POLL_MILLIS)
            waited += UpdateCheckPolicy.HANDOVER_POLL_MILLIS
        }
        releaseHandover()
    }

    // ---- the check ---------------------------------------------------------

    /**
     * Check GitHub, if enough time has passed and there is nothing already on screen.
     *
     * Safe to call on every composition — that is how it is meant to be called. All three guards
     * (the state check, the mutex, and [UpdateCheckPolicy.isCheckDue]) exist so that the caller can
     * be as naive as `LaunchedEffect(Unit) { check() }` and still make at most one request a day.
     *
     * The state guard releases a stale hand-over before it reads the state, which is not a
     * formality: the guard's job is to avoid replacing an offer under the listener's finger, and a
     * hand-over the platform never answered is not an offer under anyone's finger. A guard that
     * refuses to act *because* the state is wrong is the one shape of guard this class must not
     * have.
     */
    suspend fun checkForUpdateIfDue() {
        releaseStaleHandover()

        // Something is already being offered, downloaded or installed. Re-checking could only
        // replace the offer under the listener's finger.
        if (local.value != UpdateState.Idle) return

        checkLock.withLock {
            if (local.value != UpdateState.Idle) return

            val now = clock.nowMillis()
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

    /**
     * Check right now, whatever the cadence says, and report what was found.
     *
     * This is the manual path: the "Check for updates" row in Settings, where a person has asked
     * the question and is owed a plain answer, unlike [checkForUpdateIfDue] which is the ambient
     * once-a-day glance. It still shares the mutex and still updates the offer, so a newer release
     * found here lights the banner too; it just skips the "is it due yet" gate.
     *
     * ## This is the deadlock's manual release, and it answers without a network
     *
     * A stale hand-over is let go before anything else happens, so the one row in the app whose
     * entire purpose is to ask the question can never be the row that refuses to. The outcome is
     * then [ManualUpdateCheck.UPDATE_AVAILABLE] from the released offer itself, with no request to
     * GitHub, and that is deliberate rather than lazy: the release is the answer the listener
     * needs, the banner now reads "Needler 1.2.3 is available" with an Update button on it, and
     * Settings' own line for this outcome — "An update is ready. See the bar at the bottom of the
     * app." — points straight at it. It also means the recovery works on a device with no
     * connection, which matters, because an install that stalled may well have stalled on a train.
     *
     * The alternative rejected was to release the hand-over and then still ask GitHub. It would
     * replace a certain answer with one that can come back [ManualUpdateCheck.OFFLINE] and leave
     * the listener unsure whether anything had been fixed.
     */
    suspend fun checkNow(): ManualUpdateCheck {
        releaseStaleHandover()

        when (local.value) {
            is UpdateState.Available -> return ManualUpdateCheck.UPDATE_AVAILABLE

            // A bar that is already showing a Retry is not an update in progress. Reporting one
            // would be the same lie as the stuck banner, in a quieter place: the listener presses
            // the row, is told something is happening, and nothing is.
            is UpdateState.DownloadFailed, is UpdateState.InstallFailed ->
                return ManualUpdateCheck.UPDATE_AVAILABLE

            UpdateState.Idle -> Unit

            // A download or install is genuinely under way. Re-checking would only get in its way,
            // and "an update is happening" is the honest answer — honest now, because a hand-over
            // that stopped being under way has already been released above.
            else -> return ManualUpdateCheck.BUSY
        }
        return checkLock.withLock {
            if (local.value is UpdateState.Available) return@withLock ManualUpdateCheck.UPDATE_AVAILABLE
            val now = clock.nowMillis()
            lastAttemptAtMillis = now
            val outcome = when (val lookup = releases.latestRelease()) {
                is ReleaseLookup.Found -> {
                    preferences.recordCheckedAt(now)
                    offer(lookup.release)
                    // offer() lights the banner only when the release is newer and not dismissed;
                    // if it did not, the release we found is the one already installed.
                    if (local.value is UpdateState.Available) {
                        ManualUpdateCheck.UPDATE_AVAILABLE
                    } else {
                        ManualUpdateCheck.UP_TO_DATE
                    }
                }
                ReleaseLookup.NoUsableRelease -> {
                    preferences.recordCheckedAt(now)
                    ManualUpdateCheck.UP_TO_DATE
                }
                is ReleaseLookup.RateLimited -> {
                    preferences.recordRetryNotBefore(now + lookup.retryAfterMillis)
                    ManualUpdateCheck.RATE_LIMITED
                }
                ReleaseLookup.Unreachable -> ManualUpdateCheck.OFFLINE
            }
            downloader.pruneStaleDownloads(installed.versionCode())
            outcome
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
     *
     * ## A retry reuses the APK on disk, and does not download it again
     *
     * [ApkDownloader.download] short-circuits on a file already at the asset's published length,
     * and that is the right behaviour on this path rather than an optimisation to be careful of.
     * The length is the only verification this package claims — its own reasoning is that the
     * *signature* is what makes an APK safe to install and the platform checks it as part of
     * installing — and nothing partial ever survives to be found: that downloader deletes the
     * target on cancellation, on `IOException`, on a body that runs past the published size, and on
     * a final length that does not match. So a file sitting there at exactly the published length
     * was written to completion by this code and by nothing else.
     *
     * A failed or abandoned *install* cannot have damaged it either. The session copies from the
     * file; it does not move it, truncate it or write to it.
     *
     * Against that, a forced re-download costs the listener the whole asset again, on whatever
     * connection they are on, and spends another of the sixty unauthenticated GitHub requests an
     * hour that this app shares with everything behind the same address. REQUIREMENTS.md fixes
     * distribution as a "Signed APK on GitHub releases", so every retry is a third-party transfer
     * and not a local one.
     *
     * Rejected, therefore: re-downloading on retry, which would only help against an APK corrupted
     * *after* a complete write — a storage fault, which the platform's signature check catches at
     * install time anyway, turning it into one more Retry rather than a bad install. Also rejected:
     * deleting the cached APK when a hand-over expires. The expiry is this class admitting its own
     * bookkeeping was wrong, and making the listener pay for that in megabytes would be charging
     * them for our mistake.
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
        // Stamped before the hand-over, not after, so that a session committed and then forgotten
        // always has a deadline behind it. Every route into UpdateState.Installing passes through
        // these two lines, which is what makes the watchdog's deadline impossible to be missing.
        installHandedOverAtMillis = clock.nowMillis()

        try {
            installer.install(apk)
        } catch (cancelled: CancellationException) {
            // The composition went away during the install call. Put the offer back exactly as the
            // download's own cancellation branch does: nothing has failed and the verified APK is
            // still on disk. This class must not go on holding an intention nobody is acting on.
            //
            // The deadline is deliberately *not* cleared. Staging is not interruptible — see
            // ApkInstaller.install for why that is the right trade — so by the time a cancellation
            // is observed the session has usually been committed and the platform's half still
            // says so. merge() will keep reporting an install, correctly, and the wait must still
            // be running when a collector comes back.
            local.value = UpdateState.Available(update)
            throw cancelled
        }
    }

    // ---- leaving a hand-over the platform never answered --------------------

    /**
     * Give up on a hand-over and put the offer back.
     *
     * Both halves of the state have to move or the fold would rebuild what was just cleared:
     * [merge] derives [UpdateState.Installing] from `Staging`, `Committing` and `Succeeded` with no
     * regard for this class's half, so resetting [installer] is what stops the next emission
     * saying the same thing again. Resetting it loses nothing — a broadcast that arrives afterwards
     * sets the status again, and [merge] still handles whatever it says.
     *
     * The APK is left on disk. See [downloadAndInstall] for why.
     */
    private fun releaseHandover() {
        installHandedOverAtMillis = 0L
        val update = local.value.updateOrNull
        installer.reset()
        local.value = if (update == null) UpdateState.Idle else UpdateState.Available(update)
    }

    /**
     * Release a hand-over that has gone unanswered too long, and leave a live one alone.
     *
     * The state is read from this class's half rather than the fold, because this class's half is
     * the one that records a hand-over at all: [local] holds [UpdateState.Installing] for the whole
     * of it, whether the platform's half currently says `Committing`, `AwaitingConfirmation` or
     * nothing.
     */
    private fun releaseStaleHandover() {
        if (local.value !is UpdateState.Installing) return
        val now = clock.nowMillis()
        if (!UpdateCheckPolicy.isHandoverStale(now, installHandedOverAtMillis)) return
        releaseHandover()
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
        installHandedOverAtMillis = 0L
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
     *
     * [InstallStatus.Succeeded] folds to [UpdateState.Idle] and no longer to
     * [UpdateState.Installing], which was the worst of the four stuck routes because it made the
     * *successful* outcome the unleaveable one. `STATUS_SUCCESS` means the install is finished:
     * there is nothing left to install, nothing left to offer and nothing left to say, so the
     * banner's honest rendering is no pixels at all. [ApkInstaller] notes that this status is
     * rarely seen because the process has usually already been replaced — but "usually" is not
     * "always", and on the occasions it is seen, the old code put the one state with no action and
     * no dismissal in front of a listener whose update had just worked.
     *
     * Rejected: a "Restart Needler to finish" phase for that case. It would add a phase and a
     * string to cover a window the platform is about to end by killing this process, and the
     * action it would ask for is the one thing the listener does next anyway.
     */
    private fun merge(offered: UpdateState, install: InstallStatus): UpdateState {
        val update = offered.updateOrNull ?: return UpdateState.Idle
        return when (install) {
            InstallStatus.PermissionRequired -> UpdateState.PermissionRequired(update)
            InstallStatus.AwaitingConfirmation -> UpdateState.AwaitingConfirmation(update)
            InstallStatus.Staging, InstallStatus.Committing -> UpdateState.Installing(update)
            InstallStatus.Succeeded -> UpdateState.Idle
            InstallStatus.Cancelled -> UpdateState.Available(update)
            is InstallStatus.Failed -> UpdateState.InstallFailed(update)
            InstallStatus.Idle -> offered
        }
    }
}

/**
 * Is this a state only the platform can move on?
 *
 * The two states in which the app has handed an APK over and has nothing left to do but wait, and
 * therefore the two the bounded wait in [UpdateRepository.observe] applies to. Both draw a bar with
 * no action and no dismissal, which is correct while the wait is live and is the whole bug once it
 * is not.
 *
 * [UpdateState.Downloading] is deliberately not one of them. That transfer is driven by this app's
 * own coroutine, under OkHttp's timeouts, with its own cancellation handling — it cannot go quiet
 * without something noticing.
 */
private val UpdateState.isPlatformHandover: Boolean
    get() = this is UpdateState.Installing || this is UpdateState.AwaitingConfirmation

/**
 * What there is to say about an update, if anything.
 *
 * [UpdateState.Idle] is by far the most common value and is the reason the banner is a slot that
 * composes to nothing rather than a screen with an empty state: for all but a few minutes in the
 * life of an install, the honest rendering of this state is no pixels at all.
 */
/** The result of a manual "Check for updates", for a row that has to say something back. */
enum class ManualUpdateCheck { UP_TO_DATE, UPDATE_AVAILABLE, OFFLINE, RATE_LIMITED, BUSY }

/**
 * The wall clock, as something that can be injected.
 *
 * One method over `System.currentTimeMillis`, and it exists for the same reason
 * [InstalledVersion] does: a one-line wrapper over a platform fact, so that the decisions made
 * *from* that fact can be exercised without the fact being unavoidable. [UpdateCheckPolicy] got
 * away without one by taking `now` as a parameter, but [UpdateRepository] is the class that has to
 * supply it, and two of the three escapes from a stalled install are decisions about elapsed time
 * that would otherwise need a test to wait three real minutes to reach.
 *
 * `@Inject constructor` with nothing in it, so Hilt builds it with no module and no `@Provides`.
 * Not a `@Singleton`, because there is no state to share.
 *
 * Wall clock and not a monotonic one. `SystemClock.elapsedRealtime` would be immune to the clock
 * jumps [UpdateCheckPolicy] spends a section tolerating, which sounds strictly better and is not:
 * the cadence has to be compared against a timestamp persisted across restarts, and
 * `elapsedRealtime` resets on reboot, so a persisted one would be meaningless. One clock for all of
 * this package's arithmetic, with the jumps handled explicitly where they matter, beats two clocks
 * and a rule about which is which.
 */
class UpdateClock @Inject constructor() {

    fun nowMillis(): Long = System.currentTimeMillis()
}

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

    /**
     * Staged or committed; the platform has it.
     *
     * **Not terminal, and not allowed to look like it.** Reaching this state is the app giving up
     * control, not the app finishing; the two futures it has and the bounded wait that enforces
     * them are at the top of [UpdateRepository].
     */
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
