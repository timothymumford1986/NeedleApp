package app.needler.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerHairline
import app.needler.core.design.component.NeedlerPillButton
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerSectionHeader
import app.needler.core.design.component.NeedlerSettingsRow
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.NeedlerToggleRow
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.StreamRung
import app.needler.licences.NeedlerLegal

/**
 * Every callback the Settings screen needs, in one value.
 *
 * Grouped rather than spread across the signature because there are eighteen of them, and a
 * composable with eighteen trailing lambdas is a composable whose call sites get one of them wrong.
 * The same reasoning produced `ConnectProxyCallbacks` next door.
 *
 * **Nothing here has a default.** A `() -> Unit` default of `{}` is precisely the inert tap target
 * `NeedlerNavHost` already refuses to create for the output picker: it compiles, it renders, it does
 * nothing, and there is no way to tell from the screen which one was forgotten. The three genuinely
 * optional callbacks are nullable instead, and their absence removes the affordance rather than
 * leaving it dead - the licences row, the diagnostics row and the expiry action each disappear when
 * the host has not wired them, rather than sitting there doing nothing.
 */
data class SettingsCallbacks(
    val onSyncNow: () -> Unit,
    val onChangeServer: () -> Unit,
    val onGaplessChange: (Boolean) -> Unit,
    val onOpenCrossfade: () -> Unit,
    val onOpenEqualiser: () -> Unit,
    val onWifiRungChange: (StreamRung) -> Unit,
    val onDataRungChange: (StreamRung) -> Unit,
    val onScrobblingChange: (Boolean) -> Unit,
    val onNotifyPullFinishedChange: (Boolean) -> Unit,
    val onNotifyPullFailedChange: (Boolean) -> Unit,
    val onNotifyNewReleaseChange: (Boolean) -> Unit,
    val onKeepPulledAlbumsChange: (Boolean) -> Unit,
    val onWifiOnlyDownloadsChange: (Boolean) -> Unit,
    val onRemoveDownload: (DownloadedAlbum) -> Unit,
    val onClearCachedMusic: () -> Unit,
    val onCheckForUpdates: () -> Unit,
    val onArmDestructiveAction: (DestructiveSettingsAction) -> Unit,
    val onCancelDestructiveAction: () -> Unit,
    val onConfirmDestructiveAction: () -> Unit,

    /**
     * Opens the diagnostics log - `app.needler.diagnostics.DiagnosticsRoute`.
     *
     * Nullable for the same reason as [onOpenLicences]: until `NeedlerNavHost` registers the route,
     * the row is not drawn at all rather than drawn and inert.
     */
    val onOpenDiagnostics: (() -> Unit)? = null,

    /**
     * Opens the licences and full terms - `app.needler.licences.LicencesRoute`.
     *
     * Nullable so that a host which has not registered the route draws no link, rather than one that
     * does nothing.
     */
    val onOpenLicences: (() -> Unit)? = null,

    /**
     * Opens the full downloaded-album list - [DownloadsRoute].
     *
     * **Null does not remove a feature here, unlike the three callbacks above it.** It falls the
     * Storage section back to drawing every downloaded album inline, which is what it did before this
     * screen had anywhere to send them. That asymmetry is deliberate. REQUIREMENTS.md "Storage, and
     * why there is no budget" leaves no storage limit in the product at all, which makes this list
     * the user's only lever on a full device and "the only view that can answer 'what is actually
     * taking up the room'" - so a host that has not registered the route yet must not be able to make
     * part of it unreachable. A wiring gap may cost the user a tidier screen; it may not cost them
     * the ability to free space.
     *
     * Register it as described on [DownloadsRoute], and the section truncates to
     * [StorageSectionState.INLINE_DOWNLOADED_ALBUMS] rows with "See all N albums" under them.
     */
    val onOpenDownloads: (() -> Unit)? = null,

    /**
     * Re-authenticates an expired companion session, which means going back to Connect for the
     * account password - Needler never stores it, so there is no silent renewal. Null leaves the
     * expiry warning as a statement rather than an action.
     */
    val onSignInAgain: (() -> Unit)? = null,
)

