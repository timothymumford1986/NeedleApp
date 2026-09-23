// See SettingsFormat.kt for why this file opts in: every Instant that reaches this screen from
// :core:domain is kotlinx.datetime.Instant, which is a deprecated typealias for the stdlib one.
@file:OptIn(ExperimentalTime::class)

package app.needler.settings

import app.needler.core.domain.model.CrossfadeDuration
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.EqPreset
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Everything the Settings screen renders - `design/html/12-Settings.html`, with the corrections
 * REQUIREMENTS.md makes to it.
 *
 * The screen is stateless: it is handed one of these plus a [SettingsCallbacks], so every state it
 * can be in can be screenshotted and asserted on without a repository, a `DataStore`, a database or
 * a server. That is the same split `ConnectRoute`/`ConnectScreen` and `LibraryRoute`/`LibraryScreen`
 * already use in this project, and it is the only reason the failure and confirmation states below
 * are testable at all.
 *
 * ## What the design pack draws that is deliberately not here
 *
 * REQUIREMENTS.md "Design pack discrepancies" lists four corrections to screen 12, and three of
 * them remove something the artboard draws. They are recorded here rather than in the screen so
 * that the *model* says what the product is, and a future contributor adding a field has to argue
 * with this list first.
 *
 *  * **"Device storage limit", with its 1/2/4/8/16 GB presets, is gone.** REQUIREMENTS.md
 *    "Storage, and why there is no budget": "There is no user-facing storage limit." Downloaded
 *    albums are unlimited and are never evicted automatically; the cached-while-listening tier is
 *    bounded by the device's own free-space floor, which nobody configures. So there is no
 *    `storageLimitBytes` on [StorageSectionState], and `NeedlerSettingsStore` deliberately keeps
 *    the old `storage_budget_bytes` preference key burned so a future setting cannot inherit a
 *    stale byte count from an old install.
 *  * **"Prefer FLAC" is gone.** REQUIREMENTS.md: the request body carries no quality field, because
 *    quality is a server-side policy under the admin-only download-clients policy endpoint.
 *    Presenting it as a user toggle "would be a lie". Showing it read-only is the documented
 *    alternative, and it is not done here because the figure it would show -
 *    `quality_snapshot_summary` - is carried on the album, not by any repository this screen can
 *    reach. That leaves the design's whole **Pulling** section empty, so the section itself is not
 *    drawn.
 *  * **"Pull on Wi-Fi only" has moved and changed meaning.** REQUIREMENTS.md "The Wi-Fi-only
 *    setting is mislabelled", which is marked "confirmed and settled rather than proposed": a pull
 *    costs the phone one small request, because the server does the downloading over its own
 *    connection. What consumes mobile data is downloading audio to the device, so the setting lives
 *    in [StorageSectionState.downloadToDeviceOnWifiOnly] and reads "Download to device on Wi-Fi
 *    only".
 *  * **"Scrobble to ListenBrainz" no longer names a destination in source.** The destination is
 *    the server's business, read from its scrobble preferences and used only to label the switch -
 *    see [PlayingSectionState.scrobbleLabel].
 *
 * ## Why there is no "error" field
 *
 * Nothing this screen *reads* can fail. Every figure comes from a `DataStore` preference, the local
 * cache index or the saved session, all of which are local and all of which have safe defaults -
 * `NeedlerSettingsStore` emits defaults rather than throwing even on a corrupt preference file.
 * What can fail is the handful of things this screen *does*: a sync, a sign-out, a removal. Those
 * report into [ServerSectionState.syncNotice] and [StorageSectionState.notice] as one plain line
 * each, beside the control that produced them, rather than into a screen-wide error banner that
 * would have to explain which of six actions it was about.
 */
