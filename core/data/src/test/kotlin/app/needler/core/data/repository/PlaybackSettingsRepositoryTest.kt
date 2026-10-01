package app.needler.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.needler.core.data.fake.FakeAppStateStore
import app.needler.core.data.fake.FakeNetworkMonitor
import app.needler.core.data.fake.FakeSubsonicApi
import app.needler.core.data.fake.FakeTrackDao
import app.needler.core.data.fake.FakeV1Api
import app.needler.core.data.fake.FakeWriteQueueDao
import app.needler.core.data.local.dao.StreamOverrideDao
import app.needler.core.data.local.entity.StreamOverrideEntity
import app.needler.core.data.local.entity.StreamOverrideScopeDb
import app.needler.core.data.settings.NeedlerSettingsStore
import app.needler.core.data.settings.StreamQuality
import app.needler.core.data.writequeue.WriteQueue
import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.CrossfadeSettings
import app.needler.core.domain.model.PlaybackPreferences
import app.needler.core.domain.model.PlaybackSpeed
import app.needler.core.domain.model.SleepTimer
import app.needler.core.domain.model.StreamOverrideScope
import app.needler.core.domain.model.StreamRung
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playback speed and the sleep timer: session state that is published and never persisted.
 *
 * Both setters were `= Unit` here, on the reasoning that the values are "applied through the
 * controller". Half of that held - speed does reach the session directly - and the sleep timer's half
 * did not: nothing in the Media3 session implements a timer, so the player service evaluates it against
 * the flow this repository emits, and a no-op setter meant that flow never changed. The timer was
 * modelled, decided, tested, acted upon by `PlaybackCoordinator`, and impossible to turn on.
 *
 * These tests pin the two halves of the fix that matter: the value **arrives on
 * `observePlaybackPreferences`**, which is the only flow the player service collects, and it is **not
 * written to `DataStore`**, because a timer that survives a restart is armed and already elapsed by
 * morning - which would stop the first track of the day for no visible reason.
 */
public class PlaybackSettingsRepositoryTest {

    private val dataStore = InMemoryPreferences()
    private val settingsStore = NeedlerSettingsStore(dataStore)
    private val appStateStore = FakeAppStateStore()
    private val trackDao = FakeTrackDao()
    private val writeQueue = WriteQueue(FakeWriteQueueDao()) { NOW }
    private val network = FakeNetworkMonitor()
    private val subsonic = FakeSubsonicApi()
    private val v1 = FakeV1Api()

    private val overrides = InMemoryStreamOverrideDao()

    private val repository = DefaultPlaybackSettingsRepository(
        settingsStore = settingsStore,
        appStateStore = appStateStore,
        trackDao = trackDao,
        streamOverrideDao = overrides,
        writeQueue = writeQueue,
        networkMonitor = network,
        subsonic = subsonic,
        v1 = v1,
        now = { NOW },
    )

    // ------------------------------------------------------------- the sleep timer

    @Test
    public fun `an unset sleep timer is off`(): Unit = runTest {
        assertEquals(SleepTimer.Off, preferences().sleepTimer)
    }

    @Test
    public fun `arming the sleep timer reaches the flow the player service collects`(): Unit = runTest {
        repository.setSleepTimer(SleepTimer.EndOfTrack)

        assertEquals(SleepTimer.EndOfTrack, preferences().sleepTimer)
    }

    @Test
    public fun `a timed stop arrives as the instant it was armed with`(): Unit = runTest {
        val at = SleepTimer.At(Instant.fromEpochMilliseconds(NOW + 30 * 60_000L))

        repository.setSleepTimer(at)

        assertEquals(at, preferences().sleepTimer)
    }

    @Test
    public fun `disarming it returns the flow to off`(): Unit = runTest {
        repository.setSleepTimer(SleepTimer.EndOfTrack)
        repository.setSleepTimer(SleepTimer.Off)

        assertEquals(SleepTimer.Off, preferences().sleepTimer)
    }

    @Test
    public fun `the sleep timer is never written to DataStore`(): Unit = runTest {
        repository.setSleepTimer(SleepTimer.At(Instant.fromEpochMilliseconds(NOW + 60_000L)))

        // Nothing at all was stored: a timer that survives a restart is armed and already elapsed by
        // morning, so the first track of the day would stop itself.
        assertTrue(
            "the sleep timer must not be persisted",
            dataStore.data.first().asMap().isEmpty(),
        )
    }

    // ------------------------------------------------------------- playback speed