/**
 * The Settings screen: `design/html/12-Settings.html`, with the corrections REQUIREMENTS.md makes
 * to it.
 *
 * Stateless. It is handed a [SettingsUiState] and a [SettingsCallbacks] and owns no `ViewModel`, no
 * repository and no navigation, which is what lets every state it can be in - a device low on
 * space, an expiring session, an armed destructive action, a server that cannot transcode - be
 * rendered to a PNG and asserted on with nothing behind it. [SettingsRoute] is the stateful half.
 *
 * ## The sections, and what happened to the pack's
 *
 * | Screen 12 | Here | Why |
 * | --- | --- | --- |
 * | Server | Server | Both rows lose their chevrons; nothing is behind them |
 * | Pulling | *gone* | Both of its rows left - see [SettingsUiState] |
 * | Playing | Playing | Both quality rows are rung pickers now, and both are capability-gated |
 * | Notifications | Notifications | Unchanged |
 * | Storage | Storage | Rewritten: no budget, usage split by tier, albums listed and removable |
 * | *(legal block)* | About | Gains the version row; the rest is the pack's copy verbatim |
 *
 * ## One rule for what a row looks like
 *
 * Screen 12 carried five interaction idioms with no rule between them - a static value, a value with
 * a chevron, a value with no chevron that was nonetheless tappable, a switch, and blue text links -
 * so nothing on the screen told a user which rows did anything. That is the fault that let the inert
 * `Stream quality` row ship: a control that did not look like one, sitting beside rows that were not
 * controls and looked identical.
 *
 * There are four shapes now, and each one means exactly one thing:
 *
 * | Shape | Means | Drawn with |
 * | --- | --- | --- |
 * | Label, value, **chevron** | tapping opens or expands something | `NeedlerSettingsRow` with `onClick` |
 * | Label, value, **no chevron** | reports a fact; nothing happens on tap | `NeedlerSettingsRow` with no `onClick` |
 * | Label and a switch | a setting with two states, changed in place | `NeedlerToggleRow` |
 * | Accent text on its own | performs an action here and now | `NeedlerTextButton` |
 *
 * The rule that follows from the table is the one worth stating: **a row that does something carries
 * a chevron, and a row with no chevron is not tappable.** `NeedlerSettingsRow` already defaults
 * `showChevron` to `onClick != null`, so the rule holds by default and can only be broken by passing
 * `showChevron` explicitly - which nothing in this file does any more. `Check for updates` did, and
 * it has become a text button, which is what it always was: it performs an action rather than
 * leading anywhere, so it belongs with `Sync now` and `Clear cached music` rather than among the
 * rows that open screens.
 *
 * The rejected alternative was giving the chevron to every tappable row, including the actions. That
 * reads as "this opens a screen" on six controls that change something in place, and it would have
 * put a chevron on `Remove all from device`.
 *
 * ## Why this is a LazyColumn, and what bounds the one section that was unbounded
 *
 * Everything on this screen is a fixed handful of rows except one section. REQUIREMENTS.md "Storage,
 * and why there is no budget" makes the downloaded-album list "the only view that can answer 'what is
 * actually taking up the room'", and that list is as long as the user's offline library - so a
 * `verticalScroll` `Column` would compose every one of those rows on every frame of a scroll.
 *
 * It used to be unbounded as well as lazy, which is the defect reported from the device: "it's just a
 * huge list. It seems like a poor UI choice." The complaint is about placement and density, not
 * existence - an `items()` with no ceiling in the middle of Settings is a section that grows until it
 * buries About and Sign out behind it. So the section now draws at most
 * [StorageSectionState.INLINE_DOWNLOADED_ALBUMS] rows, largest first, and sends the rest to [DownloadsScreen] through
 * "See all N albums". On the device this was reported from, which holds five, nothing changed at all;
 * on a device with fifty, the section is six rows instead of fifty.
 *
 * Nothing was capped *away*: every album stays individually removable, and the screen the overflow
 * leads to lists all of them in the same order with the same controls. [DownloadsUiState] records the
 * two alternatives weighed against this and why each lost.
 *
 * Note that the list has no `verticalArrangement` spacing: the pack's rows butt up against one
 * another and are separated by the 1dp hairline each one draws under itself, so a gap between items
 * would break the hairlines away from the rows they belong to. Sections space themselves with their
 * own top padding instead.
 *
 * ## The tablet
 *
 * The pack draws no tablet artboard for screen 12, so this is an inference rather than a
 * transcription: at Medium and Expanded width the content is capped at [TABLET_CONTENT_MAX_WIDTH]
 * and centred. Settings rows are a label at one end and a control at the other, and stretching that
 * pair across a 1280dp pane puts half a metre between a switch and the word that says what it does.
 *
 * @param listState the scroll position. A parameter rather than a `rememberLazyListState()` buried
 *   in the body because three of this screen's states - the low-space warning, the armed
 *   "Remove all from device" confirmation and the album list - render below the fold on the pack's
 *   390x844 artboard, so a screenshot taken at the top of the list cannot see any of them. Two
 *   goldens were byte-identical to `settings-phone.png` for exactly that reason and asserted nothing
 *   about the states they were named for. `SettingsScreenshotTest` now hands in a position; the app
 *   takes the default and behaves as it did.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    callbacks: SettingsCallbacks,
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    val wide: Boolean = widthSizeClass != WindowWidthSizeClass.Compact
    // The pack's own 24px gutter on screen 12, which is the wider of the two phone gutters.
    val gutter: Dp = if (wide) spacing.tabletGutter else spacing.phoneGutterWide

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .widthIn(max = if (wide) TABLET_CONTENT_MAX_WIDTH else Dp.Unspecified)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = gutter,
                end = gutter,
                // 48px in the pack, less whatever the status bar already took.
                top = spacing.step14,
                bottom = spacing.step16,
            ),
        ) {
            item(key = "title") {
                Text(
                    text = TITLE.uppercase(),
                    style = NeedlerTheme.typography.screenTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
            }

            item(key = "server") {
                ServerSection(
                    state = state.server,
                    // Read from the screen rather than the section, as the Storage block's own
                    // confirmation is: one action is armed at a time across the whole screen.
                    armedAction = state.armedAction,
                    callbacks = callbacks,
                )
            }

            item(key = "playing") {
                PlayingSection(
                    state = state.playing,
                    // Read from the server block rather than the playing block: a fresh install has
                    // no server to ask, so it gets the plain statement instead of a line about a
                    // negotiation that has not been attempted because there is nothing to attempt.
                    serverConfigured = state.server.host != null,
                    callbacks = callbacks,
                )
            }

            item(key = "notifications") {
                NotificationsSection(state = state.notifications, callbacks = callbacks)
            }

            item(key = "storage") { StorageSection(state = state.storage, callbacks = callbacks) }

            if (state.storage.downloadedAlbums.isNotEmpty()) {
                val openDownloads: (() -> Unit)? = callbacks.onOpenDownloads
                // Both decisions live on the state, where they can be asserted on: this section is
                // below the fold on the pack's phone artboard, so no screenshot of Settings can see
                // whether it truncated. See StorageSectionState.downloadedAlbumsInline.
                val shown: List<DownloadedAlbum> =
                    state.storage.downloadedAlbumsInline(openDownloads != null)
                val truncated: Boolean =
                    state.storage.downloadedAlbumsTruncated(openDownloads != null)

                item(key = "downloaded-albums-header") {
                    Column(modifier = Modifier.padding(top = spacing.sectionGap)) {
                        NeedlerSectionHeader(
                            title = "Albums on this device",
                            // Every album, not the ones drawn below it. See
                            // StorageSectionState.downloadedAlbumsTrailing.
                            trailing = state.storage.downloadedAlbumsTrailing,
                        )
                    }
                }
                items(items = shown, key = { album -> album.releaseGroupMbid.value }) { album ->
                    DownloadedAlbumRow(
                        album = album,
                        enabled = !state.storage.working,
                        onRemove = { callbacks.onRemoveDownload(album) },
                    )
                }
                if (truncated && openDownloads != null) {
                    item(key = "downloaded-albums-all") {
                        NeedlerSettingsRow(
                            label = state.storage.seeAllDownloadedAlbumsLabel,
                            onClick = openDownloads,
                            // Last row of the section; the rows above it carry the hairlines.
                            showDivider = false,
                        )
                    }
                }
            }

            item(key = "storage-actions") {
                StorageActions(
                    state = state.storage,
                    armedAction = state.armedAction,
                    callbacks = callbacks,
                )
            }

            item(key = "about") {
                AboutSection(
                    state = state.about,
                    onCheckForUpdates = callbacks.onCheckForUpdates,
                    onOpenLicences = callbacks.onOpenLicences,
                )
            }

            item(key = "sign-out") {
                SignOutBlock(
                    notice = state.signOutNotice,
                    armed = state.armedAction == DestructiveSettingsAction.SignOut,
                    callbacks = callbacks,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------

/**
 * The pack's **Server** block.
 *
 * ## The two rows the pack draws chevrons on, and the one row that earns one
 *
 * The address row and the "Last synced" row are drawn with chevrons on the artboard, as though each
 * opened a detail screen. Neither does, and each has its own reason.
 *
 * The **address row** cannot. REQUIREMENTS.md's "Out of scope" table lists "Multiple server
 * profiles" outright, so there is no second server to choose between and no profile detail to open.
 * "Change server" below the rows is the whole of what this app does with a server address.
 *
 * The **"Last synced" row** reports a time and nothing else, because the pack has no sync-history
 * artboard and inventing one is a design decision rather than an implementation of one. What a user
 * wanting to know *what* the last sync did is looking for is the diagnostics log, which is the row
 * below.
 *
 * ## The diagnostics row is not out of scope, and an earlier version of this comment said it was
 *
 * REQUIREMENTS.md "Observability" requires it outright - "A local, user-viewable diagnostics log
 * covering the last session: request URLs with secrets redacted, status codes, sync summaries and
 * playback errors. It must be shareable as a file for bug reports, and it must never leave the
 * device automatically" - and it appears nowhere in the "Out of scope" table. This comment used to
 * claim the opposite, and that claim is the whole reason the feature had no screen: it read as a
 * decision that had been taken rather than as work that had not been done.
 *
 * So Server has a third row, and it is the one row in this block with a chevron. It belongs here
 * rather than in About because a request log answers a question about the server - "why has it
 * stopped answering me" - and Settings' Server block is where a user goes with that question.
 *
 * The row is drawn only when [SettingsCallbacks.onOpenDiagnostics] is non-null, which keeps this
 * block's original rule intact: a chevron on a row that goes nowhere is the same lie as an inert tap
 * target.
 *
 * ## The certificate row, and why it is here rather than on Connect
 *
 * The Connect screen is where a self-signed certificate is trusted, and until now it was also the
 * only place the fact existed: the fingerprint was shown once, the user tapped Trust, and from then on
 * no surface in the application said what had been trusted or offered to undo it. REQUIREMENTS.md
 * "Self-signed certificates" scopes a pin to "the one host", and a scope nobody can inspect is a scope
 * taken on faith.
 *
 * Settings is the right home for the *standing* fact. Connect answers "can I reach this server"; it is
 * a transient screen a user visits twice, and the pin outlives both visits. The Server block is
 * already where the standing facts about the server live - its address, when it last synced, and the
 * log of what it has been saying - so the question "what is this app trusting" belongs beside them.
 *
 * The fingerprint goes on its own line rather than in the row's value slot, because it is 95 characters
 * and the value slot is a right-aligned fragment. See
 * [ServerSectionState.trustedCertificateFingerprint] for why it is drawn in full.
 */
