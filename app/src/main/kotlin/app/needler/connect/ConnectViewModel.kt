package app.needler.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReonboardingReason
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.network.ProxyCredentialStore
import app.needler.core.network.ServerUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives the Connect screen off [SessionRepository].
 *
 * The screen never touches the network. Everything it needs - normalising and
 * probing the URL, logging in, minting the companion session and the
 * app-password, negotiating capabilities, pinning a certificate - is one call
 * on the domain interface, and every failure arrives as a modelled
 * [NeedlerError] rather than an exception. That is what lets
 * [ConnectFailure.from] turn each one into a different, actionable message
 * instead of a single "could not connect".
 *
 * ## The order of operations, and why
 *
 * 1. **Probe the URL.** REQUIREMENTS.md: "A wrong URL must fail on the Connect
 *    screen, never later." The probe is public, so it runs before any
 *    credential is sent, and it is where a typo and an untrusted certificate
 *    are caught.
 *
 *    The screen passes what the user typed, verbatim. A bare host with no
 *    scheme is not an instruction and is not defaulted here: REQUIREMENTS.md
 *    "Accepted URL forms" makes it a *guess*, and the repository walks
 *    `ServerUrl.ladder` until a rung answers. Typing
 *    `music.yourhome.net` therefore works. What this class owns is the other
 *    half of that: when no rung answers, the failure names every address that
 *    was dialled, because the user typed none of them and cannot otherwise
 *    learn that their non-standard port was never tried.
 * 2. **Connect.** Log in, mint a device session named for this device, create
 *    the app-password, store both. Wrong credentials surface here.
 * 3. **Negotiate capabilities.** This is where the `subsonic_enabled` gate
 *    lands. It runs *after* sign-in on purpose:
 *    `GET /api/v1/connect-apps/settings` sits behind the bearer middleware, so
 *    it cannot be read before credentials exist. A user whose server has the
 *    protocol switched off therefore signs in successfully and is then told,
 *    specifically, which setting an administrator has to turn on.
 *
 * The probe's own `subsonicEnabled` flag is deliberately not used as the gate.
 * It cannot be authoritative before sign-in, and treating it as such would
 * block onboarding on a server that is in fact configured correctly.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val proxyCredentials: ProxyCredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow(
        // Whatever proxy headers are already saved come back into the form, so re-onboarding
        // against a server that needs them does not quietly drop the thing making it reachable.
        ConnectUiState(proxy = ProxyFormState.from(proxyCredentials.proxyCredentials())),
    )
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    /** The name this device's companion session is registered under. */
    private val deviceName: String = DeviceSessionName.current()

    /** The attempt in flight, so the user can stop it. */
    private var attempt: Job? = null

    init {
        viewModelScope.launch { arriveWith(sessions.currentSession()) }
    }

    /**
     * What this screen is for, decided from the session the data layer rebuilt from the keystore.
     *
     * ## Why a sign-in form has to ask whether the user is already signed in
     *
     * Because it is the navigation graph's **start destination on every launch**.
     * `NeedlerNavHost` takes a `startConnected` flag to begin at Home instead, and nothing has ever
     * passed it - `NeedlerApp` calls the host with the flag left at its `false` default, and its
     * KDoc still says "until `SessionRepository` has an implementation behind it, a cold start
     * begins at Connect". That implementation landed. So a cold start lands here holding a perfectly
     * good session, finds a view model that only ever looked for one failure reason, and renders
     * three empty fields: the user reads that as having been signed out, signs in again, and the
     * device session is rotated for nothing. Observed on a device on 2026-10-02 after an app update
     * replaced the process, with the credentials intact on disk and the Room mirror and 440 MB of
     * downloads untouched - which is the signature of a *navigation* fault, not a credential one.
     *
     * Resuming from here rather than from the host is the deliberate choice, and the alternative was
     * rejected on two counts. Passing `startConnected` means resolving the session before the
     * `NavHost` composes, because `startDestination` is read once; that means either building the
     * whole Hilt singleton graph - Room, OkHttp, the credential file - on the main thread before the
     * first frame, which REQUIREMENTS.md "Performance budgets" will not pay for, or gating the first
     * frame on a coroutine and making the splash the reason the app feels slow, which the same
     * section forbids in those words. This path costs nothing: the view model already injects
     * [SessionRepository] and already asks it one question on arrival, and because `NeedlerApp`
     * draws the 2.1 s splash *over* the navigation rather than gating it, the hand-off to Home
     * happens underneath the animation and the form is never on screen.
     *
     * ## What each state gets
     *
     * REQUIREMENTS.md "Expiry, and why playback survives it" requires re-onboarding "in exactly one
     * case: both credentials are dead", so anything that can still do something is handed straight
     * to the library - including [SessionState.PlayerOnly], whose whole point is that an expired
     * bearer "degrades the app to a pure music player rather than bricking it" and which a sign-in
     * form is precisely the bricking of, and [SessionState.RepairingAppPassword], which "nothing in
     * the UI announces - no dialog, no prompt, no sign-in screen".
     *
     * The rest stay, and the server address is pre-filled for every one of them that has one. That
     * is not cosmetic: an empty form is how the app said "you were never signed in" to a user who
     * was, which is the confusion `SecureCredentialStore`'s unencrypted server mirror exists to make
     * impossible. Only a deliberate sign-out gets the blank form, because only that user asked for
     * it.
     */
    private fun arriveWith(session: SessionState) {
        when (session) {
            is SessionState.Authenticated,
            is SessionState.PlayerOnly,
            is SessionState.RepairingAppPassword,
            -> _state.update { it.copy(connected = true, failure = null) }

            // Unreachable from a cold start - the data layer reconstructs this state only from a
            // live negotiation, never from the keystore - and handled rather than swallowed because
            // the one thing this state must never do is look like a wrong password.
            is SessionState.SubsonicDisabled -> _state.update { current ->
                current.copy(
                    server = current.server.ifBlank { session.server.baseUrl },
                    failure = ConnectFailure.SubsonicDisabled,
                )
            }

            is SessionState.ReonboardingRequired -> explain(session)

            // A genuine first run. The empty form is the right answer and the only one.
            SessionState.NotConfigured -> Unit
        }
    }

    /**
     * The four ways a saved session ends, and which of them owes the user an explanation here.
     *
     * Only [ReonboardingReason.CREDENTIALS_UNREADABLE] gets a notice, because it is the only one
     * that is nobody's fault: the secrets are still on the disk under a key this launch could not
     * use, and `SecureCredentialStore` has deleted nothing. `BOTH_CREDENTIALS_DEAD` is an ordinary
     * expiry that the non-blocking prompt elsewhere in the app owns, and claiming the keystore
     * failed would be a false explanation; `SERVER_IDENTITY_CHANGED` is a different story with its
     * own message; `SIGNED_OUT` is deliberate.
     *
     * Three of the four still pre-fill the address, which is the part that was missing. Re-typing a
     * LAN address with a non-standard port is the step of re-onboarding users get wrong, and the
     * address is not a secret - it never had to be lost with the key, and `saveServerUrl` keeps it
     * outside the encrypted file for exactly this moment.
     */
    private fun explain(session: SessionState.ReonboardingRequired) {
        val server: String? = session.server?.baseUrl?.takeIf { it.isNotBlank() }
        val failure: ConnectFailure? = when (session.reason) {
            ReonboardingReason.CREDENTIALS_UNREADABLE -> ConnectFailure.SavedSessionLocked(server)
            ReonboardingReason.BOTH_CREDENTIALS_DEAD,
            ReonboardingReason.SERVER_IDENTITY_CHANGED,
            -> null
            // The one state that must look like a fresh install, because the user asked for it: no
            // notice, and no address carried over from the server they just left.
            ReonboardingReason.SIGNED_OUT -> return
        }
        _state.update { current ->
            current.copy(
                server = current.server.ifBlank { server.orEmpty() },
                failure = failure,
            )
        }
    }

    /**
     * How long an attempt runs before the screen admits it is still trying.
     *
     * Five seconds is well inside the 10-second connect timeout, so a genuinely slow server shows
     * the notice while it is still working rather than only after it has failed.
     */
    internal var slowNoticeAfterMillis: Long = SLOW_NOTICE_MILLIS

    fun onServerChange(value: String) = _state.update { it.copy(server = value, failure = null) }

    fun onUsernameChange(value: String) =
        _state.update { it.copy(username = value, failure = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, failure = null) }

    // ---- the optional proxy form --------------------------------------------

    fun onProxyExpandedChange(expanded: Boolean) =
        _state.update { it.copy(proxy = it.proxy.copy(expanded = expanded, problem = null)) }

    fun onProxyPresetChange(preset: ProxyPreset) = _state.update {
        it.copy(proxy = it.proxy.copy(preset = preset, problem = null), failure = null)
    }

    fun onProxyFieldChange(field: ProxyField, value: String) = _state.update { state ->
        val proxy = when (field) {
            ProxyField.CloudflareClientId -> state.proxy.copy(cloudflareClientId = value)
            ProxyField.CloudflareClientSecret -> state.proxy.copy(cloudflareClientSecret = value)
            ProxyField.BasicUsername -> state.proxy.copy(basicUsername = value)
            ProxyField.BasicPassword -> state.proxy.copy(basicPassword = value)
        }
        state.copy(proxy = proxy.copy(problem = null), failure = null)
    }

    fun onCustomHeaderChange(index: Int, name: String, value: String) = _state.update {
        it.copy(proxy = it.proxy.withCustomHeader(index, name, value), failure = null)
    }

    fun onAddCustomHeader() = _state.update { it.copy(proxy = it.proxy.withExtraCustomHeader()) }

    // ---- the attempt --------------------------------------------------------

    fun connect() {
        val current = _state.value
        if (!current.canConnect) return

        // Refused before a single packet leaves: a bad header name produces a proxy 401 that reads
        // exactly like a wrong password, and a control character in a value is a request Needler
        // must not make at all.
        val problem: String? = current.proxy.validationProblem()
        if (problem != null) {
            _state.update { it.copy(proxy = it.proxy.copy(problem = problem, expanded = true)) }
            return
        }

        // Saved before the first request rather than after a successful sign-in: the public probe
        // goes through the same proxy as everything else, so it has to carry the headers too.
        if (!proxyCredentials.saveProxyCredentials(current.proxy.credentials())) {
            _state.update {
                it.copy(
                    proxy = it.proxy.copy(
                        problem = "Those headers could not be saved to this device.",
                        expanded = true,
                    ),
                )
            }
            return
        }

        _state.update { it.copy(connecting = true, attemptIsSlow = false, failure = null) }
        attempt = viewModelScope.launch {
            val notice = launch {
                delay(slowNoticeAfterMillis)
                _state.update { it.copy(attemptIsSlow = true) }
            }
            try {
                runConnect(current)
            } finally {
                notice.cancel()
            }
        }
    }

    /**
     * Stops the attempt in flight.
     *
     * Cancelling the coroutine cancels the OkHttp call underneath it - `:core:network` runs every
     * call through `suspendCancellableCoroutine` and calls `call.cancel()` on cancellation - so this
     * releases the socket rather than leaving it to time out in the background.
     */
    fun cancelConnect() {
        // Guarded on the state, not only on the job: an attempt that has already finished leaves a
        // completed Job behind, and cancelling that would replace a successful connect with a
        // "stopped" notice.
        if (!_state.value.connecting) return
        val running: Job = attempt ?: return
        attempt = null
        running.cancel()
        _state.update {
            it.copy(connecting = false, attemptIsSlow = false, failure = ConnectFailure.Cancelled)
        }
    }

    /**
     * Pins one leaf certificate for this one host and immediately retries.
     *
     * Retrying is the point: the user has just looked at a fingerprint and said
     * yes, and making them press Connect again would be asking the same
     * question twice.
     */
    fun trustCertificate(certificate: CertificateInfo) {
        val current = _state.value
        if (current.connecting) return
        _state.update { it.copy(connecting = true, failure = null) }
        viewModelScope.launch {
            when (val trusted = sessions.trustCertificate(certificate)) {
                is Outcome.Failure -> fail(trusted.error, current.server)
                is Outcome.Success -> runConnect(current)
            }
        }
    }

    private suspend fun runConnect(form: ConnectUiState) {
        val typedUrl = form.server.trim()

        currentCoroutineContext().ensureActive()
        val probe = sessions.probeServer(typedUrl)
        currentCoroutineContext().ensureActive()
        val serverUrl = when (probe) {
            // The only step that gets the ladder: a probe failure is the one failure where the
            // user's address was never confirmed, and so the one where the addresses tried are
            // news. Derived from the same `ladder()` the repository walked rather than reported
            // back by it, because `NeedlerError.Offline` - the commonest of these, and the one
            // whose message is least use - carries no room for a list, and `:core:domain` is not
            // this change's to widen.
            is Outcome.Failure -> return fail(
                error = probe.error,
                typedUrl = typedUrl,
                attempted = ServerUrl.ladderFor(typedUrl).map { it.baseUrl },
            )
            is Outcome.Success -> probe.value.identity.baseUrl
        }

        val connected = sessions.connect(
            serverUrl = serverUrl,
            username = form.username.trim(),
            password = form.password,
            deviceName = deviceName,
        )
        currentCoroutineContext().ensureActive()
        if (connected is Outcome.Failure) return fail(connected.error, typedUrl)

        when (val capabilities = sessions.negotiateCapabilities()) {
            is Outcome.Failure -> return fail(capabilities.error, typedUrl)
            is Outcome.Success -> {
                // The repository is expected to fail negotiation outright when
                // the protocol is off, but a capability set that merely reports
                // it is the same blocking condition and gets the same message.
                if (!capabilities.value.subsonicEnabled) {
                    return fail(NeedlerError.SubsonicProtocolDisabled, typedUrl)
                }
            }
        }

        currentCoroutineContext().ensureActive()
        attempt = null
        _state.update {
            it.copy(
                connecting = false,
                attemptIsSlow = false,
                failure = null,
                connected = true,
                // The account password is never needed again: the two secrets
                // that outlive onboarding are the companion bearer and the
                // app-password, both of which the repository has already put in
                // Keystore-backed storage. Holding it in UI state after that is
                // a secret sitting in a saved-state bundle for no reason.
                password = "",
            )
        }
    }

    private fun fail(
        error: NeedlerError,
        typedUrl: String,
        attempted: List<String> = emptyList(),
    ) {
        attempt = null
        _state.update {
            val failure: ConnectFailure = ConnectFailure.from(error, typedUrl, attempted)
            it.copy(
                connecting = false,
                attemptIsSlow = false,
                failure = failure,
                // A proxy in the way with no credential set is the one failure whose fix is a field
                // the user cannot see. Open the disclosure for them rather than describing where it
                // is.
                proxy = if (failure is ConnectFailure.ProxyIntercepted && !it.proxy.isConfigured) {
                    it.proxy.copy(expanded = true)
                } else {
                    it.proxy
                },
            )
        }
    }

    private companion object {
        const val SLOW_NOTICE_MILLIS: Long = 5_000
    }
}
