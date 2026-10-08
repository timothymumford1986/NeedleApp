package app.needler.connect

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.component.NeedlerButtonSize
import app.needler.core.design.component.NeedlerChevronDownIcon
import app.needler.core.design.component.NeedlerChevronRightIcon
import app.needler.core.design.component.NeedlerLabelledTextField
import app.needler.core.design.component.NeedlerPrimaryButton
import app.needler.core.design.component.NeedlerSecondaryButton
import app.needler.core.design.component.NeedlerSecretTextField
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
 * ## No secret field carries a placeholder
 *
 * The pack draws sixteen bullets inside the password field, and that was
 * transcribed literally as a `placeholder`. It is wrong twice over, which is why
 * it is gone from all four secret fields here - the account password, the
 * Cloudflare client secret, the proxy password and each custom header value.
 *
 * It is wrong on screen, because a placeholder is what an *empty* field shows:
 * sixteen bullets are exactly what a filled password field looks like, so the
 * one field on this screen that must be obviously empty before the user types
 * was the one field that looked obviously full.
 *
 * It is wrong for a screen reader, because a placeholder is a text node inside
 * the field's decoration. The account password field therefore announced its
 * label and then sixteen bullet characters, which TalkBack reads out as sixteen
 * bullets, in place of the guidance a field's supporting text exists to give.
 *
 * Masking is not a placeholder's job and never was: `PasswordVisualTransformation`
 * does it, on the characters the user actually typed. What is left under the
 * field is the thing REQUIREMENTS.md "Required change to the Connect screen"
 * asks for - the label `PASSWORD` and the helper text naming the user's own
 * words for the secret - which is what someone hesitating over this field needs
 * to read. It is sentence case with a full stop, because the three other helper
 * lines on this screen are and a form that capitalises one field and not the
 * next looks like two people wrote it.
 *
 * Nothing about the keyboard changes: every field keeps its `KeyboardType`,
 * its `ImeAction`, its disabled auto-correct and its place in the focus order.
 *
 * ## Every secret field can be revealed
 *
 * All four are `NeedlerSecretTextField`, which adds the reveal toggle. Masked
 * with no way to look was a guessing game on exactly the screen a locked-out
 * user reaches, with a phone keyboard and often a generated secret.
 *
 * The proxy fields get the toggle too, and they are the stronger case rather
 * than the afterthought. A Cloudflare Access service-token secret is 64 hex
 * characters that nobody has memorised, and a single wrong character there
 * produces a proxy 401 - which is the failure
 * [ConnectFailure.ProxyIntercepted] exists because users read as a wrong
 * server password. Letting the user check the thing they pasted is the cheapest
 * way to tell those two apart. One component for all four also means one pair
 * of content descriptions and one touch-target rule, instead of four chances to
 * get the accessible version of this wrong.
 *
 * The toggle is not a return of the bullets: it is a sibling control with its
 * own label, outside the field's decoration, and `NeedlerSecretTextField` takes
 * no `placeholder` parameter at all so none can be passed to it.
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
 *
 * ## The two things that cost
 *
 * A pinned button means the scroll area ends in a hard horizontal cut, and the
 * cut lands wherever the content happens to be. Every failure state put a field
 * label across it - `PASSWORD` sliced through the middle of its letters,
 * `USERNAME` with the top of its box showing - and with nothing marking the
 * boundary the form read as *amputated* rather than as *continuing below*. That
 * is what [ScrollEdgeFade] is for, at both edges: the content now dissolves
 * into the canvas the button sits on, which is the one thing a cut cannot say.
 *
 * The second cost is the proxy section, which is last in the column and so is
 * the first thing the cut takes. A user who opens it sees the top two
 * millimetres of a segmented control and nothing else, which is a disclosure
 * that discloses nothing - so opening it scrolls it into view. See the effect
 * below for the one case that must not.
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
    val scroll: ScrollState = rememberScrollState()

    ScrollProxySectionIntoView(state, scroll)

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
        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier.verticalScroll(scroll),
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
                // check and a button that pins it, and at the foot of a
                // scrolling form both sit below the fold - a security decision
                // the user never sees they are being offered. Putting it here
                // also reads correctly for the failures whose answer is in a
                // field: the explanation comes before the thing to change.
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

            ScrollEdgeFade(scroll = scroll, edgeColor = colors.canvas)
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
 *
 * ## Connect is pinned here too
 *
 * A 1280x800 window leaves the card 720dp between the gutters, and the pack's
 * card is sized to its content - which is fine for three fields and wrong for
 * anything taller. An untrusted certificate, or an intercepting proxy with the
 * proxy fields open, overran it: the card ended flush with the bottom of the
 * viewport, mid-form, with no Connect button anywhere on a screen whose left
 * half was a decorative record. The same overrun ate the card's own 40dp bottom
 * padding while connecting, which is why `Stop` sat against the card's edge on
 * one screenshot and clear of it on the next.
 *
 * So the card scrolls its content and keeps [ConnectActions] outside that
 * scroll, exactly as [PhoneConnect] does. `weight(1f, fill = false)` is what
 * makes that cost nothing in the ordinary case: the card still shrinks to its
 * content when the content fits, so screen 16 at rest is unchanged, and only
 * takes the full height when there is more than will fit.
 *
 * The rejected alternative was widening the card. 480dp of record, 80dp of gap
 * and 80dp of gutters leave room to take it to 560dp, which would have cleared
 * the certificate panel - and still not the proxy fields, and not at 200% text,
 * and at the price of every tablet screenshot being re-drawn against a pack
 * that specifies 440dp.
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
    val scroll: ScrollState = rememberScrollState()

    ScrollProxySectionIntoView(state, scroll)

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
                    .padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(spacing.step14),
            ) {
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    Column(
                        modifier = Modifier.verticalScroll(scroll),
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
                    }

                    ScrollEdgeFade(scroll = scroll, edgeColor = colors.surfaceTranslucentSoft)
                }

                ConnectActions(
                    state = state,
                    onConnect = onConnect,
                    onCancelConnect = onCancelConnect,
                )
            }
        }
    }
}