@Composable
private fun ServerSection(
    state: ServerSectionState,
    armedAction: DestructiveSettingsAction?,
    callbacks: SettingsCallbacks,
) {
    SettingsSection(title = "Server") {
        // Above the rows, not below them. The expiry warning used to sit between "Diagnostics log"
        // and "Sync now" in the dimmest grey the palette has, which made the most consequential
        // sentence on the screen the hardest thing on it to read and the last thing reached. See
        // SessionAlert.
        val sessionNotice: String? = state.sessionNotice
        if (sessionNotice != null) {
            SessionAlert(text = sessionNotice, onSignInAgain = callbacks.onSignInAgain)
        }

        NeedlerSettingsRow(label = state.hostLabel, value = state.username)
        NeedlerSettingsRow(label = "Last synced", value = state.lastSyncedLabel)

        if (state.showCertificateRow) {
            TrustedCertificateBlock(
                state = state,
                armed = armedAction == DestructiveSettingsAction.ForgetCertificate,
                callbacks = callbacks,
            )
        }

        val openDiagnostics: (() -> Unit)? = callbacks.onOpenDiagnostics
        if (openDiagnostics != null) {
            NeedlerSettingsRow(label = "Diagnostics log", onClick = openDiagnostics)
        }

        // Offline is a fact, not an error. REQUIREMENTS.md: "The whole UI works with no network" -
        // only streaming un-cached audio and pulling new music need a connection - so the line says
        // what still works rather than presenting the state as a failure. Sync now is deliberately
        // left enabled: a connection that came back a second ago should not need this screen to
        // notice before the user may press it.
        if (state.offline) {
            NoticeLine(text = "No connection to the server. Everything on this device still plays.")
        }

        // Absent with no server, greyed while syncing. See ServerSectionState.showSyncNow for why
        // those two are not the same state and must not look like it.
        if (state.showSyncNow) {
            NeedlerTextButton(
                text = "Sync now",
                onClick = callbacks.onSyncNow,
                enabled = state.canSyncNow,
            )
        }
        val syncNotice: String? = state.syncNotice
        if (syncNotice != null) NoticeLine(text = syncNotice)

        NeedlerTextButton(text = state.changeServerLabel, onClick = callbacks.onChangeServer)
    }
}

