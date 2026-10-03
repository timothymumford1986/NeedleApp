package app.needler.core.data.repository

import app.needler.core.data.mapper.CatalogueMappers
import app.needler.core.data.mapper.ErrorMapper
import app.needler.core.data.mapper.networkCall
import app.needler.core.data.platform.NetworkMonitor
import app.needler.core.data.security.SecureCredentialStore
import app.needler.core.domain.model.CertificateInfo
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.OpenSubsonicExtension
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PinnedCertificate
import app.needler.core.domain.model.PlayerOnlyReason
import app.needler.core.domain.model.ReonboardingReason
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.ServerIdentity
import app.needler.core.domain.model.ServerProbe
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.User
import app.needler.core.domain.repository.SessionRepository
import app.needler.core.network.NetworkError
import app.needler.core.network.ServerUrl
import app.needler.core.network.ServerUrlResult
import app.needler.core.network.capability.CapabilityProbe
import app.needler.core.network.capability.CapabilityProbeResult
import app.needler.core.network.capability.ServerCapabilitiesDto
import app.needler.core.network.tls.CertificateDetails
import app.needler.core.network.tls.MutableCertificatePinStore
import app.needler.core.network.tls.TlsCertificateProbe
import app.needler.core.network.v1.V1Api
import app.needler.core.network.v1.dto.AppPasswordCreateResponseDto
import app.needler.core.network.v1.dto.AppPasswordDto
import app.needler.core.network.v1.dto.AppPasswordListDto
import app.needler.core.network.v1.dto.AuthProvidersDto
import app.needler.core.network.v1.dto.AuthResponseDto
import app.needler.core.network.v1.dto.DeviceSessionResponseDto
import app.needler.core.network.v1.dto.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

/**
 * Onboarding, credentials, capability negotiation and connectivity.
 *
 * No secret appears in this class's public surface: the companion bearer and the app-password live
 * behind [SecureCredentialStore] in Keystore-backed storage, and nothing here returns or accepts a
 * token.
 *
 * ## The credential asymmetry, which is the whole design
 *
 * The bearer lives 30 days and does not slide; the app-password lives until it is revoked. So an
 * expired bearer degrades the app to a **pure music player** rather than bricking it - library
 * browsing, playlists, favourites and playback all run on the app-password and are untouched.
 *
 * The mirror-image failure is *not* symmetrical, and getting that wrong is expensive. A rejected
 * app-password with a live bearer is repaired **silently**: `POST /api/v1/connect-apps/app-passwords`
 * needs only the bearer, which the app is still holding, so it mints a replacement and tells the user
 * nothing. Re-onboarding would throw away a working session and a multi-gigabyte cache over one
 * revoked secret - usually one the user revoked from the web UI without realising which app it
 * belonged to. Re-onboarding happens in exactly one case: both credentials are dead.
 */
