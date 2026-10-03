package app.needler.core.data.background

import app.needler.core.data.local.dao.PinDao
import app.needler.core.data.local.entity.DownloadStateDb
import app.needler.core.data.local.entity.PinEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Re-enqueues the downloads whose jobs the system dropped, once per process start.
 *
 * ## The state that could be entered and not left
 *
 * REQUIREMENTS.md "The download in flight is a badge, not a banner" records the fault this class
 * exists to close, and names the fix: "A retryable transport failure leaves the pin row at
 * `downloading` for `WorkManager` to retry with backoff, and the download job is enqueued `KEEP`.
 * Nothing re-enqueues it when the app next starts. So a row whose job the system dropped - cancelled,
 * or retried to exhaustion - stays `downloading` with nothing advancing it... **The missing piece is a
 * resume sweep at start-up: every pin row at `downloading` or `queued` re-enqueued, which `KEEP` makes
 * idempotent for the jobs that do still exist.**"
 *
 * Process death, a reboot, a force-stop and `WorkManager` giving up at its backoff ceiling all end the
 * same way: a row saying the album is downloading, and no job anywhere that will ever change it.
 * [app.needler.core.domain.repository.PinRepository.retryPinnedDownload] could clear it, but it is a
 * manual action - the user has to guess that the album they are looking at needs a tap on Retry - so
 * it is a cure for the symptom and not for the fault. This is the same family as the updater's
 * permanent "Installing" state: the only thing that could leave the state is the event that can no
 * longer happen.
 *
 * ## Why a blanket re-enqueue rather than asking WorkManager which jobs are dead
 *
 * The obvious alternative is to read `WorkManager.getWorkInfosForUniqueWork` for each pinned album
 * and re-enqueue only the ones whose job is gone. It was rejected, for three reasons:
 *
 *  1. **`KEEP` already is that decision, made atomically.** `ExistingWorkPolicy.KEEP` inserts the new
 *     request only when no *unfinished* work exists under the unique name. A check from here is the
 *     same test run earlier, outside `WorkManager`'s own transaction, and a job that finishes between
 *     the read and the enqueue makes the answer wrong in the direction that leaves the row stuck.
 *  2. **A dead job is not reliably distinguishable from a pruned one.** `WorkManager` prunes finished
 *     work, so the lookup answers either an empty list or a stale `SUCCEEDED` record depending on how
 *     long ago the process died. Both mean "no job is coming", and `KEEP` treats both correctly
 *     without this class having to know which it got.
 *  3. It costs one `ListenableFuture` round trip per pinned album to learn what one enqueue already
 *     knows.
 *
 * So the sweep is blanket and the idempotence is `KEEP`'s: a job that is genuinely still alive is
 * untouched - the same guarantee that makes a second tap on a downloading album join the job rather
 * than restart it - and a row whose job is gone gets a new one.
 *
 * ## Which rows qualify, and which deliberately do not
 *
 * [DownloadStateDb.RESUMABLE_DB_VALUES]: `queued` and `downloading`, and nothing else. `partial` and
 * `failed` are what the downloader *leaves behind* when it stops, which is why
 * [app.needler.core.domain.model.OfflineDownloadState.isInFlight] excludes them too. Treating them as
 * progress is how the un-exitable state was built in the first place, and an album whose server has no
 * file for some tracks rests at `partial` permanently by decision - `AlbumDownloadTest` pins that - so
 * a sweep that included `partial` would re-download that album's whole track list on every single app
 * start, for ever, and still never reach `complete`.
 *
 * ## Why the Wi-Fi setting cannot be lost here
 *
 * The sweep does not build a work request. It calls
 * [BackgroundWorkScheduler.scheduleAlbumDownload], which is the one place in the app that enqueues a
 * download and which reads "Download to device on Wi-Fi only" at enqueue time to choose between
 * `NetworkType.UNMETERED` and `NetworkType.CONNECTED`. A sweep with its own request builder would be a
 * second copy of that policy, and the copy that forgot the constraint would download gigabytes over
 * mobile data against an explicit setting - REQUIREMENTS.md "Battery and data" point 4, "Downloads to
 * device default to Wi-Fi only".
 *
 * ## Why one shot at start-up and never a poll
 *
 * REQUIREMENTS.md "Battery and data" point 1 allows "no background polling... beyond the six-hourly
 * sync", so a watchdog that periodically looked for stranded rows is not available even though it
 * would catch a job dropped mid-session. It is also not needed: the states this fixes are entered by
 * the process stopping, so the next process start is the first moment at which anything could have
 * gone wrong and the first moment at which a fix can be applied.
 *
 * The cost is one indexed read of `pin` on `index_pin_download_state`, and nothing at all when it
 * comes back empty. [start] launches and returns, so the read happens off the main thread after
 * `onCreate` has finished and REQUIREMENTS.md "Performance budgets" cold-start budget is unaffected.
 */
@Singleton
public class DownloadResumeCoordinator @Inject constructor(
    private val pinDao: PinDao,
    /**
     * Deliberately a [Provider] rather than the scheduler itself.
     *
     * Resolving [BackgroundWorkScheduler] constructs `WorkManager`, and `WorkManager.getInstance` is
     * what triggers on-demand initialisation - which reads `Configuration.Provider` off the
     * `Application`, whose getter in turn reads an `@Inject lateinit` `HiltWorkerFactory` field. This
     * class is field-injected into that same `Application`, so taking the scheduler directly would
     * resolve `WorkManager` *during* member injection, and the order in which Dagger assigns injected
     * fields is not part of its contract: the configuration getter could run before the field it
     * reads has been set. A [Provider] moves that first `getInstance` into the launched coroutine,
     * which cannot start before `onCreate` has returned.
     */
    private val scheduler: Provider<BackgroundWorkScheduler>,
) {

    /**
     * Sweeps once. Safe to call once, from `Application.onCreate`.
     *
     * [scope] must outlive every screen - an application scope, not a view model's - for the reason
     * `WidgetRefreshCoordinator.start` gives: the work this starts is work the user is not looking at,
     * and a scope tied to a screen would be cancelled either before the sweep ran or while the
     * enqueues were still going in.
     */
    public fun start(scope: CoroutineScope) {
        scope.launch { resumeInterruptedDownloads() }
    }

    /**
     * Re-enqueues every pin row the downloader still owes work on, and answers which ones.
     *
     * The return value is for tests and for a future diagnostics line; nothing in the app branches on
     * it. An empty result is the normal case and costs one indexed query.
     */
    public suspend fun resumeInterruptedDownloads(): List<String> {
        val stranded: List<PinEntity> = pinDao.getPinsInState(DownloadStateDb.RESUMABLE_DB_VALUES)
        if (stranded.isEmpty()) return emptyList()
        // Resolved here rather than held as a field: see the constructor note.
        val work: BackgroundWorkScheduler = scheduler.get()
        for (pin in stranded) {
            work.scheduleAlbumDownload(pin.releaseGroupMbid)
        }
        return stranded.map { it.releaseGroupMbid }
    }
}