/**
 * The standing warning about this device's session, drawn as the thing it is.
 *
 * REQUIREMENTS.md requires the companion bearer be warned about "from day 25", and the warning this
 * replaces was a caption in [app.needler.core.design.theme.NeedlerColors.textSecondary] wedged
 * between two rows - lower contrast than the row labels either side of it, no container, no colour,
 * and no action, on the one message on this screen that costs the user search and pulls if they
 * ignore it.
 *
 * So it gets a raised surface, the body type the rows use, primary text, and the action it names.
 * **The action is the point**: the sentence says "Sign in again" and until now the only live control
 * on the screen was "Change server", which is a different promise - it asks for an address, and this
 * user's address is fine. [SettingsCallbacks.onSignInAgain] goes to the same Connect screen, but the
 * button agrees with the sentence that sent the user to it.
 *
 * The button is still absent when the host has not wired the callback, which is this file's standing
 * rule about optional callbacks: the affordance disappears rather than sitting there doing nothing.
 * The warning itself is unaffected, because the fact is true whether or not anything is wired.
 *
 * The rejected alternative was the destructive colour. It is reserved for things that delete, and an
 * expiring session deletes nothing - the app keeps playing, browsing and favouriting throughout.
 * Raising the contrast and giving it a container says "read this" without saying "something broke".
 */
@Composable
private fun SessionAlert(text: String, onSignInAgain: (() -> Unit)?) {
    val colors = NeedlerTheme.colors
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = spacing.step4)
            .background(colors.surfaceRaised, NeedlerTheme.shapes.medium)
            .padding(horizontal = spacing.step6, vertical = spacing.step4),
    ) {
        Text(
            text = text,
            style = NeedlerTheme.typography.body,
            color = colors.textPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (onSignInAgain != null) {
            NeedlerTextButton(text = "Sign in again", onClick = onSignInAgain)
        }
    }
}

/**
 * The trusted certificate, its fingerprint, and the way to stop trusting it.
 *
 * Three parts because the row alone cannot carry them: the row states *which host* has an exception,
 * the line under it is the fingerprint the user checks against their own server, and the action is
 * behind the two-tap confirmation every consequence of this size in this screen is behind. See
 * [DestructiveSettingsAction.ForgetCertificate] for why two taps rather than one, given that a pin can
 * be granted again.
 *
 * The forget action is absent, not disabled, when nothing is pinned. There is no certificate to forget
 * and a disabled control would invite the user to work out why.
 */
@Composable
private fun TrustedCertificateBlock(
    state: ServerSectionState,
    armed: Boolean,
    callbacks: SettingsCallbacks,
) {
    val colors = NeedlerTheme.colors
    NeedlerSettingsRow(
        label = "Trusted certificate",
        value = state.trustedCertificateValue,
    )

    val fingerprint: String? = state.trustedCertificateFingerprint
    if (fingerprint != null) {
        NoticeLine(text = fingerprint, tone = colors.textSecondary)
    }

    val notice: String? = state.certificateNotice
    if (notice != null) NoticeLine(text = notice)

    if (armed) {
        DestructiveConfirmation(
            action = DestructiveSettingsAction.ForgetCertificate,
            onConfirm = callbacks.onConfirmDestructiveAction,
            onCancel = callbacks.onCancelDestructiveAction,
        )
    } else if (state.canForgetCertificate) {
        NeedlerTextButton(
            text = "Forget this certificate",
            onClick = {
                callbacks.onArmDestructiveAction(DestructiveSettingsAction.ForgetCertificate)
            },
            color = colors.destructive,
        )
    }
}