public class DefaultSessionRepository(
    private val credentials: SecureCredentialStore,
    private val v1: V1Api,
    private val capabilityProbe: CapabilityProbe,
    private val networkMonitor: NetworkMonitor,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * Reads the leaf certificate of a rung that failed TLS, so the Connect screen can show a
     * fingerprint instead of "no connection".
     *
     * Defaulted, so the Hilt module needs no change for it: this object holds no state, keeps no
     * socket between calls and is only ever used on the Connect path, so one per repository costs
     * nothing. A parameter at all only so a test can answer for it without a server.
     */
    private val certificates: TlsCertificateProbe = TlsCertificateProbe(),
    /**
     * The live pin set the TLS trust manager consults on every handshake.
     *
     * **Not defaulted**, unlike [certificates], and that is the point: a repository holding its own
     * store would pin into an object the HTTP client has never seen, which is how "Trust this
     * certificate" came to do nothing at all. There is one instance in the graph, shared with
     * `NeedlerHttpClient`, and a caller that cannot supply it has nothing to pin into.
     */
    private val pins: MutableCertificatePinStore,
) : SessionRepository {

    private val sessionState: MutableStateFlow<SessionState> = MutableStateFlow(initialState())
    private val capabilitiesState: MutableStateFlow<ServerCapabilities?> = MutableStateFlow(null)

    /**
     * Guards app-password repair.
     *
     * Implementations must be single-flight: many Subsonic calls fail at once when a secret is
     * revoked, and one repair per failed request would burn through the server's cap of 25 active
     * app-passwords per user in a single burst.
     */
    private val repairLock: Mutex = Mutex()

    override fun observeSession(): Flow<SessionState> = sessionState.asStateFlow()

    override suspend fun currentSession(): SessionState = sessionState.value

    override fun observeCapabilities(): Flow<ServerCapabilities?> = capabilitiesState.asStateFlow()

    override suspend fun currentCapabilities(): ServerCapabilities? = capabilitiesState.value

    override fun observeConnectivity(): Flow<ConnectivityState> = networkMonitor.observe()

    override suspend fun currentConnectivity(): ConnectivityState = networkMonitor.current()

    // ------------------------------------------------------------------- connect

    /**
     * Normalises a typed URL and probes it.
     *
     * The probe is `GET /api/v1/auth/providers`, which is **public**. It cannot be `/api/v1/version`
     * or `/api/v1/status`, however much they look like health checks: both sit behind the bearer
     * middleware and answer `401` unauthenticated, so a correct address and a typo are
     * indistinguishable. A wrong URL must fail here, on the Connect screen, never later.
     *
     * When the user typed no scheme there is more than one address to try, so the probe walks
     * [ServerUrl.ladder] and the first rung that answers wins - that rung's `baseUrl` is what
     * comes back, and what `connect` then signs in against. A certificate the user has not
     * trusted ends the walk rather than falling through to cleartext.
     */
    override suspend fun probeServer(rawUrl: String): Outcome<ServerProbe> {
        val parsed: ServerUrlResult = ServerUrl.parse(rawUrl)
        val url: ServerUrl = when (parsed) {
            is ServerUrlResult.Invalid -> return Outcome.Failure(
                NeedlerError.NotADroppedNeedleServer(rawUrl),
            )
            is ServerUrlResult.Valid -> parsed.url
        }
        var bestFailure: NeedlerError? = null
        for (candidate in url.ladder()) {
            currentCoroutineContext().ensureActive()
            saveServer(candidate)
            when (val call = probeRung(candidate)) {
                is Outcome.Success -> return Outcome.Success(
                    ServerProbe(
                        identity = ServerIdentity(baseUrl = candidate.baseUrl),
                        apiVersion = null,
                        // Whether the Subsonic shim is switched on is behind the bearer, so the
                        // Connect screen cannot know it yet. It is discovered by
                        // `negotiateCapabilities` right after sign-in, and that is where the
                        // "ask an admin" message comes from.
                        subsonicEnabled = false,
                    ),
                )
                is Outcome.Failure -> {
                    val failure: NeedlerError = probeFailure(call.error, candidate)
                    if (endsTheLadder(failure)) return Outcome.Failure(failure)
                    val best: NeedlerError? = bestFailure
                    if (best == null || tellsTheUserMore(failure) > tellsTheUserMore(best)) {
                        bestFailure = failure
                    }
                }
            }
        }

        // Nothing answered. Leave the address the user typed saved rather than whichever rung was
        // tried last, so Settings and a retry both show what they entered.
        saveServer(url)
        return Outcome.Failure(bestFailure ?: NeedlerError.NotADroppedNeedleServer(url.baseUrl))
    }

    /**
     * How much a rung's failure is worth saying, so a finished walk reports its most informative
     * rung rather than whichever one happened to be last.
     *
     * Reporting the last rung loses the only failure on this screen whose fix is a field the user
     * cannot see. A bare domain name puts `https` first, which is where a forward-auth proxy -
     * Cloudflare Access, Authelia - intercepts; the two cleartext rungs after it are then refused
     * by a port with nothing on it, and the proxy notice the Connect screen has a whole state and
     * a whole form for is replaced by "no connection". That is the 45-second dead screen the proxy
     * work was done to remove, re-introduced by the ladder.
     *
     * An interception therefore outranks everything, and anything that answered at all outranks
     * [NeedlerError.Offline], which only ever says that nothing was listening. Ties go to the
     * earlier rung, because [ServerUrl.ladder] is already ordered by what the address most likely
     * is.
     *
     * The walk is **not** cut short for an interception, unlike an untrusted certificate. A proxy
     * on 443 says nothing about DroppedNeedle's own port on the same host, so a LAN rung may still
     * answer and deserves its turn; only the message is affected.
     */
    private fun tellsTheUserMore(error: NeedlerError): Int = when {
        error is NeedlerError.Unexpected && error.cause is NetworkError.AuthenticatingProxy -> 2
        error is NeedlerError.Offline -> 0
        else -> 1
    }

    /**
     * One rung of the walk: `GET /api/v1/auth/providers` against whatever [credentials] now holds.
     *
     * Not [networkCall], which is otherwise this module's only way to make a request, because
     * `ErrorMapper` deliberately flattens [NetworkError.TlsNotTrusted] into
     * [NeedlerError.Offline] - the transport only ever saw a handshake fail, it has no
     * [CertificateInfo] to offer, and for the rest of the app a distrusted server really is just
     * unreachable. The Connect screen is the one caller that needs the certificate itself, and
     * REQUIREMENTS.md "Self-signed certificates" puts the reading of it here: "show the
     * fingerprint, subject and expiry, and let the user pin that exact certificate for this one
     * host".
     *
     * Flattening it here would be a silent downgrade, which is the one thing the ladder must never
     * do. A bare domain name puts `https` first; a self-hosted server with its own certificate
     * fails that rung, and an [NeedlerError.Offline] does not end the walk, so the probe would go
     * on to `http` on 8688 and then 80, succeed on cleartext if anything were listening, and
     * complete onboarding over plain HTTP against a server the user had set up with TLS - never
     * having been shown the fingerprint they were entitled to decide on.
     */
    private suspend fun probeRung(candidate: ServerUrl): Outcome<AuthProvidersDto> = try {
        Outcome.Success(v1.authProviders())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        Outcome.Failure(
            if (failure is NetworkError.TlsNotTrusted) {
                certificateDecision(candidate)
            } else {
                ErrorMapper.toNeedlerError(failure)
            },
        )
    }

    /**
     * Reads the certificate the failing rung presented and decides which of the two certificate
     * failures the user is looking at.
     *
     * [TlsCertificateProbe] completes a second handshake without validating the chain, purely to
     * read the leaf. Nothing is trusted by doing so - the socket is closed without a byte being
     * written to it - and the certificate becomes an exception only once the **user** pins it.
     *
     * A pin that exists and does not match is [NeedlerError.CertificateChanged], which must fail
     * loudly and be re-confirmed; anything else is [NeedlerError.CertificateUntrusted], the
     * ordinary self-hosted case. Either way the walk stops, because the user has a decision to
     * make and the next rung would make it for them.
     *
     * When the second handshake cannot be completed either, there is no fingerprint to show, so
     * this falls back to the flattened answer the rest of the app would have given - an address
     * that cannot be reached. The alternative, an empty certificate prompt, asks the user to
     * compare a fingerprint that is not there.
     */
    private suspend fun certificateDecision(candidate: ServerUrl): NeedlerError {
        val details: CertificateDetails? = try {
            certificates.probe(candidate)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unreadable: Exception) {
            // Swallowed rather than logged: this class holds no log, and the handshake that
            // produced `TlsNotTrusted` has already been logged by the engine. Anything thrown
            // here - the host stopped answering between the two handshakes, no TLS provider for
            // the algorithm it offered - means the same thing to the caller.
            null
        }
        if (details == null) return NeedlerError.Offline(OfflineCause.CONNECTION_FAILED)
        val presented = CertificateInfo(
            sha256Fingerprint = details.sha256Hex,
            subject = details.subject,
            issuer = details.issuer,
            notAfter = Instant.fromEpochMilliseconds(details.notAfter.time),
        )
        val pinned: String? = credentials.pinnedCertificateFor(details.host)
        return if (pinned != null && pinned != presented.sha256Fingerprint) {
            NeedlerError.CertificateChanged(expectedFingerprint = pinned, presented = presented)
        } else {
            NeedlerError.CertificateUntrusted(presented)
        }
    }

    /**
     * True for a probe failure that must not be answered by trying the next rung.
     *
     * An untrusted or changed certificate means TLS *worked* and the user has a decision to make.
     * Falling through to cleartext would hide that prompt and quietly downgrade the connection,
     * which is the one thing the ladder must never do.
     */
    private fun endsTheLadder(error: NeedlerError): Boolean = when (error) {
        is NeedlerError.CertificateUntrusted,
        is NeedlerError.CertificateChanged,
        NeedlerError.Cancelled,
        -> true
        else -> false
    }

    /**
     * The user has looked at the fingerprint and tapped Trust.
     *
     * Two stores have to hear about it, and both halves were missing. [SecureCredentialStore] keeps
     * the pin across launches; [MutableCertificatePinStore] is what `PinnedHostTrustManager`
     * actually consults during a handshake. Writing only the first is a pin that never takes
     * effect; writing only the second is a pin that is gone on the next launch. The user's decision
     * has to outlive both the request and the process.
     *
     * ## The host, and why there has to be one
     *
     * REQUIREMENTS.md "Self-signed certificates": "Pin the leaf certificate for the one host, never
     * disable validation globally." [CertificateInfo] carries no host - it is what the UI renders -
     * so the host comes from the **saved server address**, which is the right answer rather than a
     * convenient one: [probeServer] saves each rung before dialling it and an untrusted certificate
     * ends the walk immediately, so the address saved at this moment is the exact rung that
     * presented this certificate. With no saved address there is no host, and a pin with no host is
     * the global exception the doc forbids - so this fails instead of guessing.
     *
     * ## Disk first, then memory
     *
     * A failed persist returns a failure and grants nothing. The alternative ordering - trust now,
     * persist if it works - produces the worst outcome available: a connection that works until the
     * process dies and then fails with the user certain they already confirmed it.
     *
     * The in-memory pin **replaces** the host's previous one rather than joining it. It is not a
     * silent replacement: nothing reaches this method except the user tapping Trust on a prompt
     * raised by [NeedlerError.CertificateUntrusted] or the loud
     * [NeedlerError.CertificateChanged], which is the re-confirmation the doc demands.
     */
    override suspend fun trustCertificate(certificate: CertificateInfo): Outcome<Unit> {
        val host: String = credentials.serverUrl()?.host
            ?: return Outcome.Failure(
                NeedlerError.Unexpected("no saved server to scope the certificate pin to"),
            )
        val persisted: Boolean = credentials.pinCertificate(
            host = host,
            sha256Fingerprint = certificate.sha256Fingerprint,
        )
        if (!persisted) {
            return Outcome.Failure(NeedlerError.Unexpected("could not persist the certificate pin"))
        }
        // Live, so the retry the Connect screen makes next uses it: the store is read on every
        // handshake and `NeedlerHttpClient` evicts pooled connections when it changes.
        pins.pin(host, certificate.sha256Fingerprint)
        return Outcome.Ok
    }

    /**
     * What the user trusted, read back out of the store that kept it.
     *
     * Both halves come from [SecureCredentialStore] rather than from [pins], and the asymmetry with
     * [trustCertificate] - which writes to both - is deliberate. The credential store is the record of
     * the user's *decision*; the in-memory store is a cache the handshake reads, seeded from it at
     * startup by `DataModule`. Reporting the cache would report a fact about this process rather than
     * a fact about the install, and the two differ for exactly as long as a bug lets them.
     *
     * `pinnedCertificateHost()` rather than the saved address's host, for the reason that method
     * documents: a pin is attributed to the host it was granted to and written down beside it, so that
     * changing the address afterwards cannot re-point it. See
     * [SecureCredentialStore.pinnedCertificateHost].
     */
    override suspend fun pinnedCertificate(): PinnedCertificate? {
        val host: String = credentials.pinnedCertificateHost() ?: return null
        val fingerprint: String = credentials.pinnedCertificateFor(host) ?: return null
        return PinnedCertificate(host = host, sha256Fingerprint = fingerprint)
    }

    /**
     * Drops the pin from disk first, then from the live set.
     *
     * Disk first, which is the mirror image of [trustCertificate]'s ordering and the same reasoning:
     * there the worst outcome available was a trust that evaporated on restart, so the persist had to
     * succeed before the grant; here it is a revocation that comes *back* on restart, so the persist
     * has to succeed before the handshake stops honouring it. A user who revokes a certificate and
     * finds it trusted again tomorrow has been told something false about their own device.
     *
     * The host is read before the clear, because afterwards there is nothing left to say which host to
     * drop from the live set.
     */
    override suspend fun forgetPinnedCertificate(): Outcome<Unit> {
        val host: String = credentials.pinnedCertificateHost() ?: return Outcome.Ok
        if (!credentials.clearPinnedCertificate()) {
            return Outcome.Failure(NeedlerError.Unexpected("could not forget the certificate pin"))
        }
        // Live, so the next request stops trusting it rather than the next launch: the store is read
        // on every handshake and `NeedlerHttpClient` evicts pooled connections when it changes.
        pins.clear(host)
        return Outcome.Ok
    }

    /**
     * Saves the server address, and keeps the live pin set in step with what that did to the pin.
     *
     * [SecureCredentialStore.saveServerUrl] drops a pinned certificate whose host is not the one
     * being saved - REQUIREMENTS.md "Secrets and migrations" drops the mirror and the cache on a
     * server change, and a pin left behind is worse than stale data because the new host would
     * inherit an exception the user never granted it. This mirrors that into the in-memory store,
     * by asking what the credential store decided rather than deciding again: two copies of the
     * same rule is how the two halves came to disagree in the first place.
     */
    private fun saveServer(url: ServerUrl): Boolean {
        val pinnedHost: String? = credentials.pinnedCertificateHost()
        val saved: Boolean = credentials.saveServerUrl(url)
        if (pinnedHost != null && credentials.pinnedCertificateFor(pinnedHost) == null) {
            pins.clear(pinnedHost)
        }
        return saved
    }

    /**
     * Completes onboarding with the user's **account password**, not an app-password.
     *
     * An app-password cannot authenticate `/api/v1` at all - the bearer middleware validates only
     * against the auth-tokens table, while app-passwords live in a separate store that only the
     * Subsonic and Jellyfin shims consult - so a user who supplied one would get a working music
     * player with no search, no pull and no queue.
     *
     * The login bearer is used only to mint the other two and is then discarded. The app-password
     * secret is returned exactly once and is never re-fetchable, so a failed write **aborts and
     * revokes** rather than leaving a credential neither side can use.
     */
    override suspend fun connect(
        serverUrl: String,
        username: String,
        password: String,
        deviceName: String,
    ): Outcome<SessionState> {
        val parsed: ServerUrlResult = ServerUrl.parse(serverUrl)
        val url: ServerUrl = when (parsed) {
            is ServerUrlResult.Invalid ->
                return Outcome.Failure(NeedlerError.NotADroppedNeedleServer(serverUrl))
            is ServerUrlResult.Valid -> parsed.url
        }
        saveServer(url)

        val login: Outcome<AuthResponseDto> = networkCall { v1.login(username, password) }
        val loginBearer: String = when (login) {
            is Outcome.Failure -> return Outcome.Failure(
                if (login.error is NeedlerError.SessionExpired) {
                    // A `401` on login is a wrong password, not a stale session.
                    NeedlerError.InvalidCredentials
                } else {
                    login.error
                },
            )
            is Outcome.Success -> login.value.token
        }

        return provision(url, loginBearer, deviceName)
    }

    /**
     * Runs capability negotiation and persists the result.
     *
     * Everything discovered here is a *feature gate*, not a version check: Needler targets no fixed
     * DroppedNeedle version and disables what a server cannot do rather than failing. Anything absent
     * from `getOpenSubsonicExtensions` is treated as unavailable however plausible it looks - three
     * extensions this server implements are unadvertised pending certification.
     */
    override suspend fun negotiateCapabilities(): Outcome<ServerCapabilities> {
        return when (val result: CapabilityProbeResult = capabilityProbe.probe()) {
            is CapabilityProbeResult.Ready -> {
                val capabilities: ServerCapabilities = toDomain(result.capabilities)
                capabilitiesState.value = capabilities
                promoteCapabilities(capabilities)
                Outcome.Success(capabilities)
            }

            is CapabilityProbeResult.SubsonicDisabled -> {
                val capabilities: ServerCapabilities = toDomain(result.partial)
                capabilitiesState.value = capabilities
                // The one hard dependency on an administrator. The setup screen names the setting
                // rather than showing a generic failure, which is why this is its own error case.
                sessionState.value = SessionState.SubsonicDisabled(identity())
                Outcome.Failure(NeedlerError.SubsonicProtocolDisabled)
            }

            is CapabilityProbeResult.SessionExpired -> {
                markSessionStale(PlayerOnlyReason.BEARER_REJECTED)
                Outcome.Failure(NeedlerError.SessionExpired)
            }

            is CapabilityProbeResult.AppPasswordRejected -> {
                markAppPasswordRejected(result.subsonicCode)
                Outcome.Failure(NeedlerError.AppPasswordRevoked(result.subsonicCode))
            }

            is CapabilityProbeResult.Unreachable ->
                Outcome.Failure(app.needler.core.data.mapper.ErrorMapper.toNeedlerError(result.error))

            is CapabilityProbeResult.Failed ->
                Outcome.Failure(app.needler.core.data.mapper.ErrorMapper.toNeedlerError(result.error))
        }
    }

    override suspend fun refreshUser(): Outcome<User> {
        val call: Outcome<UserDto> = networkCall { v1.me() }
        return when (call) {
            is Outcome.Failure -> {
                if (call.error is NeedlerError.SessionExpired) {
                    markSessionStale(PlayerOnlyReason.BEARER_REJECTED)
                }
                call
            }
            is Outcome.Success -> {
                val user: User = CatalogueMappers.user(call.value)
                sessionState.value = withUser(sessionState.value, user)
                Outcome.Success(user)
            }
        }
    }

    /**
     * Re-authenticates after the 30-day bearer has expired.
     *
     * A companion session cannot mint another device session - the server answers `403` - so silent
     * renewal is impossible without storing the account password, which Needler does not do. The user
     * is prompted instead, and warned in-app from day 25 so this is rarely a surprise.
     */
    override suspend fun reauthenticate(password: String): Outcome<SessionState> {
        val url: ServerUrl = credentials.serverUrl()
            ?: return Outcome.Failure(NeedlerError.CapabilityUnavailable("no server configured"))
        val username: String = (sessionState.value as? SessionState.PlayerOnly)?.user?.username
            ?: (sessionState.value as? SessionState.Authenticated)?.user?.username
            ?: return Outcome.Failure(NeedlerError.CapabilityUnavailable("no username to sign in with"))

        val login: Outcome<AuthResponseDto> = networkCall { v1.login(username, password) }
        val loginBearer: String = when (login) {
            is Outcome.Failure -> return Outcome.Failure(
                if (login.error is NeedlerError.SessionExpired) {
                    NeedlerError.InvalidCredentials
                } else {
                    login.error
                },
            )
            is Outcome.Success -> login.value.token
        }
        return provision(url, loginBearer, deviceName = DEFAULT_DEVICE_NAME)
    }

    override suspend fun markSessionStale(reason: PlayerOnlyReason) {
        credentials.onBearerRejected()
        sessionState.value = SessionState.afterBearerRejected(sessionState.value, reason)
    }

    /**
     * Records a Subsonic app-password rejection and returns the state it moved to.
     *
     * Safe to call repeatedly: several Subsonic requests are usually in flight when the first is
     * refused and each of them reports it. The transition itself is
     * `SessionState.afterAppPasswordRejected`, so every caller agrees on the answer.
     */
    override suspend fun markAppPasswordRejected(subsonicErrorCode: Int?): SessionState {
        credentials.onAppPasswordRejected()
        val next: SessionState = SessionState.afterAppPasswordRejected(
            current = sessionState.value,
            bearerAlive = credentials.bearerToken() != null,
        )
        sessionState.value = next
        return next
    }

    /**
     * Mints a replacement app-password with the existing bearer and stores it.
     *
     * Single-flight by construction: concurrent callers await the same attempt, because the server
     * caps active app-passwords at 25 per user and a burst of failed Subsonic calls would otherwise
     * exhaust that in seconds.
     *
     * A *retryable* failure leaves the session in `RepairingAppPassword` with the error recorded - a
     * repair that failed in a tunnel resumes when the connection comes back, and handing the user a
     * sign-in screen for that would be wrong. Only a permanent failure reaches re-onboarding.
     */
    override suspend fun repairAppPassword(): Outcome<SessionState> = repairLock.withLock {
        val current: SessionState = sessionState.value
        if (current is SessionState.Authenticated && credentials.appPassword() != null) {
            // Another caller already repaired it while this one waited for the lock.
            return@withLock Outcome.Success(current)
        }
        val repairing: SessionState.RepairingAppPassword = current as? SessionState.RepairingAppPassword
            ?: return@withLock Outcome.Failure(
                NeedlerError.Unexpected("repairAppPassword called in state " + current::class.simpleName),
            )

        val minted: Outcome<AppPasswordCreateResponseDto> = mintAppPassword(bearer = null)
        when (minted) {
            is Outcome.Failure -> {
                val next: SessionState =
                    SessionState.afterAppPasswordRepairFailed(repairing, minted.error)
                sessionState.value = next
                return@withLock Outcome.Failure(minted.error)
            }
            is Outcome.Success -> {
                if (!credentials.saveAppPassword(minted.value.secret)) {
                    // The secret is shown once and is never re-fetchable, so a failed write must
                    // revoke rather than leave a credential neither side can use.
                    networkCall { v1.revokeAppPassword(minted.value.appPassword.id) }
                    val error = NeedlerError.Unexpected("could not store the new app-password")
                    sessionState.value = SessionState.afterAppPasswordRepairFailed(repairing, error)
                    return@withLock Outcome.Failure(error)
                }
                val next: SessionState = repairing.repaired()
                sessionState.value = next
                return@withLock Outcome.Success(next)
            }
        }
    }

    override suspend fun signOut(revokeRemote: Boolean): Outcome<Unit> {
        if (revokeRemote) {
            // Best effort: an abandoned device should not keep a live session in the user's list, but
            // a failure here must not block the user from signing out of their own phone.
            val existing: Outcome<AppPasswordListDto> = networkCall { v1.appPasswords() }
            if (existing is Outcome.Success) {
                existing.value.items
                    .filter { it.name == APP_PASSWORD_NAME }
                    .forEach { entry -> networkCall { v1.revokeAppPassword(entry.id) } }
            }
        }
        credentials.clear()
        // `clear` wipes the pinned fingerprint with the secrets, so the live store has to go too,
        // or this process would keep trusting a certificate the disk no longer records.
        pins.replaceAll(emptyMap())
        capabilitiesState.value = null
        sessionState.value = SessionState.ReonboardingRequired(
            server = null,
            reason = ReonboardingReason.SIGNED_OUT,
        )
        return Outcome.Ok
    }

    // ------------------------------------------------------------------ internals

    /**
     * Mints the two credentials Needler actually uses and stores them.
     *
     * The device session is named after the device, capped at 80 characters after whitespace
     * collapsing - a longer name is a server-side rejection, so it is truncated here. Minting under a
     * name that already exists **rotates** it: the server revokes the previous companion token, which
     * is exactly what a re-install wants and exactly why two devices must never share a name.
     */
    private suspend fun provision(
        url: ServerUrl,
        loginBearer: String,
        deviceName: String,
    ): Outcome<SessionState> {
        val session: Outcome<DeviceSessionResponseDto> = networkCall {
            v1.createDeviceSession(deviceName = normaliseDeviceName(deviceName), bearer = loginBearer)
        }
        val companion: DeviceSessionResponseDto = when (session) {
            is Outcome.Failure -> return session
            is Outcome.Success -> session.value
        }
        val issuedAt: Long = nowMillis()
        if (!credentials.saveCompanionBearer(companion.token, issuedAt)) {
            return Outcome.Failure(NeedlerError.Unexpected("could not store the companion session"))
        }

        // Reuse the `Needler` slot if one exists: revoke and replace, because the secret of the
        // existing one cannot be read back. The cap is 25 active per user.
        val existing: Outcome<AppPasswordListDto> =
            networkCall { v1.appPasswords(bearer = loginBearer) }
        if (existing is Outcome.Success) {
            existing.value.items
                .filter { it.name == APP_PASSWORD_NAME }
                .forEach { entry: AppPasswordDto ->
                    networkCall { v1.revokeAppPassword(entry.id, bearer = loginBearer) }
                }
        }

        val minted: Outcome<AppPasswordCreateResponseDto> = mintAppPassword(bearer = loginBearer)
        val appPassword: AppPasswordCreateResponseDto = when (minted) {
            is Outcome.Failure -> return minted
            is Outcome.Success -> minted.value
        }
        if (!credentials.saveAppPassword(appPassword.secret)) {
            networkCall { v1.revokeAppPassword(appPassword.appPassword.id, bearer = loginBearer) }
            return Outcome.Failure(NeedlerError.Unexpected("could not store the app-password"))
        }

        val user: User = CatalogueMappers.user(companion.user)
        val identity = ServerIdentity(baseUrl = url.baseUrl)
        val authenticated = SessionState.Authenticated(
            server = identity,
            user = user,
            capabilities = capabilitiesState.value ?: ServerCapabilities.Unknown,
            bearerExpiresAt = credentials.companionBearerExpiresAt()
                ?.let { Instant.fromEpochMilliseconds(it) },
        )
        sessionState.value = authenticated

        // The negotiated set replaces the pessimistic default. A failure here is not fatal to sign-in
        // - except the Subsonic gate, which the caller must surface.
        val negotiated: Outcome<ServerCapabilities> = negotiateCapabilities()
        return when (negotiated) {
            is Outcome.Failure ->
                if (negotiated.error is NeedlerError.SubsonicProtocolDisabled) negotiated else {
                    Outcome.Success(sessionState.value)
                }
            is Outcome.Success -> Outcome.Success(sessionState.value)
        }
    }

    private suspend fun mintAppPassword(bearer: String?): Outcome<AppPasswordCreateResponseDto> =
        networkCall { v1.createAppPassword(name = APP_PASSWORD_NAME, bearer = bearer) }

    private fun promoteCapabilities(capabilities: ServerCapabilities) {
        sessionState.value = when (val current: SessionState = sessionState.value) {
            is SessionState.Authenticated -> current.copy(capabilities = capabilities)
            is SessionState.PlayerOnly -> current.copy(capabilities = capabilities)
            is SessionState.RepairingAppPassword -> current.copy(capabilities = capabilities)
            else -> current
        }
    }

    private fun withUser(state: SessionState, user: User): SessionState = when (state) {
        is SessionState.Authenticated -> state.copy(user = user)
        is SessionState.PlayerOnly -> state.copy(user = user)
        is SessionState.RepairingAppPassword -> state.copy(user = user)
        else -> state
    }

    private fun toDomain(dto: ServerCapabilitiesDto): ServerCapabilities {
        val extensions: Set<OpenSubsonicExtension> = dto.extensions.keys
            .mapNotNull(OpenSubsonicExtension::fromWireName)
            .toSet()
        return ServerCapabilities(
            subsonicEnabled = dto.subsonicEnabled,
            // Two-part gate: the extension must be advertised **and** transcoding must be enabled in
            // the Connect Apps settings, because ffmpeg may simply be absent from the server.
            transcodingAvailable = dto.transcodingUsable,
            libraryDownloadAllowed = dto.downloadAllowed,
            extensions = extensions,
            rawExtensions = dto.extensions.keys,
            serverVersion = dto.serverVersion,
            subsonicApiVersion = dto.subsonicProtocolVersion,
            negotiatedAt = Instant.fromEpochMilliseconds(dto.probedAtEpochMillis),
        )
    }

    private fun identity(): ServerIdentity =
        ServerIdentity(baseUrl = credentials.serverUrl()?.baseUrl.orEmpty())

    /**
     * The state to start in, reconstructed from what is in the keystore.
     *
     * Reconstruction rather than a stored enum, because the credentials are the truth: a process that
     * died mid-repair must resume the repair, not restart onboarding.
     *
     * ## The keystore is asked whether it could be read at all, first
     *
     * A store that would not open has no bearer and no app-password to reconstruct from, and read
     * naively that is indistinguishable from both credentials having been revoked - which is how a
     * transient Keystore failure used to present as a fresh install. It is not the same thing, and
     * `ReonboardingReason.CREDENTIALS_UNREADABLE` exists for it: the secrets may still be on the
     * disk, intact, under a key this launch could not use. REQUIREMENTS.md "Expiry, and why playback
     * survives it" establishes that re-onboarding is required "in exactly one case: both credentials
     * are dead"; an unreadable credential is not a dead one, and the user is told which of the two
     * they are looking at rather than being handed onboarding and left to assume they were never
     * signed in.
     *
     * The server address still comes back, because `SecureCredentialStore` keeps it outside the
     * encrypted file precisely so that this state is not also an amnesiac one.
     */
    private fun initialState(): SessionState {
        val saved: ServerUrl? = credentials.serverUrl()
        // Guarded on there being a server, because a store that will not open on a device whose
        // keystore is broken outright looks identical on a first run and after a year of use, and
        // only one of those two users has anything to be told. With no address there is nothing
        // that was lost, so this is a first run and the Connect screen owns it.
        if (saved != null && credentials.state.savedSessionUnavailable) {
            return SessionState.ReonboardingRequired(
                server = ServerIdentity(baseUrl = saved.baseUrl),
                reason = ReonboardingReason.CREDENTIALS_UNREADABLE,
            )
        }
        val url: ServerUrl = saved ?: return SessionState.NotConfigured
        val identity = ServerIdentity(baseUrl = url.baseUrl)
        val bearer: String? = credentials.bearerToken()
        val appPassword: String? = credentials.appPassword()
        return when {
            bearer == null && appPassword == null -> SessionState.ReonboardingRequired(
                server = identity,
                reason = ReonboardingReason.BOTH_CREDENTIALS_DEAD,
            )
            bearer == null -> SessionState.PlayerOnly(
                server = identity,
                user = null,
                capabilities = ServerCapabilities.Unknown,
                reason = if (credentials.isCompanionBearerExpired(nowMillis())) {
                    PlayerOnlyReason.BEARER_EXPIRED
                } else {
                    PlayerOnlyReason.BEARER_REJECTED
                },
            )
            // A bearer with no app-password is a repair in progress, which survives process death.
            // `RepairingAppPassword` needs a user, and the only honest one before `auth/me` answers
            // is a placeholder that `refreshUser` replaces.
            appPassword == null -> SessionState.RepairingAppPassword(
                server = identity,
                user = UNKNOWN_USER,
                capabilities = ServerCapabilities.Unknown,
                bearerExpiresAt = credentials.companionBearerExpiresAt()
                    ?.let { Instant.fromEpochMilliseconds(it) },
            )
            else -> SessionState.Authenticated(
                server = identity,
                user = UNKNOWN_USER,
                capabilities = ServerCapabilities.Unknown,
                bearerExpiresAt = credentials.companionBearerExpiresAt()
                    ?.let { Instant.fromEpochMilliseconds(it) },
            )
        }
    }

    /**
     * A TLS failure on the Connect probe is the one place a certificate must be shown, so it keeps
     * its own case instead of collapsing to "offline" as it does everywhere else.
     */
    private fun probeFailure(error: NeedlerError, url: ServerUrl): NeedlerError = when (error) {
        // `auth/providers` is public, so anything that is not a transport failure means the address
        // does not point at a DroppedNeedle at all - which is exactly what the Connect screen has to
        // tell the user, before any credential is typed.
        is NeedlerError.NotFound, is NeedlerError.ProtocolViolation ->
            NeedlerError.NotADroppedNeedleServer(url.baseUrl)
        else -> error
    }

    /**
     * Server-side cap: 80 characters after whitespace collapsing. A longer name is rejected, so it is
     * truncated here rather than left for the server to refuse.
     */
    private fun normaliseDeviceName(raw: String): String {
        val collapsed: String = raw.trim().replace(Regex("\\s+"), " ")
        val name: String = collapsed.ifEmpty { DEFAULT_DEVICE_NAME }
        return if (name.length <= MAX_DEVICE_NAME_LENGTH) name else name.take(MAX_DEVICE_NAME_LENGTH)
    }

    public companion object {
        public const val APP_PASSWORD_NAME: String = "Needler"
        public const val MAX_DEVICE_NAME_LENGTH: Int = 80
        public const val DEFAULT_DEVICE_NAME: String = "Needler"

        /** A stand-in until `GET /api/v1/auth/me` answers. Least privilege until proven otherwise. */
        internal val UNKNOWN_USER: User = User(
            id = "",
            username = "",
            displayName = null,
            role = app.needler.core.domain.model.UserRole.USER,
        )
    }
}