data class SettingsUiState(
    /**
     * True until every section has answered once.
     *
     * Not "until the server answers": there is no network read behind this screen. It is the gap
     * between the composition starting and the first `DataStore` emission, which is short but not
     * zero, and rendering "0 B" of storage during it would be a figure rather than a wait.
     */
    val loading: Boolean = true,
    val server: ServerSectionState = ServerSectionState(),
    val playing: PlayingSectionState = PlayingSectionState(),
    val notifications: NotificationSectionState = NotificationSectionState(),
    val storage: StorageSectionState = StorageSectionState(),
    val about: AboutSectionState = AboutSectionState(),

    /**
     * The destructive action waiting for a second tap, or null when none is.
     *
     * REQUIREMENTS.md added the destructive colour `#e8908a` to the palette specifically because
     * screen 12 drew "Remove all from device" in the same accent blue as "Connect" and "Play" - "a
     * permanent data-loss action styled exactly like the primary action". The colour fixes how it
     * looks; this field fixes what it costs, by making the action two deliberate taps rather than
     * one mis-aimed thumb. It is state rather than a dialog because the pack draws no dialogs, and
     * because an inline confirmation can be screenshotted.
     */
    val armedAction: DestructiveSettingsAction? = null,

    /**
     * Set once the sign-out has completed, so the host can leave for Connect.
     *
     * The route watches this and calls its `onSignedOut` exactly the way `ConnectRoute` watches
     * `connected`: the screen never navigates, and a sign-out that failed never navigates either.
     */
    val signedOut: Boolean = false,

    /**
     * Why a sign-out did not happen.
     *
     * Its own field rather than sharing [StorageSectionState.notice] because it is rendered at the
     * foot of the screen, beside the button that produced it. `SessionRepository.signOut` treats
     * the server-side revoke as best-effort and returns success regardless, so this is very nearly
     * a dead path - but the interface can fail, and a sign-out that silently did nothing would be
     * the worst possible thing for this particular button to do.
     */
    val signOutNotice: String? = null,
)

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

/**
 * The **Server** block: the address and account, when the mirror last synced, and the two actions
 * that change either.
 *
 * Screen 12 draws the first two rows with chevrons, as though each opened something. Neither does,
 * and neither can: there is no server-detail screen in the pack and no sync-history screen either,
 * and REQUIREMENTS.md puts "multiple server profiles" and the diagnostics log outside v1. A chevron
 * on a row that goes nowhere is the same lie as an inert tap target, so both rows are drawn without
 * one and are not clickable.
 */
data class ServerSectionState(
    /** The saved server's host, e.g. `music.yourhome.net`. Null before onboarding. */
    val host: String? = null,
    /** The signed-in account, drawn on the right of the server row as the pack does. */
    val username: String? = null,
    val lastSyncedAt: Instant? = null,
    val syncing: Boolean = false,
    /** The server is unreachable. Nothing on this screen stops working; the sync row says so. */
    val offline: Boolean = false,

    /**
     * A standing fact about the session, not the result of an action: the day-25 expiry warning, or
     * the player-only degradation.
     *
     * REQUIREMENTS.md: the companion bearer "expires hard at 30 days" with "no silent renewal", and
     * the app must "warn from day 25". Settings is where a user looks when search or pulls have
     * stopped working, so the warning belongs here as well as wherever else it appears.
     */
    val sessionNotice: String? = null,

    /** What the last **Sync now** did, or why it could not. Replaced by the next one. */
    val syncNotice: String? = null,

    /** When the state was assembled, so "2 min ago" is computed against a fixed instant. */
    val renderedAt: Instant = Instant.fromEpochSeconds(0L),
) {
    val hostLabel: String get() = host ?: "No server"

    /**
     * `2 min ago`, `Syncing…`, or `Never`.
     *
     * Note this does not tick: the flows behind it emit when something changes, not once a minute,
     * so a Settings screen left open shows the age it had when it was assembled. That is the same
     * behaviour the library header has, and it is deliberate - a relative time that updates itself
     * costs a recomposition a second for a line nobody is watching.
     */
    val lastSyncedLabel: String
        get() = when {
            syncing -> "Syncing…"
            else -> SettingsFormat.relativeTime(lastSyncedAt, renderedAt) ?: "Never"
        }

    /** A sync with no server to sync against is not an action, so the row does not offer it. */
    val canSyncNow: Boolean get() = host != null && !syncing
}

// ---------------------------------------------------------------------------
// Playing
// ---------------------------------------------------------------------------