// ---------------------------------------------------------------------------
// Playing
// ---------------------------------------------------------------------------

/**
 * The pack's **Playing** block, plus the two rows that lead to the sub-screens.
 *
 * ## The two quality rows really are two controls
 *
 * Screen 12 draws "Stream quality: Original" and "Stream on mobile data: MP3 320" as two rows with
 * chevrons, and that is right: they are two independent ceilings, one per connection. For a while
 * the domain could only express "Original" or "Original on Wi-Fi, MP3 320 on mobile data", so this
 * screen drew one switch and a row that reported "Original" without offering anything. Both rows are
 * pickers now, over a ladder of Original, Opus 192/128/96 and MP3 320/256/192/128.
 *
 * REQUIREMENTS.md is still emphatic that original bytes are the default and that transcoding is a
 * scarce, shared resource - "the server allows one transcode per user and two in total, so a
 * household with two listeners can exhaust it" - which is why Wi-Fi defaults to Original and is
 * normally left there. A rung is also a *ceiling*: it never re-encodes a file that is already at or
 * below it, so picking MP3 320 on an MP3 320 library changes nothing at all.
 *
 * ## The picker expands in place
 *
 * The chips are drawn under the row rather than on a sub-screen because a sub-screen needs a
 * navigation destination, and this control is eight one-word choices. Only one picker is open at a
 * time: two open pickers put sixteen chips on screen and make it easy to set the wrong one.
 *
 * ## Both pickers disappear rather than greying out - but only when the server has said no
 *
 * REQUIREMENTS.md rule 3 of "Streaming": hide it entirely unless `transcoding:1` is advertised
 * *and* the server reports transcoding enabled. A disabled row invites a user to go looking for the
 * switch that would enable it, and on a server without ffmpeg there is nothing to find. What is left
 * in that case is one row reporting "Original", which is the truth on such a server: every rung
 * resolves to original bytes.
 *
 * **This branch used to also swallow the case where the server had not been asked**, which is how
 * the finished seven-rung ladder came to be unreachable on the device: `observeCapabilities` is null
 * until something negotiates, nothing negotiates on launch, so every restart drew the refusal.
 * [PlayingSectionState.streamQuality] now separates the two and
 * [StreamQualityAffordance.PICKERS_UNCONFIRMED] draws the pickers with [TRANSCODING_UNCONFIRMED]
 * above them. The reasoning is on [PlayingSectionState.transcodingNegotiated]; the underlying fix
 * belongs to `:core:data` and is not this module's to make.
 */
@Composable
private fun PlayingSection(
    state: PlayingSectionState,
    serverConfigured: Boolean,
    callbacks: SettingsCallbacks,
) {
    // Which rung picker is expanded, if either. Screen-local: it is not a preference, it does not
    // survive leaving the screen, and nothing else can act on it.
    var openPicker: StreamRungPicker? by remember { mutableStateOf<StreamRungPicker?>(null) }

    SettingsSection(title = "Playing") {
        NeedlerToggleRow(
            label = "Gapless playback",
            checked = state.gaplessEnabled,
            onCheckedChange = callbacks.onGaplessChange,
        )
        NeedlerSettingsRow(
            label = "Crossfade",
            value = state.crossfadeLabel,
            onClick = callbacks.onOpenCrossfade,
        )
        NeedlerSettingsRow(
            label = "Equaliser",
            value = state.equaliserLabel,
            onClick = callbacks.onOpenEqualiser,
        )
        val affordance: StreamQualityAffordance = state.streamQuality(serverConfigured)
        if (affordance == StreamQualityAffordance.STATEMENT) {
            // Nothing to choose between: without ffmpeg the server serves original bytes whatever it
            // is asked for. The row reports rather than offering, and carries no chevron, because a
            // chevron on a row that goes nowhere is the same lie as an inert tap target.
            //
            // The line under it is why there is one row here and two everywhere else. Without it the
            // screen collapses two settings into one and says nothing - see
            // PlayingSectionState.streamQualityStatement.
            NeedlerSettingsRow(label = "Stream quality", value = "Original", showDivider = false)
            NoticeLine(text = state.streamQualityStatement(serverConfigured))
            NeedlerHairline()
        } else {
            StreamRungRow(
                label = "Stream quality on Wi-Fi",
                selected = state.wifiRung,
                notice = state.wifiRungCacheNotice,
                expanded = openPicker == StreamRungPicker.WIFI,
                onToggleExpanded = {
                    openPicker =
                        if (openPicker == StreamRungPicker.WIFI) null else StreamRungPicker.WIFI
                },
                onSelect = callbacks.onWifiRungChange,
            )
            val unconfirmed: Boolean = affordance == StreamQualityAffordance.PICKERS_UNCONFIRMED
            StreamRungRow(
                label = "Stream quality on mobile data",
                selected = state.dataRung,
                notice = state.dataRungCacheNotice,
                expanded = openPicker == StreamRungPicker.DATA,
                onToggleExpanded = {
                    openPicker =
                        if (openPicker == StreamRungPicker.DATA) null else StreamRungPicker.DATA
                },
                onSelect = callbacks.onDataRungChange,
                // The block's closing hairline moves below the paragraph, so the paragraph stays
                // inside the block it belongs to instead of starting the next one.
                showDivider = !unconfirmed,
            )
            // Below the rows it is about, not above them. It used to sit between "Equaliser" and
            // "Stream quality on Wi-Fi", which read as an explanation of the equaliser - while the
            // cache paragraph 150px further down sat below its own row. Two conventions on one
            // screen is no convention; this file now has one, and it is that an explanation follows
            // the thing it explains.
            if (unconfirmed) {
                NoticeLine(text = TRANSCODING_UNCONFIRMED)
                NeedlerHairline()
            }
        }
        NeedlerToggleRow(
            label = state.scrobbleLabel,
            checked = state.scrobblingEnabled,
            onCheckedChange = callbacks.onScrobblingChange,
            subtitle = state.scrobbleSubtitle,
            showDivider = false,
        )
    }
}

