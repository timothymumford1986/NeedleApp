package app.needler.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerChevronDownIcon
import app.needler.core.design.component.NeedlerChevronRightIcon
import app.needler.core.design.component.NeedlerLabelledTextField
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerSegmentedTabs
import app.needler.core.design.component.NeedlerStrokeIcon
import app.needler.core.design.component.NeedlerTextButton
import app.needler.core.design.component.NeedlerWordmark
import app.needler.core.design.motion.NeedlerSpinningRecord
import app.needler.core.design.motion.needlerRise
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.network.ProxyCredentials

/**
 * The optional proxy section's callbacks, bundled.
 *
 * Five more lambdas threaded through three layout functions would be five more parameters on a
 * signature that is already long, for a section most users never open. Defaults are no-ops so a
 * preview or a screenshot can render any state without wiring any of them.
 */
data class ConnectProxyCallbacks(
    val onExpandedChange: (Boolean) -> Unit = {},
    val onPresetChange: (ProxyPreset) -> Unit = {},
    val onFieldChange: (ProxyField, String) -> Unit = { _, _ -> },
    val onCustomHeaderChange: (Int, String, String) -> Unit = { _, _, _ -> },
    val onAddCustomHeader: () -> Unit = {},
)

/**
 * Connect - screens 01 (phone) and 16 (tablet).
 *
 * Three fields and a button: server, username, password. The layout is the
 * pack's at both widths: the phone puts the wordmark and headline above the
 * form with the button pinned to the bottom; the tablet centres a 440dp card
 * beside a large spinning record.
 *
 * ## The third field says PASSWORD, not APP PASSWORD
 *
 * The pack labels it `APP PASSWORD` and REQUIREMENTS.md overrules that at
 * length. An app-password cannot authenticate the `/api/v1` lane at all: the
 * bearer middleware validates only against the server's auth-tokens table,
 * while app-passwords live in a separate store that only the Subsonic and
 * Jellyfin shims consult. A user who typed an app-password here would end up
 * with a working music player and no search, no pull and no queue - most of the
 * product missing, with nothing on screen to say why.
 *
 * So the field takes the account password, the helper text says so in the
 * user's own words, and Needler mints the app-password itself afterwards. The
 * user never sees one. Nothing else about the field changes.
 *
 * ## Failures
 *
 * Every failure is rendered inline, above the form, by
 * `ConnectFailureNotice` - not in a dialog, because all of them are answered
 * either by changing something in the form or by doing something outside the
 * app, and a dialog helps with neither. The untrusted-certificate case is the
 * one that carries an action, and it shows the fingerprint, subject, issuer and
 * expiry before offering it.
 *
 * The screen is stateless: [ConnectRoute] owns the state and the repository, so
 * every state here can be rendered from a literal in a test or a screenshot.
 */
@Composable
fun ConnectScreen(
    state: ConnectUiState,
    widthSizeClass: WindowWidthSizeClass,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onTrustCertificate: (CertificateInfo) -> Unit,
    modifier: Modifier = Modifier,
    onCancelConnect: () -> Unit = {},
    proxyCallbacks: ConnectProxyCallbacks = ConnectProxyCallbacks(),
) {
    if (widthSizeClass == WindowWidthSizeClass.Expanded) {
        TabletConnect(
            state = state,
            onServerChange = onServerChange,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            onConnect = onConnect,
            onCancelConnect = onCancelConnect,
            onTrustCertificate = onTrustCertificate,
            proxyCallbacks = proxyCallbacks,
            modifier = modifier,
        )
    } else {
        PhoneConnect(
            state = state,
            onServerChange = onServerChange,
            onUsernameChange = onUsernameChange,
            onPasswordChange = onPasswordChange,
            onConnect = onConnect,
            onCancelConnect = onCancelConnect,
            onTrustCertificate = onTrustCertificate,
            proxyCallbacks = proxyCallbacks,
            modifier = modifier,
        )
    }
}

