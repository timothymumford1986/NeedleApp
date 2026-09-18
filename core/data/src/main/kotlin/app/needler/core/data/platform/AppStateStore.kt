package app.needler.core.data.platform

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.needler.core.data.local.TrackKeyDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Small device-local state that has no business in the mirror.
 *
 * Three things live here, and they have nothing in common except that they are per-device, tiny, and
 * would be noise as tables: the recent-search list behind the empty search state, the set of
 * completed pulls the user has already seen (which is half of the Pulls badge), and the persisted
 * crate - the restore point the player service reads at cold start.
 *
 * It is an interface because every repository that touches it is unit-tested with no device.
 */
public interface AppStateStore {

    public fun observeRecentQueries(): Flow<List<String>>

    public suspend fun recordRecentQuery(query: String)

    public suspend fun clearRecentQueries()

    /**
     * Completed pulls the user has already been shown.
     *
     * The badge is `active + unseen completions`, and it is the reliable channel - notifications are
     * delayed or dropped by Doze and OEM battery managers, so the badge has to be correct whenever
     * the app is opened, which means the seen set survives process death.
     */
    public fun observeSeenPulls(): Flow<Set<String>>

    public suspend fun markPullsSeen(releaseGroupMbids: Set<String>)

    /** The crate as last persisted: a restore point, never the live queue. */
    public fun observePersistedQueue(): Flow<PersistedQueue>

    public suspend fun savePersistedQueue(queue: PersistedQueue)

    public companion object {
        public const val MAX_RECENT_QUERIES: Int = 20
    }
}

/**
 * The crate reduced to what survives a restart: stable track keys and the playing position.
 *
 * Track keys, not file ids - the same rule as everywhere else. A queue restored after a server-side
 * quality upgrade resolves each key against the mirror and picks up the new file, which is the whole
 * reason identity is (release group, disc, track).
 */
public data class PersistedQueue(
    val keys: List<TrackKeyDb> = emptyList(),
    val currentIndex: Int? = null,
) {
    public companion object {
        public val Empty: PersistedQueue = PersistedQueue()
    }
}

/** [AppStateStore] over DataStore preferences. */
public class DataStoreAppStateStore(
    private val dataStore: DataStore<Preferences>,
) : AppStateStore {

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    override fun observeRecentQueries(): Flow<List<String>> = preferences
        .map { decodeList(it[KEY_RECENT_QUERIES]) }
        .distinctUntilChanged()

    override suspend fun recordRecentQuery(query: String) {
        val trimmed: String = query.trim()
        if (trimmed.isEmpty()) return
        dataStore.edit { prefs ->
            val existing: List<String> = decodeList(prefs[KEY_RECENT_QUERIES])
            val updated: List<String> = (listOf(trimmed) + existing.filter { !it.equals(trimmed, true) })
                .take(AppStateStore.MAX_RECENT_QUERIES)
            prefs[KEY_RECENT_QUERIES] = encodeList(updated)
        }
    }

    override suspend fun clearRecentQueries() {
        dataStore.edit { it.remove(KEY_RECENT_QUERIES) }
    }

    override fun observeSeenPulls(): Flow<Set<String>> = preferences
        .map { it[KEY_SEEN_PULLS].orEmpty() }
        .distinctUntilChanged()

    override suspend fun markPullsSeen(releaseGroupMbids: Set<String>) {
        if (releaseGroupMbids.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[KEY_SEEN_PULLS] = prefs[KEY_SEEN_PULLS].orEmpty() + releaseGroupMbids
        }
    }

    override fun observePersistedQueue(): Flow<PersistedQueue> = preferences
        .map { prefs ->
            PersistedQueue(
                keys = decodeList(prefs[KEY_QUEUE_KEYS]).mapNotNull(TrackKeyDb::parse),
                currentIndex = prefs[KEY_QUEUE_INDEX]?.takeIf { it >= 0 },
            )
        }
        .distinctUntilChanged()

    override suspend fun savePersistedQueue(queue: PersistedQueue) {
        dataStore.edit { prefs ->
            prefs[KEY_QUEUE_KEYS] = encodeList(queue.keys.map { it.canonical })
            prefs[KEY_QUEUE_INDEX] = queue.currentIndex ?: -1
        }
    }

    private fun encodeList(values: List<String>): String = values.joinToString(SEPARATOR)

    private fun decodeList(stored: String?): List<String> {
        val raw: String = stored.orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    public companion object {
        // ASCII unit separator. A query the user typed cannot contain it, so the joined
        // string needs no escaping.
        private const val SEPARATOR: String = "\u001F"
        private const val STORE_NAME: String = "needler_app_state"

        private val KEY_RECENT_QUERIES = stringPreferencesKey("recent_queries")
        private val KEY_SEEN_PULLS = stringSetPreferencesKey("seen_pulls")
        private val KEY_QUEUE_KEYS = stringPreferencesKey("queue_keys")
        private val KEY_QUEUE_INDEX = intPreferencesKey("queue_index")

        public fun createDataStore(context: Context, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope) {
                context.preferencesDataStoreFile(STORE_NAME)
            }

        public fun create(context: Context, scope: CoroutineScope): DataStoreAppStateStore =
            DataStoreAppStateStore(createDataStore(context, scope))
    }
}