/** Which of the two rung pickers is open. Screen-local state, not a preference. */
private enum class StreamRungPicker { WIFI, DATA }

/**
 * The line above the pickers when the server has not been asked whether it can re-encode.
 *
 * It reports the uncertainty rather than withholding the control over it, which is the whole point:
 * both rungs are local preferences and a rung is a **ceiling**, so setting one can never produce a
 * request a server rejects. The sentence says what is saved and what is conditional, and does not
 * ask the user to do anything - there is nothing for them to do, and the next successful negotiation
 * removes the line.
 *
 * It avoids blaming the connection, because an unasked server is not an offline one: the capability
 * set is negotiated at sign-in and not re-read on launch, so this appears on a perfectly healthy
 * network.
 */
private const val TRANSCODING_UNCONFIRMED: String =
    "Needler has not asked this server whether it can re-encode since it started. Both settings " +
        "are saved either way, and a rung below Original only changes anything on a server that can."

/**
 * One rung picker: a row that names its current rung, and the ladder underneath it when tapped.
 *
 * The chips scroll horizontally rather than wrapping. Eight rungs wrap to three lines at 200% text,
 * and a settings section that changes height by three lines when a row is tapped loses the reader's
 * place; a scrolling strip keeps the row where it was. The selected chip is the `selected` variant of
 * [NeedlerPillButton], which is a filled fill rather than a colour change, so the choice is not
 * carried by hue.
 *
 * @param notice the cache-cliff sentence for this rung, or null when the rung keeps its bytes. It sits
 *   on the row as a subtitle, where it is visible without opening the picker - the point of it is to
 *   be read by someone who is *not* currently thinking about caching.
 *
 *   It is drawn in [app.needler.core.design.theme.NeedlerColors.textSecondary], not `textMuted`, and
 *   that is a decision about what the tier is *for* rather than about what it measures. `textMuted`
 *   is the palette's dimmest text role - placeholders, timecodes, disabled labels - all of which are
 *   short and none of which a user has to finish reading. This is the longest piece of prose on the
 *   screen and the only explanation of why streaming at a lower rung builds no offline library, so it
 *   is read end to end or it is wasted.
 *
 *   The same went for the rest of the long copy on these two screens: the certificate fingerprint, the
 *   update-check result, the About disclaimer and the Downloaded-albums explainer were all on the
 *   muted tier and are all on the secondary one now.
 *
 * @param showDivider false when the caller is drawing something after this row that belongs to the
 *   same block, and will close the block itself.
 */
@Composable
private fun StreamRungRow(
    label: String,
    selected: StreamRung,
    notice: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelect: (StreamRung) -> Unit,
    showDivider: Boolean = true,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(modifier = Modifier.fillMaxWidth()) {
        NeedlerSettingsRow(
            label = label,
            value = SettingsFormat.rung(selected),
            onClick = onToggleExpanded,
            showDivider = showDivider && notice == null && !expanded,
        )
        if (notice != null) {
            Text(
                text = notice,
                style = typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StreamRung.entries.forEach { rung ->
                    NeedlerPillButton(
                        text = SettingsFormat.rung(rung),
                        onClick = { onSelect(rung) },
                        selected = rung == selected,
                        contentDescription = label + ", " + SettingsFormat.spokenRung(rung),
                    )
                }
            }
        }
        if (showDivider && (notice != null || expanded)) NeedlerHairline()
    }
}

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

@Composable
private fun NotificationsSection(state: NotificationSectionState, callbacks: SettingsCallbacks) {
    SettingsSection(title = "Notifications") {
        NeedlerToggleRow(
            label = "Pull finished",
            checked = state.pullFinished,
            onCheckedChange = callbacks.onNotifyPullFinishedChange,
        )
        NeedlerToggleRow(
            label = "Pull failed",
            checked = state.pullFailed,
            onCheckedChange = callbacks.onNotifyPullFailedChange,
        )
        NeedlerToggleRow(
            label = "New release from a followed artist",
            checked = state.newReleaseFromFollowedArtist,
            onCheckedChange = callbacks.onNotifyNewReleaseChange,
            showDivider = false,
        )
    }
}