/**
 * Screen 01: 72dp above the wordmark, 24dp gutters, Connect pinned to the
 * bottom.
 *
 * The header and form scroll; the button does not. That is deliberate rather
 * than decorative - REQUIREMENTS.md requires text to scale to 200% without
 * clipping, and at that scale this form is taller than an 844dp phone. Putting
 * the button outside the scroll area keeps the pack's layout at normal scale
 * and keeps the action reachable at large ones.
 */
@Composable
private fun PhoneConnect(
    state: ConnectUiState,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onCancelConnect: () -> Unit,
    onTrustCertificate: (CertificateInfo) -> Unit,
    proxyCallbacks: ConnectProxyCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            // Edge-to-edge: keep clear of the system bars, and of the keyboard
            // when the password field has focus.
            .safeDrawingPadding()
            .imePadding()
            .padding(start = 24.dp, end = 24.dp, top = 72.dp, bottom = 40.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.step16),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.step14)) {
                NeedlerWordmark(modifier = Modifier.needlerRise(stagger = 0))
                Text(
                    text = HEADLINE,
                    style = typography.display,
                    color = colors.textPrimary,
                    modifier = Modifier.needlerRise(stagger = 1),
                )
            }

            // Above the form, not below it. The notice for an untrusted
            // certificate carries a fingerprint the user is being asked to
            // check and a button that pins it, and at the foot of a scrolling
            // form both sit below the fold - a security decision the user never
            // sees they are being offered. Putting it here also reads correctly
            // for the failures whose answer is in a field: the explanation
            // comes before the thing to change.
            if (state.failure != null) {
                ConnectFailureNotice(
                    failure = state.failure,
                    onTrustCertificate = onTrustCertificate,
                    onRetry = onConnect,
                    retryEnabled = state.canConnect,
                )
            }

            ConnectForm(
                state = state,
                onServerChange = onServerChange,
                onUsernameChange = onUsernameChange,
                onPasswordChange = onPasswordChange,
                onConnect = onConnect,
                modifier = Modifier.needlerRise(stagger = 2),
            )

            ProxySection(
                proxy = state.proxy,
                callbacks = proxyCallbacks,
                enabled = !state.connecting,
            )
        }

        Spacer(modifier = Modifier.height(spacing.step8))

        ConnectActions(
            state = state,
            onConnect = onConnect,
            onCancelConnect = onCancelConnect,
            modifier = Modifier.needlerRise(stagger = 3),
        )
    }
}

/**
 * Screen 16: the record and the card side by side, centred in the window.
 *
 * The pack's backdrop is a CSS radial gradient from
 * [app.needler.core.design.theme.NeedlerColors.backdropGlow] to the canvas. It
 * is drawn here as a soft disc behind the record, which is the only place the
 * gradient is bright enough to read.
 */
@Composable
private fun TabletConnect(
    state: ConnectUiState,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    onCancelConnect: () -> Unit,
    onTrustCertificate: (CertificateInfo) -> Unit,
    proxyCallbacks: ConnectProxyCallbacks,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .safeDrawingPadding()
            .imePadding()
            .padding(spacing.tabletGutter),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(80.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(480.dp)
                    .clip(NeedlerTheme.shapes.circle)
                    .background(colors.backdropGlow),
                contentAlignment = Alignment.Center,
            ) {
                NeedlerSpinningRecord(
                    playing = true,
                    modifier = Modifier.size(480.dp),
                )
            }

            Column(
                modifier = Modifier
                    .width(440.dp)
                    .needlerRise(stagger = 1)
                    .clip(NeedlerTheme.shapes.card)
                    .background(colors.surfaceTranslucentSoft)
                    .border(
                        width = NeedlerTheme.sizes.hairlineThickness,
                        color = colors.hairline,
                        shape = NeedlerTheme.shapes.card,
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.step14),
            ) {
                NeedlerWordmark(
                    textStyle = typography.wordmarkCompact,
                    markSize = 30.dp,
                )
                Text(
                    text = HEADLINE,
                    style = typography.displayCompact,
                    color = colors.textPrimary,
                )
                if (state.failure != null) {
                    ConnectFailureNotice(
                        failure = state.failure,
                        onTrustCertificate = onTrustCertificate,
                        onRetry = onConnect,
                        retryEnabled = state.canConnect,
                    )
                }
                ConnectForm(
                    state = state,
                    onServerChange = onServerChange,
                    onUsernameChange = onUsernameChange,
                    onPasswordChange = onPasswordChange,
                    onConnect = onConnect,
                )
                ProxySection(
                    proxy = state.proxy,
                    callbacks = proxyCallbacks,
                    enabled = !state.connecting,
                )
                ConnectActions(
                    state = state,
                    onConnect = onConnect,
                    onCancelConnect = onCancelConnect,
                )
            }
        }
    }
}