    @Test
    public fun `speed is published so the coordinator does not reset it`(): Unit = runTest {
        // PlaybackCoordinator re-applies preferences.speed on every emission. With a hard-coded Normal on
        // this flow, any unrelated settings change - arming the sleep timer among them - dropped playback
        // back to 1x.
        repository.setPlaybackSpeed(PlaybackSpeed(1.5f))

        assertEquals(PlaybackSpeed(1.5f), preferences().speed)
    }

    @Test
    public fun `speed is never written to DataStore either`(): Unit = runTest {
        repository.setPlaybackSpeed(PlaybackSpeed(0.5f))

        assertTrue("the speed must not be persisted", dataStore.data.first().asMap().isEmpty())
    }

    // ------------------------------------------------- the settings around them

    @Test
    public fun `a persisted setting and a session value ride the same flow`(): Unit = runTest {
        repository.setCrossfadeSettings(CrossfadeSettings(duration = CrossfadeDuration.SIX_SECONDS))
        repository.setSleepTimer(SleepTimer.EndOfTrack)

        val preferences: PlaybackPreferences = preferences()
        assertEquals(CrossfadeDuration.SIX_SECONDS, preferences.crossfade.duration)
        assertEquals(SleepTimer.EndOfTrack, preferences.sleepTimer)
        // And the crossfade did persist, which is the difference being drawn here.
        assertTrue(dataStore.data.first().asMap().isNotEmpty())
    }

    // ------------------------------------------- the two rungs, and the install that had a toggle

    /**
     * The migration, in both directions.
     *
     * "Stream MP3 320 on mobile data" was one switch over two stored keys. The switch is gone and both
     * keys are now pickers, so the thing that can hurt a real user is an existing install reading back
     * as something they did not choose. There are exactly two states it can be in, and the *off* one is
     * the one at risk: it is only distinguishable from "never touched" because the old Settings screen
     * wrote `mobile_data_stream_quality = original` explicitly when the switch went off. If that write
     * were ever dropped as redundant, every install that had turned transcoding off would silently turn
     * it back on.
     */
    @Test
    public fun `an install whose metered toggle was on reads as original on wifi and mp3 320 on data`(): Unit =
        runTest {
            // Exactly what the retired switch wrote when it was turned on.
            settingsStore.setStreamQuality(StreamQuality.ORIGINAL)
            settingsStore.setMobileDataStreamQuality(StreamQuality.MP3_320)

            val preferences: PlaybackPreferences = preferences()
            assertEquals(StreamRung.ORIGINAL, preferences.wifiQuality)
            assertEquals(StreamRung.MP3_320, preferences.dataQuality)
        }

    @Test
    public fun `an install whose metered toggle was off reads as original on both`(): Unit = runTest {
        settingsStore.setStreamQuality(StreamQuality.ORIGINAL)
        settingsStore.setMobileDataStreamQuality(StreamQuality.ORIGINAL)

        val preferences: PlaybackPreferences = preferences()
        assertEquals(StreamRung.ORIGINAL, preferences.wifiQuality)
        assertEquals(StreamRung.ORIGINAL, preferences.dataQuality)
    }

    @Test
    public fun `a fresh install reads as original on wifi and mp3 320 on data`(): Unit = runTest {
        // Nothing written at all: the store's own defaults, which are the same pair the toggle wrote.
        val preferences: PlaybackPreferences = preferences()
        assertEquals(StreamRung.ORIGINAL, preferences.wifiQuality)
        assertEquals(StreamRung.MP3_320, preferences.dataQuality)
    }

    @Test
    public fun `each rung setter writes only the key it names`(): Unit = runTest {
        // The bug the old two-valued setter had: writing one rung moved the other. The Settings screen
        // carried a documented second write to work around it.
        repository.setDataStreamRung(StreamRung.OPUS_96)

        assertEquals(StreamRung.ORIGINAL, preferences().wifiQuality)
        assertEquals(StreamRung.OPUS_96, preferences().dataQuality)

        repository.setWifiStreamRung(StreamRung.MP3_256)

        assertEquals(StreamRung.MP3_256, preferences().wifiQuality)
        assertEquals(StreamRung.OPUS_96, preferences().dataQuality)
    }

    @Test
    public fun `turning a data rung back to original sticks`(): Unit = runTest {
        repository.setDataStreamRung(StreamRung.MP3_128)
        repository.setDataStreamRung(StreamRung.ORIGINAL)

        assertEquals(StreamRung.ORIGINAL, preferences().dataQuality)
    }

    @Test
    public fun `the new opus rungs survive a round trip through storage`(): Unit = runTest {
        // Storage values are frozen strings, so a rung that cannot be read back is a rung that
        // silently resets to Original on the next launch.
        StreamRung.entries.forEach { rung ->
            repository.setDataStreamRung(rung)
            assertEquals("rung " + rung, rung, preferences().dataQuality)
        }
    }

