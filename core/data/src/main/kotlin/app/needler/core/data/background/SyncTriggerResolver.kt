package app.needler.core.data.background

import app.needler.core.data.local.dao.SyncStateDao
import app.needler.core.data.local.entity.SyncStateEntity
import app.needler.core.domain.repository.SyncRepository
import app.needler.core.network.CredentialProvider

/**
 * Answers "does a sync need to run, and how big a one" from the two pieces of state that decide it.
 *
 * The identity comparison is the load-bearing half. `sync_state.server_identity` holds
 * `ServerUrl.baseUrl`, and so does the credential store, deliberately - the two are written from the
 * same rendering so that this comparison cannot disagree with itself over a trailing slash or an
 * assumed port.
 *
 * The decision itself is [SyncDecision], which is pure; this class only reads.
 */
public class SyncTriggerResolver(
    private val syncStateDao: SyncStateDao,
    private val credentials: CredentialProvider,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * True when a server has been saved, so background work has something to talk to.
     *
     * Worth a check before anything is enqueued: on a fresh install the Connect screen owns the
     * app, and a poller scheduled before onboarding would wake every six hours to fail at building
     * a URL it has no host for.
     */
    public fun hasServer(): Boolean = credentials.serverUrl() != null

    /**
     * The trigger for right now.
     *
     * Called when the app comes to the foreground and when a server is first connected, which are
     * the two moments REQUIREMENTS.md's sync table names that are not a screen's own refresh.
     */
    public suspend fun resolve(
        staleAfterMillis: Long = SyncRepository.DefaultStaleAfter.inWholeMilliseconds,
    ): SyncTrigger {
        val state: SyncStateEntity? = syncStateDao.getSyncState()
        return SyncDecision.decide(
            storedIdentity = state?.serverIdentity,
            currentIdentity = credentials.serverUrl()?.baseUrl,
            lastFullSyncAt = state?.lastFullSyncAt,
            lastDeltaSyncAt = state?.lastDeltaSyncAt,
            nowMillis = nowMillis(),
            staleAfterMillis = staleAfterMillis,
        )
    }
}
