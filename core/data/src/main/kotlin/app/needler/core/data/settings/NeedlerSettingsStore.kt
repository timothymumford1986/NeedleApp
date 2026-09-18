package app.needler.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Every user preference on the Settings screen, in a `DataStore<Preferences>`.
 *
 * **No secrets, ever.** The companion bearer, the app-password, the server URL and any pinned
 * certificate fingerprint live in `EncryptedSharedPreferences` under a Keystore master key - see
 * `app.needler.core.data.security.SecureCredentialStore`. This file is plain, unencrypted and, on a
 * device with backup enabled, would be eligible for it; that is fine for a crossfade length and
 * catastrophic for a credential.
 *
 * ## Reading
 *
 * Each section is exposed as its own Flow, not just the aggregate [settings]: the audio pipeline
 * re-reads on any equaliser change and must not be woken by a notification toggle, and every flow
 * is `distinctUntilChanged` so a write that changes nothing produces no downstream work.
 *
 * A read failure - a corrupt file, a device running out of space mid-write - emits defaults rather
 * than throwing. A settings screen that cannot render because a preference file is damaged would
 * take the whole app with it, and every value here has a safe default.
 *
 * ## Writing
 *
 * Setters are `suspend` and atomic, and each writes one key. Values are sanitised on the way in
 * *and* on the way out: on the way in so a bad value never reaches storage, and on the way out so a
 * value written by a different build cannot reach the audio pipeline.
 */
public class NeedlerSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { error ->
            // DataStore signals a damaged or unreadable file as IOException. Anything else is a
            // programming error and must not be swallowed.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }

    /** Everything at once. Prefer a narrower flow where one exists. */
    public val settings: Flow<NeedlerSettings> = preferences
        .map { it.toSettings() }
        .distinctUntilChanged()

    public val playback: Flow<PlaybackSettings> = preferences
        .map { it.toPlaybackSettings() }
        .distinctUntilChanged()

    public val crossfade: Flow<CrossfadeSettings> = preferences
        .map { it.toCrossfadeSettings() }
        .distinctUntilChanged()

    public val equaliser: Flow<EqualiserSettings> = preferences
        .map { it.toEqualiserSettings() }
        .distinctUntilChanged()

    public val notifications: Flow<NotificationSettings> = preferences
        .map { it.toNotificationSettings() }
        .distinctUntilChanged()

    public val storage: Flow<StorageSettings> = preferences
        .map { it.toStorageSettings() }
        .distinctUntilChanged()

    // ------------------------------------------------------------------ playing