    // ------------------------------------------------------------- per-item overrides

    @Test
    public fun `an item with no override reads null rather than a default`(): Unit = runTest {
        // Null is what makes the precedence chain work: a zero value would be indistinguishable from
        // "the user asked for Original here", which is a real and different choice.
        assertNull(repository.getStreamOverride(StreamOverrideScope.ALBUM, ALBUM_ID))
    }

    @Test
    public fun `an override round trips and is readable at its own scope only`(): Unit = runTest {
        repository.setStreamOverride(StreamOverrideScope.ALBUM, ALBUM_ID, StreamRung.ORIGINAL)

        assertEquals(
            StreamRung.ORIGINAL,
            repository.getStreamOverride(StreamOverrideScope.ALBUM, ALBUM_ID),
        )
        assertNull(repository.getStreamOverride(StreamOverrideScope.TRACK, ALBUM_ID))
    }

    @Test
    public fun `setting an override twice replaces it rather than adding a second`(): Unit = runTest {
        repository.setStreamOverride(StreamOverrideScope.TRACK, TRACK_ID, StreamRung.MP3_128)
        repository.setStreamOverride(StreamOverrideScope.TRACK, TRACK_ID, StreamRung.OPUS_192)

        assertEquals(
            StreamRung.OPUS_192,
            repository.getStreamOverride(StreamOverrideScope.TRACK, TRACK_ID),
        )
    }

    @Test
    public fun `clearing an override returns the item to null`(): Unit = runTest {
        repository.setStreamOverride(StreamOverrideScope.TRACK, TRACK_ID, StreamRung.MP3_128)
        repository.clearStreamOverride(StreamOverrideScope.TRACK, TRACK_ID)

        assertNull(repository.getStreamOverride(StreamOverrideScope.TRACK, TRACK_ID))
    }

    @Test
    public fun `an override is observable, so a screen redraws when it changes`(): Unit = runTest {
        val observed: Flow<StreamRung?> =
            repository.observeStreamOverride(StreamOverrideScope.ALBUM, ALBUM_ID)
        assertNull(observed.first())

        repository.setStreamOverride(StreamOverrideScope.ALBUM, ALBUM_ID, StreamRung.MP3_192)

        assertEquals(StreamRung.MP3_192, observed.first())
    }

    private suspend fun preferences(): PlaybackPreferences =
        repository.observePlaybackPreferences().first()

    private companion object {
        const val NOW: Long = 1_700_000_000_000L
        const val ALBUM_ID: String = "3f2a1d90-0000-4000-8000-000000000001"
        const val TRACK_ID: String = "3f2a1d90-0000-4000-8000-000000000001/1/7"
    }
}

/**
 * A `stream_override` table with no database behind it.
 *
 * In this file rather than in the shared fakes because nothing else needs it yet - the same reasoning
 * as [InMemoryPreferences] below. It keys on the pair the real table's composite primary key uses, so
 * "one row per item per scope" is enforced here as it is there.
 */
private class InMemoryStreamOverrideDao : StreamOverrideDao {

    private val rows: MutableStateFlow<Map<Pair<StreamOverrideScopeDb, String>, StreamOverrideEntity>> =
        MutableStateFlow(emptyMap())

    override suspend fun get(
        scope: StreamOverrideScopeDb,
        itemId: String,
    ): StreamOverrideEntity? = rows.value[scope to itemId]

    override fun observe(
        scope: StreamOverrideScopeDb,
        itemId: String,
    ): Flow<StreamOverrideEntity?> = rows.map { it[scope to itemId] }

    override suspend fun upsert(override: StreamOverrideEntity) {
        rows.value = rows.value + ((override.scope to override.itemId) to override)
    }

    override suspend fun delete(scope: StreamOverrideScopeDb, itemId: String) {
        rows.value = rows.value - (scope to itemId)
    }

    override suspend fun clear() {
        rows.value = emptyMap()
    }
}

/**
 * A `DataStore<Preferences>` with no file behind it.
 *
 * `NeedlerSettingsStore` is a thin wrapper over this interface, so faking the interface rather than the
 * wrapper keeps the store's own key mapping in the test - the crossfade assertion above goes through the
 * real `Keys`, not a stub. It lives in this file rather than in the shared fakes because nothing else
 * needs it yet.
 */
private class InMemoryPreferences : DataStore<Preferences> {

    private val state: MutableStateFlow<Preferences> = MutableStateFlow(emptyPreferences())

    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences {
        val updated: Preferences = transform(state.value)
        state.value = updated
        return updated
    }
}
