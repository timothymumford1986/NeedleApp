package app.needler.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.network.ProxyCredentialStore
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
            is Outcome.Failure -> return fail(probe.error, typedUrl)
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

    private fun fail(error: NeedlerError, typedUrl: String) {
        attempt = null
        _state.update {
            val failure: ConnectFailure = ConnectFailure.from(error, typedUrl)
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