/**
 * A soft edge on whichever side of a scroll area still has content beyond it.
 *
 * Both layouts end their scroll area in a straight horizontal cut, because both keep Connect
 * outside it. A cut with nothing on it is read as the end of the form, not as the middle of one:
 * the screenshots of this screen show `PASSWORD` severed through the letters and `USERNAME` with
 * the lid of its field showing, and neither looks like something a scroll would fix. A gradient to
 * the colour behind the cut says "under", which is the whole of the fix - there is no new control
 * here, nothing to tap and nothing for a screen reader to find, which is why this is drawn rather
 * than composed.
 *
 * Drawn on both edges and only when that edge can actually move, so the resting screen - the one
 * the design pack draws, with three fields and no failure - has neither.
 *
 * The extent is read rather than `canScrollForward`, and compared against `Int.MAX_VALUE` first.
 * That is the value `ScrollState` reports before anything has been measured, which is true of the
 * first composition of every one of these screens: `canScrollForward` is therefore *true* on a
 * screen that will turn out not to scroll at all, and a screenshot taken before the measurement
 * lands would record a fade across the bottom of a form that fits. Reading the extent directly also
 * keeps both edges recomposing with the scroll rather than a frame behind it.
 *
 * @param edgeColor what sits behind the cut: the canvas on the phone, the card's own translucent
 *   fill on the tablet. Fading to the wrong one would draw a band of the wrong colour over the
 *   record showing through the tablet card.
 */
@Composable
private fun BoxScope.ScrollEdgeFade(
    scroll: ScrollState,
    edgeColor: Color,
    height: Dp = 32.dp,
) {
    val extent: Int = scroll.maxValue
    if (extent == Int.MAX_VALUE) return

    if (scroll.value > 0) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(height)
                .background(Brush.verticalGradient(listOf(edgeColor, Color.Transparent))),
        )
    }
    if (scroll.value < extent) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(height)
                .background(Brush.verticalGradient(listOf(Color.Transparent, edgeColor))),
        )
    }
}