    public suspend fun setGaplessEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.GAPLESS_ENABLED] = enabled }
    }

    public suspend fun setStreamQuality(quality: StreamQuality) {
        dataStore.edit { it[Keys.STREAM_QUALITY] = quality.storageValue }
    }

    /** Applies only while the connection is metered, and only when the server can transcode. */
    public suspend fun setMobileDataStreamQuality(quality: StreamQuality) {
        dataStore.edit { it[Keys.MOBILE_DATA_STREAM_QUALITY] = quality.storageValue }
    }

    public suspend fun setScrobbleEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SCROBBLE_ENABLED] = enabled }
    }

    // ------------------------------------------------------------------ crossfade

    /** Snapped to one of Off, 4, 6 or 12 seconds; anything else becomes Off. */
    public suspend fun setCrossfadeSeconds(seconds: Int) {
        val sanitised: Int = CrossfadeSettings.sanitiseSeconds(seconds)
        dataStore.edit { it[Keys.CROSSFADE_SECONDS] = sanitised }
    }

    public suspend fun setSkipFadeInsideAlbum(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSFADE_SKIP_INSIDE_ALBUM] = enabled }
    }

    public suspend fun setFadeOnSkip(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSFADE_FADE_ON_SKIP] = enabled }
    }

    public suspend fun setFadeOnPause(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSFADE_FADE_ON_PAUSE] = enabled }
    }

    // ------------------------------------------------------------------ equaliser

    public suspend fun setEqualiserEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EQ_ENABLED] = enabled }
    }

    /**
     * Selects a preset and writes the band gains it implies, so the stored bands and the selected
     * chip can never disagree.
     */
    public suspend fun setEqPreset(preset: EqPreset) {
        val bands: List<Int> = EqPresets.bandsFor(preset)
        dataStore.edit { prefs ->
            prefs[Keys.EQ_PRESET] = preset.storageValue
            if (preset != EqPreset.CUSTOM) {
                prefs[Keys.EQ_BANDS] = encodeBands(bands)
            }
        }
    }

    /** Writes all ten bands at once and marks the preset custom. */
    public suspend fun setEqBands(bandGainsDb: List<Int>) {
        val sanitised: List<Int> = EqualiserSettings.sanitiseBands(bandGainsDb)
        dataStore.edit { prefs ->
            prefs[Keys.EQ_BANDS] = encodeBands(sanitised)
            prefs[Keys.EQ_PRESET] = EqPresets.matchingPreset(sanitised).storageValue
        }
    }

    /**
     * Moves one band. Reads the current bands inside the same `edit` block, so two sliders dragged
     * at once cannot write over each other's band.
     */
    public suspend fun setEqBand(index: Int, gainDb: Int) {
        if (index < 0 || index >= EqualiserSettings.BAND_COUNT) return
        dataStore.edit { prefs ->
            val current: List<Int> = decodeBands(prefs[Keys.EQ_BANDS])
            val updated: List<Int> = current.toMutableList().also {
                it[index] = EqualiserSettings.clampGain(gainDb)
            }
            prefs[Keys.EQ_BANDS] = encodeBands(updated)
            prefs[Keys.EQ_PRESET] = EqPresets.matchingPreset(updated).storageValue
        }
    }

    public suspend fun setEqPreampDb(preampDb: Int) {
        dataStore.edit { it[Keys.EQ_PREAMP_DB] = EqualiserSettings.clampGain(preampDb) }
    }

    /** "Reset to flat" on screen 19: flat bands, no preamp, Flat selected. */
    public suspend fun resetEqualiserToFlat() {
        dataStore.edit { prefs ->
            prefs[Keys.EQ_BANDS] = encodeBands(EqualiserSettings.FLAT_BANDS)
            prefs[Keys.EQ_PREAMP_DB] = 0
            prefs[Keys.EQ_PRESET] = EqPreset.FLAT.storageValue
        }
    }

    // ------------------------------------------------------------------ notifications

    public suspend fun setNotifyPullFinished(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFY_PULL_FINISHED] = enabled }
    }

    public suspend fun setNotifyPullFailed(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFY_PULL_FAILED] = enabled }
    }

    public suspend fun setNotifyNewRelease(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFY_NEW_RELEASE] = enabled }
    }

    // ------------------------------------------------------------------ storage

    // There is deliberately no storage-limit setter. The limit is gone: downloads are unlimited
    // and the cached tier is bounded by device free space, which nobody configures.

    public suspend fun setKeepPulledAlbumsOnDevice(enabled: Boolean) {
        dataStore.edit { it[Keys.KEEP_PULLED_ALBUMS] = enabled }
    }

    /** The corrected "Download to device on Wi-Fi only"; see [StorageSettings]. */
    public suspend fun setDownloadToDeviceOnWifiOnly(enabled: Boolean) {
        dataStore.edit { it[Keys.DOWNLOAD_WIFI_ONLY] = enabled }
    }

    /**
     * Clears every preference, returning the app to the defaults.
     *
     * Not part of changing server: quality, EQ, crossfade and notification choices belong to the
     * device and the person, not to the server, so `NeedlerDatabase.clearForServerChange` leaves
     * them alone. This exists for "sign out and forget everything".
     */
    public suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    // ------------------------------------------------------------------ mapping

    private fun Preferences.toSettings(): NeedlerSettings = NeedlerSettings(
        playback = toPlaybackSettings(),
        crossfade = toCrossfadeSettings(),
        equaliser = toEqualiserSettings(),
        notifications = toNotificationSettings(),
        storage = toStorageSettings(),
    )

    private fun Preferences.toPlaybackSettings(): PlaybackSettings = PlaybackSettings(
        gaplessEnabled = this[Keys.GAPLESS_ENABLED] ?: true,
        streamQuality = StreamQuality.fromStorageValue(this[Keys.STREAM_QUALITY]),
        mobileDataStreamQuality = this[Keys.MOBILE_DATA_STREAM_QUALITY]
            ?.let(StreamQuality::fromStorageValue)
            ?: StreamQuality.MP3_320,
        scrobbleEnabled = this[Keys.SCROBBLE_ENABLED] ?: true,
    )

    private fun Preferences.toCrossfadeSettings(): CrossfadeSettings = CrossfadeSettings(
        seconds = CrossfadeSettings.sanitiseSeconds(this[Keys.CROSSFADE_SECONDS] ?: 0),
        skipFadeInsideAlbum = this[Keys.CROSSFADE_SKIP_INSIDE_ALBUM] ?: true,
        fadeOnSkip = this[Keys.CROSSFADE_FADE_ON_SKIP] ?: true,
        fadeOnPause = this[Keys.CROSSFADE_FADE_ON_PAUSE] ?: true,
    )

    private fun Preferences.toEqualiserSettings(): EqualiserSettings = EqualiserSettings(
        enabled = this[Keys.EQ_ENABLED] ?: false,
        preset = EqPreset.fromStorageValue(this[Keys.EQ_PRESET]),
        bandGainsDb = decodeBands(this[Keys.EQ_BANDS]),
        preampDb = EqualiserSettings.clampGain(this[Keys.EQ_PREAMP_DB] ?: 0),
    )

    private fun Preferences.toNotificationSettings(): NotificationSettings = NotificationSettings(
        pullFinished = this[Keys.NOTIFY_PULL_FINISHED] ?: true,
        pullFailed = this[Keys.NOTIFY_PULL_FAILED] ?: true,
        newReleaseFromFollowedArtist = this[Keys.NOTIFY_NEW_RELEASE] ?: true,
    )

    private fun Preferences.toStorageSettings(): StorageSettings = StorageSettings(
        keepPulledAlbumsOnDevice = this[Keys.KEEP_PULLED_ALBUMS] ?: false,
        downloadToDeviceOnWifiOnly = this[Keys.DOWNLOAD_WIFI_ONLY] ?: true,
    )

    /** Ten integers, comma separated. Chosen over ten keys so a band set is written atomically. */
    private fun encodeBands(bands: List<Int>): String =
        EqualiserSettings.sanitiseBands(bands).joinToString(separator = ",")

    private fun decodeBands(encoded: String?): List<Int> {
        if (encoded.isNullOrBlank()) return EqualiserSettings.FLAT_BANDS
        val parsed: List<Int> = encoded.split(',').mapNotNull { it.trim().toIntOrNull() }
        return EqualiserSettings.sanitiseBands(parsed)
    }

    /**
     * Preference keys. The string names are persisted, so they are frozen: renaming one silently
     * resets that setting to its default on every existing install.
     */
    private object Keys {
        val GAPLESS_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("gapless_enabled")
        val STREAM_QUALITY: Preferences.Key<String> = stringPreferencesKey("stream_quality")
        val MOBILE_DATA_STREAM_QUALITY: Preferences.Key<String> =
            stringPreferencesKey("mobile_data_stream_quality")
        val SCROBBLE_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("scrobble_enabled")

        val CROSSFADE_SECONDS: Preferences.Key<Int> = intPreferencesKey("crossfade_seconds")
        val CROSSFADE_SKIP_INSIDE_ALBUM: Preferences.Key<Boolean> =
            booleanPreferencesKey("crossfade_skip_inside_album")
        val CROSSFADE_FADE_ON_SKIP: Preferences.Key<Boolean> =
            booleanPreferencesKey("crossfade_fade_on_skip")
        val CROSSFADE_FADE_ON_PAUSE: Preferences.Key<Boolean> =
            booleanPreferencesKey("crossfade_fade_on_pause")

        val EQ_ENABLED: Preferences.Key<Boolean> = booleanPreferencesKey("eq_enabled")
        val EQ_PRESET: Preferences.Key<String> = stringPreferencesKey("eq_preset")
        val EQ_BANDS: Preferences.Key<String> = stringPreferencesKey("eq_bands")
        val EQ_PREAMP_DB: Preferences.Key<Int> = intPreferencesKey("eq_preamp_db")

        val NOTIFY_PULL_FINISHED: Preferences.Key<Boolean> =
            booleanPreferencesKey("notify_pull_finished")
        val NOTIFY_PULL_FAILED: Preferences.Key<Boolean> = booleanPreferencesKey("notify_pull_failed")
        val NOTIFY_NEW_RELEASE: Preferences.Key<Boolean> = booleanPreferencesKey("notify_new_release")

        // "storage_budget_bytes" was the user-set limit. It is retired, not renamed: the name
        // stays burned so a future setting cannot inherit a stale byte count from an old install.
        val KEEP_PULLED_ALBUMS: Preferences.Key<Boolean> =
            booleanPreferencesKey("keep_pulled_albums_on_device")
        val DOWNLOAD_WIFI_ONLY: Preferences.Key<Boolean> =
            booleanPreferencesKey("download_to_device_on_wifi_only")
    }

    public companion object {
        /** The DataStore file name. Frozen: changing it resets every setting on upgrade. */
        public const val FILE_NAME: String = "needler_settings"

        /**
         * Builds the backing DataStore.
         *
         * [scope] must outlive every reader - an application-scoped one. DI wiring belongs to
         * `:app`, so this stays a plain factory rather than a Hilt module.
         */
        public fun createDataStore(context: Context, scope: CoroutineScope): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { context.preferencesDataStoreFile(FILE_NAME) },
            )

        public fun create(context: Context, scope: CoroutineScope): NeedlerSettingsStore =
            NeedlerSettingsStore(createDataStore(context = context, scope = scope))
    }
}