// ---------------------------------------------------------------------------
// Storage
// ---------------------------------------------------------------------------

/**
 * Usage split by tier, the low-space warning, and the two toggles that survived the rewrite.
 *
 * REQUIREMENTS.md replaced screen 12's "Music kept on device: 2.1 GB" and "Device storage limit:
 * 4 GB" with a split, because the two tiers obey opposite rules and one figure could not describe
 * both: **Device** is unlimited and never evicted, while **Temporary** - what listening left
 * behind - is bounded by the device's free space. The two words are REQUIREMENTS.md
 * "Vocabulary" again: the tier a user chose is the same word the album screen badges, and the
 * one they did not choose says how long it lasts. Artwork gets its own line "because it has its own small LRU and is usually
 * tiny; a user hunting for gigabytes should not spend a tap on it", and free space sits beside them
 * because with no limit in the product it is the only thing left to compare against.
 *
 * The Wi-Fi-only toggle carries a subtitle the pack does not draw. It is the one place in the app
 * where the correction REQUIREMENTS.md insisted on can be explained to the person it affects:
 * leaving the setting under Pulling "would teach users that requesting music costs them data, which
 * is false".
 */
@Composable
private fun StorageSection(state: StorageSectionState, callbacks: SettingsCallbacks) {
    SettingsSection(title = "Storage") {
        NeedlerSettingsRow(label = "Device", value = state.downloadedLabel)
        NeedlerSettingsRow(label = "Temporary", value = state.cachedLabel)
        NeedlerSettingsRow(label = "Artwork", value = state.artworkLabel)
        NeedlerSettingsRow(label = "Free on this device", value = state.deviceFreeLabel)

        val lowOnSpace: String? = state.lowOnSpaceMessage
        if (lowOnSpace != null) {
            NoticeLine(text = lowOnSpace, tone = NeedlerTheme.colors.destructive)
        }

        NeedlerToggleRow(
            label = "Keep pulled albums on the device",
            checked = state.keepPulledAlbumsOnDevice,
            onCheckedChange = callbacks.onKeepPulledAlbumsChange,
            // Reads the switch rather than describing one of its two states and hoping. See
            // StorageSectionState.keepPulledAlbumsSubtitle.
            subtitle = state.keepPulledAlbumsSubtitle,
        )
        NeedlerToggleRow(
            label = "Download to device on Wi-Fi only",
            checked = state.downloadToDeviceOnWifiOnly,
            onCheckedChange = callbacks.onWifiOnlyDownloadsChange,
            subtitle = "Asking your server to find music costs almost no data; downloading it here does.",
            showDivider = false,
        )
    }
}

/**
 * The two actions that free space, and whichever confirmation is armed.
 *
 * "Clear cached music" is one tap by instruction: REQUIREMENTS.md calls it "safe behind a single
 * tap, because those bytes are re-fetchable and were never explicitly asked for". "Remove all from
 * device" is two, and is drawn in the destructive colour that was added to the palette for it - the
 * pack drew it in the same accent blue as Connect and Play, which styled permanent data loss
 * exactly like the primary action.
 */
@Composable
private fun StorageActions(
    state: StorageSectionState,
    armedAction: DestructiveSettingsAction?,
    callbacks: SettingsCallbacks,
) {
    val colors = NeedlerTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        val notice: String? = state.notice
        if (notice != null) NoticeLine(text = notice)

        NeedlerTextButton(
            text = "Clear cached music",
            onClick = callbacks.onClearCachedMusic,
            enabled = state.canClearCache,
        )

        if (armedAction == DestructiveSettingsAction.RemoveAllFromDevice) {
            DestructiveConfirmation(
                action = DestructiveSettingsAction.RemoveAllFromDevice,
                onConfirm = callbacks.onConfirmDestructiveAction,
                onCancel = callbacks.onCancelDestructiveAction,
            )
        } else {
            NeedlerTextButton(
                text = "Remove all from device",
                onClick = {
                    callbacks.onArmDestructiveAction(DestructiveSettingsAction.RemoveAllFromDevice)
                },
                enabled = state.canRemoveAll,
                color = colors.destructive,
            )
        }
    }
}

// `DownloadedAlbumRow` moved to DownloadsScreen.kt with the list it draws. It is still called from
// this file's fallback path, which is why it is `internal` rather than private to that one.

// ---------------------------------------------------------------------------
// About
// ---------------------------------------------------------------------------

/**
 * The version, and the block of legal copy the pack ends screen 12 with.
 *
 * The copy is the pack's, verbatim, because it is not decoration: it says that Needler is an
 * independent client for a server the user runs, that it hosts and transmits nothing itself, and
 * that what is searched for and downloaded is the user's responsibility. REQUIREMENTS.md's opening
 * makes the same point about the product's shape, and rewording any of it here would be a legal
 * change made by a UI file.
 *
 * It is read from [NeedlerLegal] rather than held here, because the Licences screen shows the same
 * four paragraphs - they are what "Licences and full terms" promises - and a legal statement that
 * exists twice in a codebase is a legal statement that will eventually say two different things.
 * REQUIREMENTS.md "Legal and attribution" calls this text "a requirement rather than decoration",
 * and a requirement with two copies has no single answer to "what does it say".
 *
 * The one addition is the version row. The pack draws "Needler 0.1" inside the legal block; a row
 * at the top of the section is where a person actually looks for a version number, and it carries
 * the build number too, which is the only thing that distinguishes two builds of the same release.
 */