/**
 * Opening the proxy disclosure scrolls the section it opens into view.
 *
 * The section is last in the column at both widths, so the end of the scroll *is* the whole of it -
 * which is why this scrolls to the end rather than measuring the section's own bounds. It is a jump
 * rather than an animation on purpose: the user has just tapped a disclosure and is waiting to see
 * what came out of it, and Robolectric's clock does not advance for a screenshot, so an animated
 * version would be recorded at its first frame and the goldens would show the same two millimetres
 * of segmented control they showed before.
 *
 * ## The one case it is skipped in
 *
 * A failure notice on screen. It is an assertive live region and it is usually the thing that told
 * the user to open this section at all - `ConnectFailure.ProxyIntercepted` names the disclosure in
 * its own copy - so scrolling it off the top would take away the instruction at the moment it is
 * being followed. This is the same rule [ConnectForm]'s autofocus follows, for the same reason, and
 * the two must not disagree: the notice outranks anything that wants the user's position on the
 * form.
 *
 * Keyed on the flag rather than on the state, so typing in a proxy field does not re-scroll.
 */
@Composable
private fun ScrollProxySectionIntoView(state: ConnectUiState, scroll: ScrollState) {
    LaunchedEffect(state.proxy.expanded) {
        if (!state.proxy.expanded || state.failure != null) return@LaunchedEffect
        // maxValue is Int.MAX_VALUE until the first layout has measured the content; ScrollState
        // clamps the position down to the real extent as soon as it knows it.
        scroll.scrollTo(scroll.maxValue)
    }
}

/**
 * The three fields, identical at both widths - which is why the cursor is placed here rather than in
 * either layout branch.
 *
 * ## Autofocus, and the one case it must not fire in
 *
 * The screen used to open with no field focused (`mInputShown=false`), so a user who had just been
 * signed out had to tap before they could type. The cursor therefore goes into SERVER on arrival,
 * once: [LaunchedEffect] keyed on `Unit` reads the state as it was on the first composition and
 * never asks again, because a later request would steal focus from whichever field the user had
 * moved to by then.
 *
 * It is skipped while an attempt is in flight, and skipped when there is a failure notice on
 * screen. The notice is an assertive live region, and taking focus cuts its announcement off
 * mid-sentence - which would matter most in exactly the case that most often brings someone back to
 * this form, `ConnectFailure.SavedSessionLocked`: a user who cannot hear why their saved session was
 * not restored is left assuming they were never signed in, which is the defect that message exists
 * to remove. Saving a tap is not worth re-introducing it through the focus path.
 *
 * `runCatching`, because `requestFocus` throws if the node is not attached - a configuration change
 * landing between the composition and the effect - and a lost cursor is not worth a crash.
 */