/**
 * The band gains behind screen 19's five presets.
 *
 * Needler's presets are deliberately not the server's ten (Flat, Rock, Pop, Jazz, Classical, Bass
 * Boost, Treble Boost, Vocal, Electronic, Acoustic): the design pack's five are the better mobile
 * set, and the requirements keep them while claiming the same *bands*, not the same presets.
 *
 * Values are in dB per band, low to high, over the ten frequencies in
 * [EqualiserSettings.BAND_FREQUENCIES_HZ].
 */
public object EqPresets {

    public val FLAT: List<Int> = EqualiserSettings.FLAT_BANDS

    /** Lift below 250 Hz, a touch of cut in the low mids so it does not turn to mud. */
    public val BASS: List<Int> = listOf(6, 5, 4, 2, -1, -1, 0, 0, 1, 1)

    /** Presence for voices: dip the low end, lift 1-4 kHz. */
    public val VOCAL: List<Int> = listOf(-3, -2, 0, 1, 3, 4, 4, 2, 0, -1)

    /** Air and sparkle at the top. */
    public val BRIGHT: List<Int> = listOf(-1, -1, 0, 0, 1, 2, 3, 4, 5, 5)

    /** Gentle smile curve with the extremes rolled off, the way a record sounds. */
    public val VINYL: List<Int> = listOf(2, 3, 2, 0, -1, -1, 0, 1, -2, -4)

    public fun bandsFor(preset: EqPreset): List<Int> = when (preset) {
        EqPreset.FLAT -> FLAT
        EqPreset.BASS -> BASS
        EqPreset.VOCAL -> VOCAL
        EqPreset.BRIGHT -> BRIGHT
        EqPreset.VINYL -> VINYL
        // Custom has no canonical bands: whatever the user set is already stored.
        EqPreset.CUSTOM -> FLAT
    }

    /**
     * Which preset chip to light up for a set of bands, or [EqPreset.CUSTOM] when it matches none.
     * Called after a band is dragged, so moving a slider back onto a preset re-selects that preset.
     */
    public fun matchingPreset(bands: List<Int>): EqPreset {
        val sanitised: List<Int> = EqualiserSettings.sanitiseBands(bands)
        return EqPreset.entries.firstOrNull { preset ->
            preset != EqPreset.CUSTOM && bandsFor(preset) == sanitised
        } ?: EqPreset.CUSTOM
    }
}
