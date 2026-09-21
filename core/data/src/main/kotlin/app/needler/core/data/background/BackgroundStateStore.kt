package app.needler.core.data.background

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * What the background half remembers between process lifetimes.
 *
 * Every field here exists because the process dies between polls. A `revision` held in a field is a
 * revision that resets to null on the next wake, which turns the cheap poll into a full refresh
 * every fifteen minutes; an "already announced" set held in memory re-announces the same album on
 * every cold start. So it is persisted, and it is persisted **separately from
 * `AppStateStore`**: that store is about things the user did (recent searches, seen pulls, the
 * crate), this one is about what the background machinery has done, and a user-facing "clear
 * history" must never reset a poller's bookkeeping.
 *
 * An interface because the workers are unit-tested with no device.
 */
public interface BackgroundStateStore {

    /** The poller's memory. Read at the start of a run, written at the end of one. */
    public suspend fun pollMemory(): PollMemory

    public suspend fun savePollMemory(memory: PollMemory)

    /**
     * True once the runtime notification permission has been requested at least once.
     *
     * Android stops showing the dialog after two refusals, so the app asks once and then points at
     * system settings instead of training the user to dismiss a prompt.
     */
    public fun observeNotificationPermissionAsked(): Flow<Boolean>

    public suspend fun notificationPermissionAsked(): Boolean

    public suspend fun markNotificationPermissionAsked()

    /**
     * True once the user has placed a pull on this device.
     *
     * The first pull is one of the two moments at which asking for the notification permission
     * makes sense, so it has to be distinguishable from the second.
     */
    public suspend fun firstPullPlaced(): Boolean

    public suspend fun markPullPlaced()

    /** Forgets everything. Called when the saved server changes; the old revisions mean nothing. */
    public suspend fun clear()
}

/** [BackgroundStateStore] over DataStore preferences. */
public class DataStoreBackgroundStateStore(
    private val dataStore: DataStore<Preferences>,
) : BackgroundStateStore {

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    override suspend fun pollMemory(): PollMemory {
        val prefs: Preferences = preferences.first()
        return PollMemory(
            lastRevision = prefs[KEY_REVISION],
            announcedLandedMbids = prefs[KEY_ANNOUNCED].orEmpty(),
            lastFailedCount = prefs[KEY_FAILED_COUNT] ?: 0,
            lastUnseenNewReleaseCount = prefs[KEY_UNSEEN_NEW_RELEASES] ?: 0,
        )
    }

    override suspend fun savePollMemory(memory: PollMemory) {
        dataStore.edit { prefs ->
            val revision: Long? = memory.lastRevision
            if (revision == null) prefs.remove(KEY_REVISION) else prefs[KEY_REVISION] = revision
            prefs[KEY_ANNOUNCED] = memory.announcedLandedMbids
            prefs[KEY_FAILED_COUNT] = memory.lastFailedCount
            prefs[KEY_UNSEEN_NEW_RELEASES] = memory.lastUnseenNewReleaseCount
        }
    }

    override fun observeNotificationPermissionAsked(): Flow<Boolean> =
        preferences.map { it[KEY_PERMISSION_ASKED] ?: false }

    override suspend fun notificationPermissionAsked(): Boolean =
        preferences.first()[KEY_PERMISSION_ASKED] ?: false

    override suspend fun markNotificationPermissionAsked() {
        dataStore.edit { it[KEY_PERMISSION_ASKED] = true }
    }

    override suspend fun firstPullPlaced(): Boolean =
        preferences.first()[KEY_PULL_PLACED] ?: false

    override suspend fun markPullPlaced() {
        dataStore.edit { it[KEY_PULL_PLACED] = true }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    public companion object {

        private const val STORE_NAME: String = "needler_background_state"

        private val KEY_REVISION = longPreferencesKey("activity_revision")
        private val KEY_ANNOUNCED = stringSetPreferencesKey("announced_landed")
        private val KEY_FAILED_COUNT = intPreferencesKey("last_failed_count")
        private val KEY_UNSEEN_NEW_RELEASES = intPreferencesKey("last_unseen_new_releases")
        private val KEY_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")
        private val KEY_PULL_PLACED = booleanPreferencesKey("first_pull_placed")

        public fun createDataStore(context: Context, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope) {
                context.preferencesDataStoreFile(STORE_NAME)
            }

        public fun create(context: Context, scope: CoroutineScope): DataStoreBackgroundStateStore =
            DataStoreBackgroundStateStore(createDataStore(context, scope))
    }
}