/** The three fields, identical at both widths. */
@Composable
private fun ConnectForm(
    state: ConnectUiState,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step9),
    ) {
        NeedlerLabelledTextField(
            label = "SERVER",
            value = state.server,
            onValueChange = onServerChange,
            placeholder = "https://music.yourhome.net",
            helperText = "A host name, an IP and port, or a sub-path all work.",
            enabled = !state.connecting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
        )
        NeedlerLabelledTextField(
            label = "USERNAME",
            value = state.username,
            onValueChange = onUsernameChange,
            placeholder = "yourname",
            enabled = !state.connecting,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
                autoCorrectEnabled = false,
            ),
        )
        NeedlerLabelledTextField(
            // REQUIREMENTS.md "Required change to the Connect screen": the pack
            // says APP PASSWORD and it cannot work. See the file header.
            label = "PASSWORD",
            value = state.password,
            onValueChange = onPasswordChange,
            placeholder = PASSWORD_PLACEHOLDER,
            helperText = "your Dropped Needle account password",
            enabled = !state.connecting,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { if (state.canConnect) onConnect() }),
        )
    }
}

/**
 * The pack's 56dp accent button, with its tonearm-and-record glyph - plus the two things an attempt
 * in flight has to offer.
 *
 * The bug this replaces: a real Cloudflare Access server left this screen reading "Connecting…" for
 * forty-five seconds, with the button disabled, nothing moving and no way out. So an attempt now
 * always has a way to stop it, and after a few seconds it says out loud that it is still trying.
 * That is the floor for any attempt, whatever the cause - a slow VPN handshake produces the same
 * dead screen as an interception.
 *
 * The notice is a polite live region rather than an assertive one: it is reassurance, and it must
 * not interrupt a screen reader that is reading the form.
 */
