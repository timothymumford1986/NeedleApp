package app.needler.core.data.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.needler.core.data.local.dao.SyncStateDao
import app.needler.core.data.local.entity.SyncStateEntity
import app.needler.core.domain.model.FullSyncReason
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.SyncReport
import app.needler.core.domain.repository.SyncRepository
import app.needler.core.network.CredentialProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Syncs the mirror when something other than a screen says it should.
 *
 * Before this, the only thing that ever triggered a sync was the Library screen's own refresh, so a
 * fresh install showed an empty library until the user happened to open that screen - and signing
 * in to a different server left the previous server's mirror in place, with its `file_id` values
 * and playlist ids describing music that is not there.
 *
 * The job re-derives what is needed rather than trusting the input, which matters because the
 * request may have been queued minutes ago and a full sync is expensive. The decision itself is
 * [SyncDecision], which is pure and tested.
 */
@HiltWorker
public class LibrarySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted parameters: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val syncStateDao: SyncStateDao,
    private val credentials: CredentialProvider,
    private val backgroundState: BackgroundStateStore,
) : CoroutineWorker(appContext, parameters) {

    override suspend fun doWork(): Result {
        val requested: SyncTrigger = SyncTrigger.entries
            .firstOrNull { it.name == inputData.getString(KEY_TRIGGER) }
            ?: SyncTrigger.DELTA

        val state: SyncStateEntity? = syncStateDao.getSyncState()
        val currentIdentity: String? = credentials.serverUrl()?.baseUrl

        val decided: SyncTrigger = SyncDecision.decide(
            storedIdentity = state?.serverIdentity,
            currentIdentity = currentIdentity,
            lastFullSyncAt = state?.lastFullSyncAt,
            lastDeltaSyncAt = state?.lastDeltaSyncAt,
            nowMillis = System.currentTimeMillis(),
            staleAfterMillis = SyncRepository.DefaultStaleAfter.inWholeMilliseconds,
        )

        // A caller that asked for a full sync gets one even when the decision says a delta would
        // do: "Rebuild the library" on the diagnostics screen must not be quietly downgraded. The
        // reverse is not true - a queued delta on a database that turns out to belong to another
        // server is upgraded, because a delta against the wrong mirror is worse than useless.
        val trigger: SyncTrigger = when {
            requested.isFull && decided == SyncTrigger.NONE -> requested
            requested.isFull && !decided.isFull -> requested
            else -> decided
        }

        val outcome: Outcome<SyncReport> = when (trigger) {
            SyncTrigger.NONE -> return Result.success()
            SyncTrigger.DELTA -> syncRepository.deltaSync()
            SyncTrigger.FULL_FIRST_CONNECT -> syncRepository.fullSync(FullSyncReason.FIRST_CONNECT)
            SyncTrigger.FULL_IDENTITY_CHANGED -> {
                // The mirror, the audio cache, the pins and the playlist ids go first, in one
                // transaction, and the full sync is what rebuilds them.
                syncRepository.resetForServerIdentityChange()
                backgroundState.clear()
                syncRepository.fullSync(FullSyncReason.SERVER_IDENTITY_CHANGED)
            }
        }

        return when (outcome) {
            is Outcome.Success -> Result.success()
            is Outcome.Failure -> if (outcome.error.isRetryable) Result.retry() else Result.failure()
        }
    }

    public companion object {
        public const val KEY_TRIGGER: String = "trigger"

        /** Unique: two syncs at once would interleave their writes and race the revision update. */
        public const val WORK_NAME: String = "needler-sync"

        /** The staleness window the foreground trigger uses, exposed so the app can share it. */
        public val STALE_AFTER: kotlin.time.Duration =
            SyncRepository.DefaultStaleAfter.inWholeMilliseconds.milliseconds
    }
}