@Composable
private fun AboutSection(
    state: AboutSectionState,
    onCheckForUpdates: () -> Unit,
    onOpenLicences: (() -> Unit)?,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    SettingsSection(title = "About") {
        NeedlerSettingsRow(label = "Version", value = state.versionLabel, showDivider = true)

        // A button, not a row. This was the one row in the file that was tappable with no chevron,
        // which is the idiom this screen's header rules out: it does something here and now rather
        // than leading anywhere, so it is drawn the way "Sync now" and "Clear cached music" are.
        NeedlerTextButton(
            text = "Check for updates",
            onClick = onCheckForUpdates,
            enabled = state.updateCheck != UpdateCheckStatus.Checking,
        )
        state.updateCheck.message?.let { message ->
            Text(
                text = message,
                style = typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = spacing.step2, bottom = spacing.step4),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.step6),
            verticalArrangement = Arrangement.spacedBy(spacing.step3),
        ) {
            Text(
                text = buildAnnotatedString {
                    append(NeedlerLegal.CREDITS_PREFIX)
                    NeedlerLegal.credits.forEachIndexed { index, name ->
                        withStyle(SpanStyle(color = colors.textSecondary)) { append(name) }
                        if (index < NeedlerLegal.credits.lastIndex) append(" · ")
                    }
                    append(NeedlerLegal.CREDITS_SUFFIX)
                },
                style = typography.caption,
                color = colors.textSecondary,
            )
            NeedlerLegal.disclaimer.forEach { paragraph ->
                Text(text = paragraph, style = typography.caption, color = colors.textSecondary)
            }
            if (onOpenLicences != null) {
                NeedlerTextButton(text = "Licences and full terms", onClick = onOpenLicences)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sign out
// ---------------------------------------------------------------------------

@Composable
private fun SignOutBlock(
    notice: String?,
    armed: Boolean,
    callbacks: SettingsCallbacks,
) {
    val spacing = NeedlerTheme.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = spacing.step12),
        verticalArrangement = Arrangement.spacedBy(spacing.step2),
    ) {
        if (notice != null) NoticeLine(text = notice, tone = NeedlerTheme.colors.destructive)
        if (armed) {
            DestructiveConfirmation(
                action = DestructiveSettingsAction.SignOut,
                onConfirm = callbacks.onConfirmDestructiveAction,
                onCancel = callbacks.onCancelDestructiveAction,
            )
        } else {
            NeedlerSecondaryButton(
                text = "Sign out",
                onClick = { callbacks.onArmDestructiveAction(DestructiveSettingsAction.SignOut) },
                modifier = Modifier.fillMaxWidth(),
                size = NeedlerButtonSize.Medium,
                // The pack's Sign out is transparent with a hairline, not the filled surface the
                // album-detail buttons wear.
                filledSurface = false,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/**
 * A block of rows under an overline, spaced from whatever came before it.
 *
 * The gap lives on the section rather than on the list so that the rows inside a section stay flush
 * against one another - the pack separates them with a `border-bottom` hairline drawn by each row,
 * and a gap would leave every hairline floating between two rows instead of under one.
 */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = NeedlerTheme.spacing.sectionGap),
    ) {
        NeedlerSectionHeader(title = title)
        content()
    }
}

/**
 * One line of plain prose inside a section: what an action did, or what is wrong.
 *
 * A polite live region, so a screen-reader user who has just removed an album hears what it freed.
 * Without it the figure changes silently and the only feedback for a destructive action is a number
 * further up the screen that the reader is not looking at.
 */
@Composable
private fun NoticeLine(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = NeedlerTheme.colors.textSecondary,
) {
    Text(
        text = text,
        style = NeedlerTheme.typography.caption,
        color = tone,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The second tap, in place of the row that armed it.
 *
 * Inline rather than a dialog because the pack draws no dialogs anywhere, and because an inline
 * confirmation can be rendered to a PNG and asserted on. The cancel is a full-sized, plainly
 * labelled control beside the confirm rather than a dismiss gesture: the way out of a destructive
 * confirmation should never be the harder of the two things to hit.
 */
@Composable
private fun DestructiveConfirmation(
    action: DestructiveSettingsAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = NeedlerTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = action.prompt,
            style = NeedlerTheme.typography.caption,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Assertive },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step8),
        ) {
            NeedlerTextButton(
                text = action.confirmLabel,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                color = colors.destructive,
            )
            NeedlerTextButton(
                text = "Cancel",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The screen's own title, as the four destinations all draw it. */
private const val TITLE: String = "Settings"

/**
 * The widest the content gets on a tablet.
 *
 * An inference, not a transcription: the pack has no tablet artboard for screen 12. 640dp is a
 * little over one and a half phone widths, which keeps a row's label and its switch within a glance
 * of each other on a 1280dp pane.
 *
 * `internal` so [DownloadsScreen] caps itself at the same width. A sub-screen of Settings that
 * measured differently from Settings would look like a different app on a tablet.
 */
internal val TABLET_CONTENT_MAX_WIDTH: Dp = 640.dp

