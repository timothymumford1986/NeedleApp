package app.needler.core.data.fake

import app.needler.core.data.platform.AppStateStore
import app.needler.core.data.platform.NetworkMonitor
import app.needler.core.data.platform.PersistedQueue
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NetworkStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** In-memory device-local state. */
public class FakeAppStateStore : AppStateStore {

    private val recentQueries: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    private val seenPulls: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())
    private val queue: MutableStateFlow<PersistedQueue> = MutableStateFlow(PersistedQueue.Empty)

    override fun observeRecentQueries(): Flow<List<String>> = recentQueries.asStateFlow()

    override suspend fun recordRecentQuery(query: String) {
        val trimmed: String = query.trim()
        if (trimmed.isEmpty()) return
        recentQueries.value = (listOf(trimmed) + recentQueries.value.filter { !it.equals(trimmed, true) })
            .take(AppStateStore.MAX_RECENT_QUERIES)
    }

    override suspend fun clearRecentQueries() {
        recentQueries.value = emptyList()
    }

    override fun observeSeenPulls(): Flow<Set<String>> = seenPulls.asStateFlow()

    override suspend fun markPullsSeen(releaseGroupMbids: Set<String>) {
        seenPulls.value = seenPulls.value + releaseGroupMbids
    }

    override fun observePersistedQueue(): Flow<PersistedQueue> = queue.asStateFlow()

    override suspend fun savePersistedQueue(queue: PersistedQueue) {
        this.queue.value = queue
    }
}

/**
 * Network conditions a test can move.
 *
 * Offline is a first-class state rather than an error, so most repository tests are about which of
 * the two branches a write takes - straight to the server, or into the journal.
 */
public class FakeNetworkMonitor(
    initial: ConnectivityState = ConnectivityState.Unmetered,
) : NetworkMonitor {

    public val state: MutableStateFlow<ConnectivityState> = MutableStateFlow(initial)

    override fun observe(): Flow<ConnectivityState> = state.asStateFlow()

    override fun current(): ConnectivityState = state.value

    public fun goOffline() {
        state.value = ConnectivityState.Offline
    }

    public fun goMetered() {
        state.value = ConnectivityState(NetworkStatus.METERED)
    }
}
