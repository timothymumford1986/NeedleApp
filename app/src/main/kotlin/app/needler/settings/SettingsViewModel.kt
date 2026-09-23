// See SettingsFormat.kt for why this file opts in: every Instant that reaches this screen from
// :core:domain is kotlinx.datetime.Instant, which is a deprecated typealias for the stdlib one.
@file:OptIn(ExperimentalTime::class)

package app.needler.settings

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.data.settings.NeedlerSettingsStore
import app.needler.core.data.settings.NotificationSettings
import app.needler.core.data.settings.StreamQuality
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EvictionReport
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlaybackPreferences
import app.needler.core.domain.model.RemovedDownload
import app.needler.core.domain.model.ScrobblePreferences
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.ServerIdentity
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.StoragePreferences
import app.needler.core.domain.model.StorageUsage
import app.needler.core.domain.model.StreamQualityPreference
import app.needler.core.domain.model.SyncReport
import app.needler.core.domain.model.SyncState
import app.needler.core.domain.model.User
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Settings screen - `design/html/12-Settings.html`, with REQUIREMENTS.md's corrections.
 *
 * REQUIREMENTS.md "Architecture > Modules" puts the Settings screen in `:app` rather than in a
 * feature module, and this ViewModel is why that is the right call: it is the only screen in the
 * product that needs four repositories at once - the session, sync, playback preferences and the
 * on-device audio store - plus one `:core:data` type directly. A feature module is not allowed to
 * see any of that.
 *
 * ## Why `NeedlerSettingsStore` is injected as well as the repositories
 *
 * Two of the things screen 12 draws have no domain repository behind them, and inventing one in
 * `:core:domain` is not this agent's to do:
 *
 *  1. **The three notification switches.** Nothing in `:core:domain` exposes them; they live only
 *     in the preference store, which is also where `BackgroundWorkScheduler` reads them from. Going
 *     through the store is therefore reading the same value the background half obeys, not a
 *     parallel copy of it.
 *  2. **Turning the metered transcode back off.** `PlaybackSettingsRepository.setStreamQuality`
 *     cannot express it. Writing `ORIGINAL` sets the *unmetered* quality and leaves
 *     `mobile_data_stream_quality` at its `MP3_320` default, and the repository then reads the pair
 *     back as `MP3_320_ON_METERED` again - so the preference is write-once through the domain
 *     interface. This is a bug in `:core:data`, recorded in the handover notes; until it is fixed,
 *     this screen calls the repository *and* clears the metered quality through the store, so the
 *     switch works in both directions. Both writes land in the same `DataStore`, so there is no
 *     second source of truth, only a second door into the one that exists.
 *
 * `:app` is the only module allowed to see `:core:data` at all, which is what makes this legal
 * here and illegal anywhere else.
 *
 * ## Why every section is its own flow
 *
 * The screen is one list, but nothing on it shares a source: the server block comes from the
 * session and sync state, the playing block from the preference store and the negotiated
 * capabilities, storage from the cache index. Combining them per section and then combining the
 * sections keeps each `combine` inside the five-argument overload without an array of `Any?`, and
 * means a storage figure changing does not re-derive the scrobble label.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val session: SessionRepository,
    private val sync: SyncRepository,
    private val playbackSettings: PlaybackSettingsRepository,
    private val pins: PinRepository,
    private val settingsStore: NeedlerSettingsStore,
) : ViewModel() {

    /**
     * Read once, at construction.
     *
     * The installed version cannot change under a running process - an update restarts the app - so
     * there is nothing to observe, and `PackageManager` is a binder call that has no business
     * running on every recomposition.
     */
    private val about: AboutSectionState = readAppVersion(context)

    private val transient: MutableStateFlow<Transient> = MutableStateFlow(Transient())

    private val serverSection: Flow<ServerSectionState> = combine(
        session.observeSession(),
        session.observeConnectivity(),
        sync.observeSyncState(),
    ) { sessionState: SessionState, connectivity: ConnectivityState, syncState: SyncState ->
        val renderedAt: Instant = now()
        ServerSectionState(
            host = hostOf(serverOf(sessionState)),
            username = userOf(sessionState)?.username,
            // The delta sync is the one that runs; the full sync is the fallback for a mirror that
            // has only ever been built once.
            lastSyncedAt = syncState.lastDeltaSyncAt ?: syncState.lastFullSyncAt,
            syncing = syncState.isSyncing,
            offline = !connectivity.isOnline,
            sessionNotice = sessionNoticeFor(sessionState, renderedAt),
            renderedAt = renderedAt,
        )
    }

    private val playingSection: Flow<PlayingSectionState> = combine(
        playbackSettings.observePlaybackPreferences(),
        playbackSettings.observeScrobblePreferences(),
        session.observeCapabilities(),
    ) { preferences: PlaybackPreferences, scrobble: ScrobblePreferences, capabilities: ServerCapabilities? ->
        PlayingSectionState(
            gaplessEnabled = preferences.gaplessEnabled,
            crossfade = preferences.crossfade.duration,
            equaliserEnabled = preferences.eq.isEnabled,
            equaliserPreset = preferences.eq.preset,
            scrobblingEnabled = scrobble.reportingEnabled,
            scrobbleTargets = scrobble.serverTargets,
            transcodeOnMobileData =
                preferences.streamQuality == StreamQualityPreference.MP3_320_ON_METERED,
            // Pessimistic before negotiation has happened: the row stays hidden rather than
            // appearing and then vanishing a moment later.
            transcodingAvailable = capabilities?.transcodingAvailable == true,
        )
    }

    private val notificationSection: Flow<NotificationSectionState> =
        settingsStore.notifications.map { stored: NotificationSettings ->
            NotificationSectionState(
                pullFinished = stored.pullFinished,
                pullFailed = stored.pullFailed,
                newReleaseFromFollowedArtist = stored.newReleaseFromFollowedArtist,
            )
        }

    private val storageSection: Flow<StorageSectionState> = combine(
        pins.observeStorageUsage(),
        pins.observeDownloadedAlbums(),
        pins.observeStoragePreferences(),
    ) { usage: StorageUsage, albums: List<DownloadedAlbum>, preferences: StoragePreferences ->
        StorageSectionState(
            downloadedBytes = usage.downloadedBytes,
            cachedBytes = usage.cachedBytes,
            artworkBytes = usage.artworkBytes,
            deviceFreeBytes = usage.deviceFreeBytes,
            lowOnSpace = usage.deviceLowOnSpace,
            shortfallBytes = usage.freeSpaceShortfallBytes,
            downloadedAlbums = albums,
            keepPulledAlbumsOnDevice = preferences.keepPulledAlbumsOnDevice,
            downloadToDeviceOnWifiOnly = preferences.downloadToDeviceOnWifiOnly,
        )
    }

    val state: StateFlow<SettingsUiState> = combine(
        serverSection,
        playingSection,
        notificationSection,
        storageSection,
        transient,
    ) { server, playing, notifications, storage, pending ->
        SettingsUiState(
            loading = false,
            server = server.copy(syncNotice = pending.syncNotice),
            playing = playing,
            notifications = notifications,
            storage = storage.copy(working = pending.working, notice = pending.storageNotice),
            about = about,
            armedAction = pending.armedAction,
            signedOut = pending.signedOut,
            signOutNotice = pending.signOutNotice,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = SettingsUiState(about = about),
    )

    init {
        // The scrobble row cannot name a destination until the server has been asked what it
        // forwards to, and nothing else in the app asks. Failure is silent and harmless: the label
        // falls back to "Report plays to your server", which is true whatever the targets are.
        viewModelScope.launch { playbackSettings.refreshScrobblePreferences() }
    }

    // ---- Playing -------------------------------------------------------------

    fun onGaplessChange(enabled: Boolean) {
        viewModelScope.launch { playbackSettings.setGaplessEnabled(enabled) }
    }

    fun onScrobblingChange(enabled: Boolean) {
        viewModelScope.launch { playbackSettings.setScrobblingEnabled(enabled) }
    }

    /**
     * "Stream MP3 320 on mobile data".
     *
     * Two writes, on purpose - see this class's header. The repository call is the domain-level
     * statement of intent and is what any future correct implementation will honour; the store call
     * is what makes switching the setting *off* actually stick today, because
     * `setStreamQuality(ORIGINAL)` leaves the metered quality at its transcoding default and the
     * repository reads that pair straight back as `MP3_320_ON_METERED`.
     */
    fun onTranscodeOnMobileDataChange(enabled: Boolean) {
        viewModelScope.launch {
            playbackSettings.setStreamQuality(
                if (enabled) StreamQualityPreference.MP3_320_ON_METERED else StreamQualityPreference.ORIGINAL,
            )
            settingsStore.setMobileDataStreamQuality(
                if (enabled) StreamQuality.MP3_320 else StreamQuality.ORIGINAL,
            )
        }
    }

    // ---- Notifications -------------------------------------------------------

    fun onNotifyPullFinishedChange(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotifyPullFinished(enabled) }
    }

    fun onNotifyPullFailedChange(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotifyPullFailed(enabled) }
    }

    fun onNotifyNewReleaseChange(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setNotifyNewRelease(enabled) }
    }

    // ---- Server --------------------------------------------------------------

    /**
     * **Sync now**, forced.
     *
     * A forced delta skips the fifteen-minute staleness check but not the revision check, so
     * pressing it against an unchanged library is one request that returns almost nothing - which
     * is why the result line says "already up to date" rather than claiming work it did not do.
     */
    fun onSyncNow() {
        if (!state.value.server.canSyncNow) return
        viewModelScope.launch {
            transient.update { it.copy(syncNotice = null) }
            val notice: String = when (val outcome: Outcome<SyncReport> = sync.deltaSync(force = true)) {
                is Outcome.Success -> describe(outcome.value)
                is Outcome.Failure -> "Could not sync. " + describe(outcome.error)
            }
            transient.update { it.copy(syncNotice = notice) }
        }
    }

    // ---- Storage -------------------------------------------------------------

    fun onKeepPulledAlbumsChange(enabled: Boolean) {
        viewModelScope.launch {
            val outcome: Outcome<Unit> = pins.setKeepPulledAlbumsOnDevice(enabled)
            if (outcome is Outcome.Failure) {
                transient.update {
                    it.copy(storageNotice = "Could not save that. " + describe(outcome.error))
                }
            }
        }
    }

    fun onWifiOnlyDownloadsChange(enabled: Boolean) {
        viewModelScope.launch {
            val outcome: Outcome<Unit> = pins.setDownloadToDeviceOnWifiOnly(enabled)
            if (outcome is Outcome.Failure) {
                transient.update {
                    it.copy(storageNotice = "Could not save that. " + describe(outcome.error))
                }
            }
        }
    }

    /**
     * Removes one downloaded album and reports what that actually gave back.
     *
     * REQUIREMENTS.md is explicit that the figure has to be real: "a 'remove' that leaves the usage
     * figure unchanged is the one thing that would make this whole screen untrustworthy". The
     * album is passed whole rather than by MBID so the message can name it; an album whose download
     * never landed frees nothing, which is a success and is reported as one rather than as an
     * error.
     */
    fun onRemoveDownload(album: DownloadedAlbum) {
        if (transient.value.working) return
        viewModelScope.launch {
            transient.update { it.copy(working = true, storageNotice = null) }
            val notice: String =
                when (val outcome: Outcome<RemovedDownload> = pins.unpinAlbum(album.releaseGroupMbid)) {
                    is Outcome.Success -> describe(album, outcome.value)
                    is Outcome.Failure ->
                        "Could not remove " + album.title + ". " + describe(outcome.error)
                }
            transient.update { it.copy(working = false, storageNotice = notice) }
        }
    }

    /**
     * "Clear cached music": the listening tier alone, leaving every download where it is.
     *
     * One tap, no confirmation, because REQUIREMENTS.md says so outright - those bytes are
     * re-fetchable and were never explicitly asked for.
     */
    fun onClearCachedMusic() {
        if (transient.value.working) return
        viewModelScope.launch {
            transient.update { it.copy(working = true, storageNotice = null) }
            val notice: String =
                when (val outcome: Outcome<EvictionReport> = pins.clearCachedAudio()) {
                    is Outcome.Success -> "Cleared cached music, freeing " +
                        SettingsFormat.bytes(outcome.value.freedBytes) + "."

                    is Outcome.Failure -> "Could not clear the cache. " + describe(outcome.error)
                }
            transient.update { it.copy(working = false, storageNotice = notice) }
        }
    }

    // ---- Destructive actions -------------------------------------------------

    /** Arms an action, which draws its confirmation in place of the row that started it. */
    fun onArmDestructiveAction(action: DestructiveSettingsAction) {
        transient.update { it.copy(armedAction = action, storageNotice = null, signOutNotice = null) }
    }

    fun onCancelDestructiveAction() {
        transient.update { it.copy(armedAction = null) }
    }

    /**
     * Goes through with whatever is armed.
     *
     * The action is disarmed *first*. A second tap arriving while the removal is in flight must not
     * start a second one, and clearing the arm is the cheapest way to say that which does not
     * depend on the screen having disabled anything.
     */
    fun onConfirmDestructiveAction() {
        val action: DestructiveSettingsAction = transient.value.armedAction ?: return
        transient.update { it.copy(armedAction = null) }
        when (action) {
            DestructiveSettingsAction.RemoveAllFromDevice -> removeAllFromDevice()
            DestructiveSettingsAction.SignOut -> signOut()
        }
    }

    private fun removeAllFromDevice() {
        if (transient.value.working) return
        viewModelScope.launch {
            transient.update { it.copy(working = true, storageNotice = null) }
            val notice: String =
                when (val outcome: Outcome<EvictionReport> = pins.removeAllFromDevice()) {
                    is Outcome.Success -> "Removed everything from this device, freeing " +
                        SettingsFormat.bytes(outcome.value.freedBytes) +
                        ". Your library is still browsable."

                    is Outcome.Failure -> "Could not remove everything. " + describe(outcome.error)
                }
            transient.update { it.copy(working = false, storageNotice = notice) }
        }
    }

    private fun signOut() {
        viewModelScope.launch {
            when (val outcome: Outcome<Unit> = session.signOut(revokeRemote = true)) {
                is Outcome.Success -> transient.update { it.copy(signedOut = true) }
                is Outcome.Failure -> transient.update {
                    it.copy(signOutNotice = "Could not sign out. " + describe(outcome.error))
                }
            }
        }
    }

    // ---- internals -----------------------------------------------------------

    private fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())

    private fun describe(report: SyncReport): String = if (report.libraryUnchanged) {
        "Already up to date."
    } else {
        "Synced " + SettingsFormat.plural(report.albumsUpdated.toLong(), "album") +
            " and " + SettingsFormat.plural(report.artistsUpdated.toLong(), "artist") + "."
    }

    private fun describe(album: DownloadedAlbum, removed: RemovedDownload): String =
        if (removed.removedTracks == 0) {
            "Removed " + album.title + ". None of it was on this device."
        } else {
            "Removed " + album.title + ": " +
                SettingsFormat.plural(removed.removedTracks.toLong(), "track") + ", " +
                SettingsFormat.bytes(removed.freedBytes) + " freed."
        }

    /**
     * One sentence a person can act on, for each failure this screen can actually produce.
     *
     * `NeedlerError.diagnostic` is deliberately not used: its own documentation says it is for the
     * diagnostics log and is "never shown raw to the user". The `else` is not laziness either -
     * most of the modelled errors belong to onboarding, streaming or pulls and cannot reach any
     * control on this screen, so spelling them all out here would be inventing copy for states that
     * are unreachable from it.
     */
    private fun describe(error: NeedlerError): String = when (error) {
        is NeedlerError.Offline -> "There is no connection to the server right now."
        NeedlerError.SessionExpired ->
            "Your sign-in has expired. Sign in again to restore search and pulls."

        NeedlerError.SubsonicProtocolDisabled ->
            "The server has the Subsonic protocol switched off. An administrator has to enable it."

        NeedlerError.DownloadForbidden ->
            "This server does not allow downloads for your account."

        is NeedlerError.RateLimited -> "The server asked Needler to slow down. Try again shortly."
        is NeedlerError.ServerError -> "The server had a problem. Try again shortly."
        is NeedlerError.InsufficientStorage -> "This device has no room left."
        else -> "Something went wrong. Try again."
    }

    /**
     * The standing warning about the session, if there is one.
     *
     * REQUIREMENTS.md: the companion bearer "expires hard at 30 days" with no silent renewal, and
     * the app must "warn from day 25". `SessionState.PlayerOnly` is the state after that expiry and
     * is explicitly *not* a broken app - playback, browsing, playlists and favourites all keep
     * working - so the line says what stopped rather than announcing a failure.
     *
     * `RepairingAppPassword` deliberately produces nothing. Its own documentation says "Nothing in
     * the UI announces this state - no dialog, no prompt, no sign-in screen - because there is
     * nothing for the user to do and the repair is one HTTP round trip."
     */
    private fun sessionNoticeFor(sessionState: SessionState, now: Instant): String? = when (sessionState) {
        is SessionState.Authenticated ->
            if (sessionState.shouldWarnAboutExpiry(now)) {
                val days: Long = sessionState.bearerExpiresAt?.let { (it - now).inWholeDays } ?: 0L
                "This device's sign-in expires in " + SettingsFormat.plural(
                    days.coerceAtLeast(0L),
                    "day",
                ) + ". Sign in again to keep search and pulls working."
            } else {
                null
            }

        is SessionState.PlayerOnly ->
            "Signed in for playback only: this device's session has expired, so search and pulls " +
                "are unavailable. Everything else keeps working."

        is SessionState.SubsonicDisabled ->
            "The server has the Subsonic protocol switched off, so there is no library to browse. " +
                "An administrator has to enable it."

        is SessionState.ReonboardingRequired -> "Signed out. Connect to a server to start again."

        // Silent by design - see this function's documentation.
        is SessionState.RepairingAppPassword -> null
        SessionState.NotConfigured -> null
    }

    private fun serverOf(sessionState: SessionState): ServerIdentity? = when (sessionState) {
        is SessionState.Authenticated -> sessionState.server
        is SessionState.PlayerOnly -> sessionState.server
        is SessionState.RepairingAppPassword -> sessionState.server
        is SessionState.SubsonicDisabled -> sessionState.server
        is SessionState.ReonboardingRequired -> sessionState.server
        SessionState.NotConfigured -> null
    }

    private fun userOf(sessionState: SessionState): User? = when (sessionState) {
        is SessionState.Authenticated -> sessionState.user
        is SessionState.PlayerOnly -> sessionState.user
        is SessionState.RepairingAppPassword -> sessionState.user
        is SessionState.SubsonicDisabled -> null
        is SessionState.ReonboardingRequired -> null
        SessionState.NotConfigured -> null
    }

    /**
     * `music.yourhome.net` from `https://music.yourhome.net`, as the pack draws it.
     *
     * The scheme and any sub-path are dropped for the row's label only; the stored URL keeps both,
     * because a sub-path deployment is one of the shapes `SessionRepository.probeServer` explicitly
     * accepts. A port is kept - `192.168.1.50:8688` and `192.168.1.50` are different servers, and
     * this row exists to tell the user which one they are on.
     */
    private fun hostOf(identity: ServerIdentity?): String? {
        val raw: String = identity?.baseUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val host: String = raw.substringAfter("://", raw).substringBefore('/')
        return host.ifEmpty { null }
    }

    /** Everything that belongs to this screen's session rather than to any repository. */
    private data class Transient(
        val armedAction: DestructiveSettingsAction? = null,
        val working: Boolean = false,
        val syncNotice: String? = null,
        val storageNotice: String? = null,
        val signOutNotice: String? = null,
        val signedOut: Boolean = false,
    )

    private companion object {
        /**
         * Keep the preference and cache-index flows alive briefly after the last subscriber leaves,
         * so a rotation does not re-read every `DataStore` file and re-query the cache index.
         */
        const val SUBSCRIPTION_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * The installed version, read from `PackageManager`.
 *
 * **Not `BuildConfig`.** `buildConfig` is not enabled in this project, so there is no generated
 * `BuildConfig.VERSION_NAME` to read at all. The package manager is also the better source for a
 * sideloaded APK: it reports what is actually installed, which is the only thing a bug report can
 * be matched against.
 *
 * `NameNotFoundException` cannot happen for an app asking about itself, but it is declared, and an
 * About row that crashed the Settings screen would be an absurd way to lose the app. A failure
 * renders as "Unknown".
 */
@Suppress("DEPRECATION")
private fun readAppVersion(context: Context): AboutSectionState = try {
    // The single-argument overload is deprecated from API 33 in favour of the PackageInfoFlags
    // one, but it is correct on every level this app supports (minSdk 26) and needs no version
    // branch. A branch here would be two code paths for one string.
    val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    AboutSectionState(
        versionName = info.versionName.orEmpty(),
        // The Int `versionCode`, not `longVersionCode`: the latter is API 28 and this app's minSdk
        // is 26, and `:app`'s build file derives the value from the git tag as an Int anyway
        // (v1.2.3 becomes 10203), so there is nothing above Int.MAX_VALUE to lose.
        versionCode = info.versionCode.toLong(),
    )
} catch (error: PackageManager.NameNotFoundException) {
    AboutSectionState()
}