@Composable
private fun ConnectActions(
    state: ConnectUiState,
    onConnect: () -> Unit,
    onCancelConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NeedlerTheme.spacing.step4),
    ) {
        if (state.connecting && state.attemptIsSlow) {
            Text(
                text = STILL_TRYING,
                style = typography.caption,
                color = colors.textMuted,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        NeedlerPrimaryButton(
            text = if (state.connecting) "Connecting…" else "Connect",
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canConnect,
            leadingIcon = { tint ->
                NeedlerStrokeIcon(
                    pathData = "M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4",
                    tint = tint,
                    size = 20.dp,
                )
            },
            contentDescription = if (state.connecting) {
                "Connecting to the server"
            } else {
                "Connect to the server"
            },
        )

        if (state.connecting) {
            NeedlerSecondaryButton(
                text = "Stop",
                onClick = onCancelConnect,
                modifier = Modifier.fillMaxWidth(),
                filledSurface = false,
                contentDescription = "Stop trying to connect",
            )
        }
    }
}

/**
 * The optional fields for a server behind an authenticating proxy, behind a disclosure.
 *
 * Collapsed by default, and the label is vendor-neutral. Almost nobody has a proxy in front of
 * their server, and a form that asks about one up front teaches every user that this app is
 * complicated to set up. The people who do have one arrive here from the failure notice above,
 * which opens this section for them.
 *
 * Inside, the presets exist because the mechanism is not what a user knows: a Cloudflare user made
 * a *service token*, a user behind `nginx` set up a password. Both are headers underneath, and the
 * transport knows nothing about either - see `ProxyCredentials`.
 */
@Composable
private fun ProxySection(
    proxy: ProxyFormState,
    callbacks: ConnectProxyCallbacks,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.step9),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = NeedlerTheme.sizes.minTouchTarget)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = { callbacks.onExpandedChange(!proxy.expanded) },
                )
                .semantics {
                    contentDescription = if (proxy.expanded) {
                        "Hide proxy credentials"
                    } else {
                        "Show proxy credentials"
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (proxy.expanded) {
                NeedlerChevronDownIcon(tint = colors.accent)
            } else {
                NeedlerChevronRightIcon(tint = colors.accent)
            }
            Text(text = PROXY_DISCLOSURE, style = typography.body, color = colors.accent)
        }

        if (!proxy.expanded) return@Column

        Text(
            text = PROXY_EXPLANATION,
            style = typography.caption,
            color = colors.textMuted,
        )

        NeedlerSegmentedTabs(
            options = ProxyPreset.entries.map { it.label },
            selectedIndex = ProxyPreset.entries.indexOf(proxy.preset),
            onSelect = { index -> callbacks.onPresetChange(ProxyPreset.entries[index]) },
            label = "Kind of proxy credential",
            enabled = enabled,
        )

        when (proxy.preset) {
            ProxyPreset.CloudflareAccess -> {
                NeedlerLabelledTextField(
                    label = "CF-ACCESS-CLIENT-ID",
                    value = proxy.cloudflareClientId,
                    onValueChange = { callbacks.onFieldChange(ProxyField.CloudflareClientId, it) },
                    placeholder = "0123abc….access",
                    helperText = "From a Cloudflare Access service token.",
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                )
                NeedlerLabelledTextField(
                    label = "CF-ACCESS-CLIENT-SECRET",
                    value = proxy.cloudflareClientSecret,
                    onValueChange = {
                        callbacks.onFieldChange(ProxyField.CloudflareClientSecret, it)
                    },
                    placeholder = PASSWORD_PLACEHOLDER,
                    helperText = "Kept on this device only, with your other credentials.",
                    enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                )
            }

            ProxyPreset.BasicAuth -> {
                NeedlerLabelledTextField(
                    label = "PROXY USERNAME",
                    value = proxy.basicUsername,
                    onValueChange = { callbacks.onFieldChange(ProxyField.BasicUsername, it) },
                    placeholder = "yourname",
                    helperText = "Sent as Proxy-Authorization, not as your server login.",
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                )
                NeedlerLabelledTextField(
                    label = "PROXY PASSWORD",
                    value = proxy.basicPassword,
                    onValueChange = { callbacks.onFieldChange(ProxyField.BasicPassword, it) },
                    placeholder = PASSWORD_PLACEHOLDER,
                    enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                )
            }

            ProxyPreset.Custom -> {
                proxy.customHeaders.forEachIndexed { index, draft ->
                    NeedlerLabelledTextField(
                        label = "HEADER " + (index + 1) + " NAME",
                        value = draft.name,
                        onValueChange = {
                            callbacks.onCustomHeaderChange(index, it, draft.value)
                        },
                        placeholder = "X-Api-Key",
                        enabled = enabled,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Next,
                            autoCorrectEnabled = false,
                        ),
                    )
                    NeedlerLabelledTextField(
                        label = "HEADER " + (index + 1) + " VALUE",
                        value = draft.value,
                        onValueChange = {
                            callbacks.onCustomHeaderChange(index, draft.name, it)
                        },
                        placeholder = PASSWORD_PLACEHOLDER,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Next,
                            autoCorrectEnabled = false,
                        ),
                    )
                }
                if (proxy.customHeaders.size < ProxyCredentials.MAX_HEADERS) {
                    NeedlerTextButton(
                        text = "Add another header",
                        onClick = callbacks.onAddCustomHeader,
                        enabled = enabled,
                    )
                }
            }
        }

        if (proxy.problem != null) {
            Text(
                text = proxy.problem,
                style = typography.caption,
                color = colors.accent,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

/**
 * The failure notice: a title, a body and - where there is something to do - an
 * action.
 *
 * Marked as an assertive live region, so a screen reader announces it when it
 * appears. Without that the whole failure story is sighted-only, which would
 * make every case below useless to the people who most need the explanation.
 */
@Composable
private fun ConnectFailureNotice(
    failure: ConnectFailure,
    onTrustCertificate: (CertificateInfo) -> Unit,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    val spacing = NeedlerTheme.spacing

    // The certificate cases are the two where getting it wrong is a security
    // problem rather than an inconvenience, so they wear the destructive colour
    // REQUIREMENTS.md added to the palette; everything else is the accent.
    val edge = when (failure) {
        is ConnectFailure.UntrustedCertificate,
        is ConnectFailure.CertificateChanged,
        -> colors.destructive
        else -> colors.accent
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NeedlerTheme.shapes.medium)
            .background(colors.surface)
            .border(
                width = NeedlerTheme.sizes.hairlineThickness,
                color = edge,
                shape = NeedlerTheme.shapes.medium,
            )
            .padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(spacing.step4),
    ) {
        Text(text = failure.title, style = typography.rowTitle, color = edge)
        Text(text = failure.detail, style = typography.body, color = colors.textSecondary)

        when (failure) {
            is ConnectFailure.UntrustedCertificate -> {
                CertificateDetail(failure.certificate)
                NeedlerSecondaryButton(
                    text = "Trust this certificate",
                    onClick = { onTrustCertificate(failure.certificate) },
                    modifier = Modifier.fillMaxWidth(),
                    filledSurface = false,
                    contentDescription = "Trust this certificate for this server only",
                )
            }

            is ConnectFailure.CertificateChanged -> {
                CertificateDetail(failure.presented)
                Text(
                    text = "Previously trusted: " + failure.expectedFingerprint,
                    style = typography.meta,
                    color = colors.textMuted,
                )
            }

            // Three failures whose answer is "change something outside this screen, then retry":
            // an admin setting, a proxy rule or credential, and an attempt the user stopped.
            ConnectFailure.SubsonicDisabled,
            is ConnectFailure.ProxyIntercepted,
            ConnectFailure.Cancelled,
            -> {
                NeedlerSecondaryButton(
                    text = "Try again",
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = retryEnabled,
                    filledSurface = false,
                )
            }

            else -> Unit
        }
    }
}

/**
 * Fingerprint, subject, issuer and expiry - what REQUIREMENTS.md requires to be
 * on screen before a certificate can be pinned.
 *
 * The fingerprint is monospaced because it is the one value a user is expected
 * to compare character by character against what their server reports, and a
 * proportional font makes that harder than it needs to be.
 */
@Composable
private fun CertificateDetail(certificate: CertificateInfo, modifier: Modifier = Modifier) {
    val colors = NeedlerTheme.colors
    val typography = NeedlerTheme.typography
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = "SHA-256", style = typography.sectionHeader, color = colors.textSecondary)
        Text(
            text = certificate.sha256Fingerprint,
            style = typography.meta.copy(fontFamily = FontFamily.Monospace),
            color = colors.textPrimary,
        )
        Text(text = certificate.subject, style = typography.meta, color = colors.textSecondary)
        Text(
            text = "Issued by " + certificate.issuer,
            style = typography.meta,
            color = colors.textMuted,
        )
        val expiry = certificate.notAfter
        if (expiry != null) {
            Text(
                text = "Expires " + expiry,
                style = typography.meta,
                color = colors.textMuted,
            )
        }
    }
}

/** The pack's headline, on both screens. */
private const val HEADLINE = "Point me at your Dropped Needle."

/** The pack draws sixteen bullets in the password field. */
private const val PASSWORD_PLACEHOLDER = "••••••••••••••••"

/**
 * Vendor-neutral on purpose. Cloudflare Access is the common case, but Authelia, authentik,
 * `oauth2-proxy` and a plain basic-auth reverse proxy are the same wall, and a label naming one
 * vendor reads as "not for me" to everyone behind another.
 */
private const val PROXY_DISCLOSURE = "My server is behind a proxy that needs its own credentials"

private const val PROXY_EXPLANATION =
    "Needler will send these with every request, including artwork and audio. They are kept on " +
        "this device with your other credentials and never shown again."

private const val STILL_TRYING = "Still trying to reach the server…"