/**
 * The **Playing** block: gapless, the two sub-screens, stream quality and scrobbling.
 *
 * Crossfade and the equaliser are rows that lead somewhere, not controls. Both sub-screens already
 * exist, fully built, in `:feature:player` - `CrossfadeRoute` and `EqualiserRoute` - because both
 * need the custom Media3 audio-processor chain that lives in `:player:service`. Settings links to
 * them; it does not contain them.
 */
data class PlayingSectionState(
    /** On by default. REQUIREMENTS.md: Media3 concatenation, no re-buffer between tracks. */
    val gaplessEnabled: Boolean = true,
    val crossfade: CrossfadeDuration = CrossfadeDuration.OFF,
    val equaliserEnabled: Boolean = false,
    val equaliserPreset: EqPreset = EqPreset.FLAT,
    val scrobblingEnabled: Boolean = true,
    /** Destinations the *server* is configured to forward to, e.g. ListenBrainz, Last.fm. */
    val scrobbleTargets: List<String> = emptyList(),

    /**
     * Whether a metered connection gets MP3 320 instead of original bytes.
     *
     * The domain models this as one preference with two values rather than as the two separate rows
     * screen 12 draws - `StreamQualityPreference.MP3_320_ON_METERED` already means "original on
     * unmetered, MP3 320 while metered" - so there is one control here, not two.
     */
    val transcodeOnMobileData: Boolean = false,

    /**
     * True only when `transcoding:1` is advertised **and** the server reports transcoding enabled.
     *
     * REQUIREMENTS.md, rule 3 of "Streaming": "Hide that setting entirely unless `transcoding:1`
     * appears in `getOpenSubsonicExtensions` and the server reports transcoding enabled. Neither is
     * guaranteed; ffmpeg may be absent." Hidden, not disabled: a greyed row invites a user to go
     * hunting for the thing that would enable it, and on a server with no ffmpeg there is nothing
     * to find.
     */
    val transcodingAvailable: Boolean = false,
) {
    /** `Off`, `4 s`, `6 s`, `12 s` - the four stops screen 20 offers. */
    val crossfadeLabel: String
        get() = when (crossfade) {
            CrossfadeDuration.OFF -> "Off"
            CrossfadeDuration.FOUR_SECONDS -> "4 s"
            CrossfadeDuration.SIX_SECONDS -> "6 s"
            CrossfadeDuration.TWELVE_SECONDS -> "12 s"
        }

    /**
     * `Flat` on the pack's artboard, and whichever of the five presets is chosen here.
     *
     * A switched-off equaliser reads `Off` rather than naming its preset, because the preset is
     * what it *would* apply, and a row reading "Bass" beside a chain that is flat would be the
     * clearest possible way to make someone think their equaliser is broken.
     */
    val equaliserLabel: String
        get() = if (!equaliserEnabled) {
            "Off"
        } else {
            when (equaliserPreset) {
                EqPreset.FLAT -> "Flat"
                EqPreset.BASS -> "Bass"
                EqPreset.VOCAL -> "Vocal"
                EqPreset.BRIGHT -> "Bright"
                EqPreset.VINYL -> "Vinyl"
                EqPreset.CUSTOM -> "Custom"
            }
        }

    /**
     * Always `Original`.
     *
     * REQUIREMENTS.md, rule 1 of "Streaming": "Default stream quality is Original, matching screen
     * 12", and the only other value the domain models is the metered transcode, which is the
     * separate control below. So the row reports rather than offering, and carries no chevron.
     */
    val streamQualityLabel: String get() = "Original"

    /**
     * `Scrobble to ListenBrainz`, or `Report plays to your server` when the destinations are not
     * known yet.
     *
     * REQUIREMENTS.md: "Read `GET /api/v1/me/scrobble-preferences` and label the toggle with
     * whatever target is actually configured, rather than hard-coding ListenBrainz as screen 12
     * does." Before that read lands - offline, or on the first frame - naming a service the user
     * may not use would be worse than naming none, so the fallback describes what the toggle
     * actually governs: whether plays are reported at all.
     */
    val scrobbleLabel: String
        get() = if (scrobbleTargets.isEmpty()) {
            "Report plays to your server"
        } else {
            "Scrobble to " + SettingsFormat.andList(scrobbleTargets)
        }

    /** The second line that explains the fallback label, and nothing once the targets are known. */
    val scrobbleSubtitle: String?
        get() = if (scrobbleTargets.isEmpty()) {
            "Your server decides where they go."
        } else {
            null
        }
}

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