@Composable
private fun ConnectForm(
    state: ConnectUiState,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val serverField: FocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (state.connecting || state.failure != null) return@LaunchedEffect
        runCatching { serverField.requestFocus() }
    }

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
            focusRequester = serverField,
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
        NeedlerSecretTextField(
            // REQUIREMENTS.md "Required change to the Connect screen": the pack
            // says APP PASSWORD and it cannot work. See the file header.
            label = "PASSWORD",
            value = state.password,
            onValueChange = onPasswordChange,
            secretName = "Password",
            helperText = "Your Dropped Needle account password.",
            enabled = !state.connecting,
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
 *
 * ## Connect stands down while a certificate is waiting to be trusted
 *
 * `ConnectFailure.UntrustedCertificate` is the one state on this screen where pressing Connect
 * cannot work - the handshake will fail again on the same certificate - and it was the loudest
 * thing on it: a filled accent button below a transparent outlined `Trust this certificate`, so the
 * eye was drawn past the decision to the button that depends on it. Emphasis follows the order the
 * two have to be pressed in, so while the panel is up Connect takes the outline and
 * [ConnectFailureNotice] gives the fill to the trust action. Nothing is disabled: the user may
 * still press it, and the same certificate panel comes back.
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
    val label: String = if (state.connecting) "Connecting…" else "Connect"
    val describedAs: String = if (state.connecting) {
        "Connecting to the server"
    } else {
        "Connect to the server"
    }
    val plugIcon: @Composable (Color) -> Unit = { tint ->
        NeedlerStrokeIcon(
            pathData = "M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4",
            tint = tint,
            size = 20.dp,
        )
    }

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

        if (state.failure is ConnectFailure.UntrustedCertificate) {
            NeedlerSecondaryButton(
                text = label,
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                size = NeedlerButtonSize.Large,
                enabled = state.canConnect,
                leadingIcon = plugIcon,
                contentDescription = describedAs,
            )
        } else {
            NeedlerPrimaryButton(
                text = label,
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.canConnect,
                leadingIcon = plugIcon,
                contentDescription = describedAs,
            )
        }

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
                NeedlerSecretTextField(
                    label = "CF-ACCESS-CLIENT-SECRET",
                    value = proxy.cloudflareClientSecret,
                    onValueChange = {
                        callbacks.onFieldChange(ProxyField.CloudflareClientSecret, it)
                    },
                    secretName = "Client secret",
                    helperText = "Kept on this device only, with your other credentials.",
                    enabled = enabled,
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
                NeedlerSecretTextField(
                    label = "PROXY PASSWORD",
                    value = proxy.basicPassword,
                    onValueChange = { callbacks.onFieldChange(ProxyField.BasicPassword, it) },
                    secretName = "Proxy password",
                    enabled = enabled,
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
                    NeedlerSecretTextField(
                        label = "HEADER " + (index + 1) + " VALUE",
                        value = draft.value,
                        onValueChange = {
                            callbacks.onCustomHeaderChange(index, draft.name, it)
                        },
                        // Numbered, because up to ProxyCredentials.MAX_HEADERS of these can be on
                        // screen at once and four toggles all announcing "Header value" would
                        // leave a screen-reader user unable to tell which one they had hold of.
                        secretName = "Header " + (index + 1) + " value",
                        enabled = enabled,
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
            // Destructive, not accent, for the reason ConnectFailureNotice is: this is the other
            // "you cannot proceed" on this screen, and it was drawn in the same blue as the
            // section's own disclosure link directly above it.
            Text(
                text = proxy.problem,
                style = typography.caption,
                color = colors.destructive,
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
 *
 * ## One colour for "you cannot proceed"
 *
 * Every blocking failure wears
 * [app.needler.core.design.theme.NeedlerColors.destructive]; the only thing
 * that does not is [ConnectFailure.Cancelled], which is not a failure - the
 * user stopped the attempt, nothing is wrong and nothing was changed - and so
 * gets a hairline and an ordinary title.
 *
 * It used to be the two certificate cases in red and the other seven in accent
 * blue. That spent the palette's one reserved signal on the state it must never
 * mean. Accent is "on the server" everywhere else in the app, it is what the
 * screen's own primary button is painted with, and it measures **1.01:1**
 * against [app.needler.core.design.theme.NeedlerColors.positive] - so to anyone
 * reading the app's colour code by contrast rather than by hue, a blocking
 * failure was drawn in the same ink as a success. REQUIREMENTS.md's palette
 * table has no error colour and `destructive` was added to it later for
 * precisely this family of "this will cost you something" states; reusing it
 * costs nothing and leaves blue meaning one thing.
 *
 * The rejected alternative was a new `error` token in `:core:design`. It would
 * be the same pale red at a different name, in a module this change does not
 * own, to distinguish "an action that destroys something" from "a state you
 * cannot get past" - a distinction no user makes and no screen here renders
 * side by side.
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

    val blocking: Boolean = failure != ConnectFailure.Cancelled
    // componentBorder, not hairline: this is a panel's own edge rather than a rule between two
    // rows, and `hairline` composites to 1.20:1 on the canvas - see NeedlerColors.hairline, which
    // records the split and the measurement. A notice with no visible edge beside eight that have
    // one would read as a rendering fault.
    val edge = if (blocking) colors.destructive else colors.componentBorder
    val titleColour = if (blocking) colors.destructive else colors.textPrimary

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
        Text(text = failure.title, style = typography.rowTitle, color = titleColour)
        Text(text = failure.detail, style = typography.body, color = colors.textSecondary)

        when (failure) {
            is ConnectFailure.UntrustedCertificate -> {
                CertificateDetail(failure.certificate)
                Text(
                    text = TRUST_COMMITMENT,
                    style = typography.caption,
                    color = colors.textSecondary,
                )
                NeedlerPrimaryButton(
                    text = "Trust this certificate",
                    onClick = { onTrustCertificate(failure.certificate) },
                    modifier = Modifier.fillMaxWidth(),
                    size = NeedlerButtonSize.Medium,
                    contentDescription = "Trust this certificate for this server only",
                )
            }

            is ConnectFailure.CertificateChanged -> {
                CertificateDetail(failure.presented)
                // The two fingerprints on this panel are there to be read against each other, so
                // the one being compared *to* is laid out by the same rule as the one above it.
                // A row of the old that does not line up with a row of the new is the whole of
                // what this notice is asking the user to look for.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Previously trusted",
                        style = typography.sectionHeader,
                        color = colors.textSecondary,
                    )
                    fingerprintRows(failure.expectedFingerprint).forEach { row ->
                        Text(
                            text = row,
                            style = typography.meta.copy(fontFamily = FontFamily.Monospace),
                            color = colors.textMuted,
                        )
                    }
                }
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
 * proportional font makes that harder than it needs to be. It is also the only
 * thing on this screen laid out by [fingerprintRows] rather than by the text
 * engine, for the reason that function records.
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
        fingerprintRows(certificate.sha256Fingerprint).forEach { row ->
            Text(
                text = row,
                style = typography.meta.copy(fontFamily = FontFamily.Monospace),
                color = colors.textPrimary,
            )
        }
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

/**
 * Splits a colon-separated fingerprint into fixed rows of whole bytes.
 *
 * ## The defect
 *
 * A SHA-256 fingerprint is one 95-character token with no space in it, so the text engine had
 * nothing to break on and fell back to breaking per character. The screenshots show what that
 * produces: `...A0:C5` / `:5A:...:D1:5` / `D:6C:...` on the phone and the byte `15` split the same
 * way on the tablet. The single job of that panel is a character-by-character comparison against a
 * fingerprint printed by the server, and a byte torn across a line break is the one rendering that
 * makes the comparison harder than reading the raw string would have been. It was not a tuning
 * problem - any width tears some byte, so the wrap had to stop being the text engine's decision.
 *
 * ## What it does instead
 *
 * Eight bytes a row, which is four rows for a SHA-256 and the grouping `openssl` and every browser
 * certificate viewer print, so the rows line up against the thing being compared. The row the eye
 * is on is then found by position rather than by counting, which is the other half of why a fixed
 * grid beats a reflowing line.
 *
 * A zero-width space after each separator leaves the only break opportunity *inside* a row at a
 * byte boundary. It is invisible and costs nothing at the sizes the pack draws, where a row is
 * about 180dp inside a 310dp panel; it earns its place at the 200% text scale REQUIREMENTS.md
 * requires, where a row no longer fits and the engine is choosing again. Without it the defect
 * returns for exactly the users who can least afford it.
 *
 * Anything without separators - a fingerprint in some other encoding - comes back as the single row
 * it already was, rather than being chopped every eight characters into groups that mean nothing.
 *
 * @param bytesPerRow how many whole bytes a row holds. Eight unless a test says otherwise.
 */
internal fun fingerprintRows(fingerprint: String, bytesPerRow: Int = 8): List<String> {
    val bytes: List<String> = fingerprint.split(':')
    if (bytes.size <= 1) return listOf(fingerprint)
    return bytes.chunked(bytesPerRow).map { row -> row.joinToString(separator = ":\u200B") }
}

/** The pack's headline, on both screens. */
private const val HEADLINE = "Point me at your Dropped Needle."

/**
 * What pressing `Trust this certificate` signs the user up to.
 *
 * The panel said "Check the fingerprint below matches your server, then trust it" and stopped
 * there, which leaves the three questions anyone hesitating over a security decision actually has:
 * how long for, how far it reaches, and whether it can be taken back. None of the three were
 * answerable from the screen, and the first two have alarming wrong answers - a user who assumes
 * "until I close the app" or "for everything" is being asked to agree to something worse than what
 * is on offer.
 *
 * All three are now named, in the order they are asked. Revocation is named last and by its exact
 * path because it is the one that was true but invisible: `SettingsScreen`'s `Trusted certificate`
 * row with its `Forget this certificate` action already exists, and a reversible decision presented
 * as irreversible is paid for in users who decline it and cannot reach their own server.
 *
 * The words are the screen's own, not the implementation's: "this one server" rather than "this
 * host", because `CertificateInfo` carries a subject, not a host, and the pin is in fact scoped to
 * the host of the saved address - see `CertificatePinStore`.
 */
private const val TRUST_COMMITMENT =
    "Trusting it pins this exact certificate for this one server and nothing else, and it stays " +
        "pinned until you forget it under Settings, Server, Trusted certificate."

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

