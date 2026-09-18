package app.needler.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    /** The name this device's companion session is registered under. */
    private val deviceName: String = DeviceSessionName.current()

    fun onServerChange(value: String) = _state.update { it.copy(server = value, failure = null) }

    fun onUsernameChange(value: String) =
        _state.update { it.copy(username = value, failure = null) }

    fun onPasswordChange(value: String) =
        _state.update { it.copy(password = value, failure = null) }

    fun connect() {
        val current = _state.value
        if (!current.canConnect) return
        _state.update { it.copy(connecting = true, failure = null) }
        viewModelScope.launch { runConnect(current) }
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

        val probe = sessions.probeServer(typedUrl)
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

        _state.update {
            it.copy(
                connecting = false,
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
        _state.update {
            it.copy(connecting = false, failure = ConnectFailure.from(error, typedUrl))
        }
    }
}