/**
 * The three independently switchable notifications on screen 12.
 *
 * All three are drawn, all three are stored, and none of them is promised to be timely.
 * REQUIREMENTS.md "Open risks": "OEM battery managers may suppress background polling entirely on
 * some devices, making pull notifications unreliable. The Pulls badge is the fallback, and the app
 * should not promise timely notifications." So the rows say what they are for and claim nothing
 * about when.
 */
data class NotificationSectionState(
    val pullFinished: Boolean = true,
    val pullFailed: Boolean = true,
    val newReleaseFromFollowedArtist: Boolean = true,
)

// ---------------------------------------------------------------------------
// Storage
// ---------------------------------------------------------------------------

/**
 * The **Storage** block, which is the part of screen 12 REQUIREMENTS.md rewrote most heavily.
 *
 * The document specifies it directly - "Screen 12's Storage section therefore shows" - and this
 * type is that list:
 *
 *  * usage split into downloaded and cached-while-listening, with artwork on its own line because
 *    it has its own small LRU and "a user hunting for gigabytes should not spend a tap on it", and
 *    the device's free space beside them;
 *  * downloaded albums listed by size, largest first, each removable on its own, which "replaces
 *    the budget as the way space is reclaimed";
 *  * **Clear cached music**, which removes the listening tier alone and needs no confirmation
 *    because "those bytes are re-fetchable and were never explicitly asked for";
 *  * **Remove all from device**, which clears both audio tiers and the artwork cache but never the
 *    metadata mirror.
 *
 * It also carries the two toggles that survived: "Keep pulled albums on device", and the moved
 * Wi-Fi-only setting.
 */
data class StorageSectionState(
    /** Pinned albums. Never evicted automatically, by decision, however full the device gets. */
    val downloadedBytes: Long = 0L,
    /** Re-fetchable bytes kept as a side effect of streaming, bounded by the free-space floor. */
    val cachedBytes: Long = 0L,
    val artworkBytes: Long = 0L,
    val deviceFreeBytes: Long = 0L,

    /**
     * True when the device has less than its free-space floor left.
     *
     * A statement about the device, not about a limit the app imposes. REQUIREMENTS.md: "A device
     * below the floor produces a warning, not an eviction of downloads. Downloads can legitimately
     * fill a phone, and the correct response is to say so and offer to remove albums."
     */
    val lowOnSpace: Boolean = false,
    /** How far below the floor the device is, for phrasing the warning. */
    val shortfallBytes: Long = 0L,

    /** Largest first, as the repository returns them. */
    val downloadedAlbums: List<DownloadedAlbum> = emptyList(),
    val keepPulledAlbumsOnDevice: Boolean = false,
    /** The corrected "Pull on Wi-Fi only". Defaults to on. */
    val downloadToDeviceOnWifiOnly: Boolean = true,

    /** A removal or a clear is in flight, so the destructive controls stop responding. */
    val working: Boolean = false,

    /** What the last storage action freed, or why it could not. Replaced by the next one. */
    val notice: String? = null,
) {
    val downloadedLabel: String get() = SettingsFormat.bytes(downloadedBytes)
    val cachedLabel: String get() = SettingsFormat.bytes(cachedBytes)
    val artworkLabel: String get() = SettingsFormat.bytes(artworkBytes)
    val deviceFreeLabel: String get() = SettingsFormat.bytes(deviceFreeBytes)

    /** Everything Needler is holding, which is what "Remove all from device" would free. */
    val totalBytes: Long get() = downloadedBytes + cachedBytes + artworkBytes

    /**
     * Whether clearing the cached tier would do anything.
     *
     * An action that is certain to free nothing is not offered. This is the cheap version of the
     * rule REQUIREMENTS.md states about removals: a control that runs and leaves the figures
     * unchanged makes the whole screen untrustworthy.
     */
    val canClearCache: Boolean get() = cachedBytes > 0L && !working

    val canRemoveAll: Boolean get() = totalBytes > 0L && !working

    /** The warning line, phrased with the shortfall so it names an amount rather than a mood. */
    val lowOnSpaceMessage: String?
        get() = if (!lowOnSpace) {
            null
        } else {
            "This device is low on space. Removing a downloaded album is the only thing that frees " +
                "room Needler is holding; free about " + SettingsFormat.bytes(shortfallBytes) +
                " to get back above the line."
        }

    /** `12 albums · 4.8 GB`, on the right of the downloaded-albums header. */
    val downloadedAlbumsTrailing: String
        get() = SettingsFormat.plural(downloadedAlbums.size.toLong(), "album") +
            " · " + SettingsFormat.bytes(downloadedAlbums.sumOf { it.sizeBytes })
}

// ---------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------

/**
 * The version, and the block of legal copy screen 12 ends with.
 *
 * The version is read from `PackageManager` rather than from `BuildConfig`, because `buildConfig`
 * is not enabled in this project - there is no generated `BuildConfig.VERSION_NAME` to read. The
 * package manager is also the more honest source: it reports what was actually installed, which on
 * a sideloaded APK is the only thing a bug report can be matched against.
 *
 * `versionCode` is carried beside the name because `:app`'s build file derives both from the git
 * tag and notes that "Android decides what counts as an update by comparing versionCode". Two
 * builds of `0.1.0` are indistinguishable without it.
 */
data class AboutSectionState(
    val versionName: String = "",
    val versionCode: Long = 0L,
) {
    /** `0.1.0 (10100)`, or `Unknown` when the package could not be read at all. */
    val versionLabel: String
        get() = when {
            versionName.isBlank() -> "Unknown"
            versionCode > 0L -> "$versionName ($versionCode)"
            else -> versionName
        }
}

// ---------------------------------------------------------------------------
// Destructive actions
// ---------------------------------------------------------------------------

/**
 * The two actions on this screen that lose something, and therefore take two taps.
 *
 * Only these two. Removing one downloaded album is a single tap on purpose: the album is still on
 * the server, the row names it, and REQUIREMENTS.md makes per-album removal "the way space is
 * reclaimed" now that the budget is gone - putting a confirmation in front of the only lever a user
 * has on a full device would make the screen tiring to use for its main purpose. "Clear cached
 * music" is a single tap for the reason the document gives outright: "Safe behind a single tap,
 * because those bytes are re-fetchable and were never explicitly asked for."
 */
enum class DestructiveSettingsAction {
    /**
     * Clears both audio tiers and the artwork cache, and **never** the metadata mirror -
     * REQUIREMENTS.md: "removing the mirror would leave the app unable to browse".
     */
    RemoveAllFromDevice,

    /**
     * Signs out, revoking this device's companion session and app-password server-side.
     *
     * Two taps because it is not reversible from inside the app: re-onboarding needs the account
     * password, which Needler deliberately never stores.
     *
     * It does **not** delete the music on the device, and the prompt says so rather than implying
     * it. `SessionRepository.signOut` clears the credentials and nothing else; the mirror and the
     * audio store are dropped only when the *server identity* changes, which is a different event
     * with a different reason - REQUIREMENTS.md: "MBIDs are global but `file_id` values and
     * playlist IDs are not." A user who wants the bytes gone has "Remove all from device" directly
     * above.
     */
    SignOut,
    ;

    /** What the confirmation asks. */
    val prompt: String
        get() = when (this) {
            RemoveAllFromDevice ->
                "This deletes every downloaded album and everything cached while listening. Your " +
                    "library stays browsable and nothing on the server changes."

            SignOut ->
                "This revokes this device's session and app-password on the server. Music already " +
                    "on the device is left alone; you will need your account password to sign in " +
                    "again."
        }

    /** The label on the button that goes through with it. */
    val confirmLabel: String
        get() = when (this) {
            RemoveAllFromDevice -> "Remove everything"
            SignOut -> "Sign out"
        }
}
