package app.needler.core.data.security

import android.content.Context
import android.content.SharedPreferences
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.network.CredentialProvider
import app.needler.core.network.ProxyCredentialStore
import app.needler.core.network.ProxyCredentials
import app.needler.core.network.ServerUrl
import app.needler.core.network.tls.CertificatePinStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The only place Needler keeps a secret: `EncryptedSharedPreferences` under a Keystore master key.
 *
 * Holds the companion bearer, the app-password, the saved server URL and any pinned certificate
 * fingerprint, and implements `:core:network`'s [CredentialProvider] so OkHttp interceptors can
 * read them without knowing where they live.
 *
 * ## Rules this class exists to enforce
 *
 * REQUIREMENTS.md, Authentication point 4 and Security points 1 and 3:
 *
 *  * Both secrets live in `EncryptedSharedPreferences` under a Keystore master key. The master key
 *    never leaves the hardware-backed keystore, so the file on disk is useless without the device.
 *  * **Never written to Room, logs, analytics or crash reports.** Nothing in this class logs a
 *    value, and nothing returns a value that reads as a credential in a stack trace: [toString] is
 *    overridden to say nothing at all, because the default data-class-ish rendering of a holder
 *    object is exactly how a token ends up in a bug report. What this package does log is the
 *    *failure to open the file* and, per write, **which key was written and whether it reached the
 *    disk** - a key name from this class's own constants and a boolean, never a value. See
 *    `CredentialStoreUnlock.kt` and `credentialStoreDiagnostics` for why logging here is required
 *    rather than merely allowed, and [commit] for why the successful case has to be on the record
 *    too.
 *  * `android:allowBackup="false"` in the `:app` manifest keeps the file off cloud backups. That
 *    manifest belongs to another module; if the flag is ever flipped on, this file must be excluded
 *    explicitly, because a Keystore-encrypted blob restored onto a different device is not just
 *    useless, it is a credential leaving the device.
 *
 * ## Why reads are cached in memory
 *
 * [CredentialProvider] is called from OkHttp interceptors on every request and its contract says
 * reads must be cheap. Each `EncryptedSharedPreferences` read decrypts a value, so the four fields
 * are held in volatile memory and refreshed on write. The process's own memory is not a weaker
 * place to hold them than the SharedPreferences cache underneath, which holds the ciphertext and
 * the plaintext for as long as the file is open.
 *
 * ## Writes use `commit`, not `apply`
 *
 * The app-password secret is returned by the server exactly once and is never re-fetchable, so a
 * failed write must abort the flow and revoke it (Authentication point 3). `apply()` cannot report
 * failure; `commit()` can, and every setter returns whether the value actually reached the disk.
 *
 * Every one of those commits also leaves a diagnostics line, successful or not. [commit] says why
 * the successful half is not noise: without it, a device that came back on the Connect screen with
 * a silent log could not distinguish a store that was never written from a session lost elsewhere,
 * and that ambiguity cost an afternoon on a cable.
 *
 * ## What happens when the file will not open
 *
 * [state] says. The store is usable in every case - a launch that cannot read its secrets must not
 * be a launch that crashes - and the three outcomes are not interchangeable:
 * [CredentialStoreState.Locked] means the secrets are intact on disk and this launch could not read
 * them, and **nothing is deleted**; [CredentialStoreState.Rebuilt] means the Keystore key is
 * provably gone and the file was discarded with it. The sequence that decides between them is
 * [unlock], and the reason it is the shape it is is written down there.
 */
public class SecureCredentialStore private constructor(
    initialPreferences: SharedPreferences,
    private val files: CredentialStoreFiles?,
    private val serverMirror: SharedPreferences?,
    private val diagnostics: DiagnosticsSink,
    initialState: CredentialStoreState,
) : CredentialProvider, ProxyCredentialStore {

    /**
     * Volatile and a `var`, because a [CredentialStoreState.Locked] store heals itself on the first
     * write and swaps the real file in behind the interceptors already reading through it.
     */
    @Volatile
    private var preferences: SharedPreferences = initialPreferences

    @Volatile
    private var storeState: CredentialStoreState = initialState

    /**
     * How this store came to be holding what it is holding.
     *
     * Read by `DefaultSessionRepository` to decide the session state the app starts in: a store
     * whose saved session is unavailable produces `ReonboardingRequired` with
     * `ReonboardingReason.CREDENTIALS_UNREADABLE` rather than the blank first-run form, which is
     * REQUIREMENTS.md "Expiry, and why playback survives it" applied to the one credential failure
     * that is nobody's fault - a user who sees onboarding assumes they were never signed in.
     */
    public val state: CredentialStoreState get() = storeState

    // In-memory cache. Volatile: interceptors read these from OkHttp's dispatcher threads while the
    // onboarding flow writes them from a coroutine.
    @Volatile
    private var cachedServerUrl: String? = null

    @Volatile
    private var cachedBearer: String? = null

    @Volatile
    private var cachedAppPassword: String? = null

    @Volatile
    private var cachedFingerprint: String? = null

    /**
     * The host the pinned fingerprint belongs to, normalised.
     *
     * Written beside the fingerprint by [pinCertificate] rather than re-derived from the saved
     * address on every read, so that changing the address afterwards cannot silently re-point an
     * exception the user granted to one server at a different one. See [pinnedCertificateHost] for
     * what an install pinned before this key existed is taken to mean.
     */
    @Volatile
    private var cachedFingerprintHost: String? = null

    @Volatile
    private var parsedServerUrl: ServerUrl? = null

    /**
     * Fixed headers for an edge proxy in front of the server - a Cloudflare Access service token,
     * a basic-auth pair, an API gateway key.
     *
     * Every value is a credential and lives here for the same reason the other two do: the file is
     * encrypted under a Keystore master key, nothing in this class logs a value, and nothing returns
     * a value that would render in a stack trace.
     */
    @Volatile
    private var cachedProxyCredentials: ProxyCredentials = ProxyCredentials.None

    private val sessionStaleState: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /**
     * True once the `/api/v1` lane has answered `401` and the companion session has been discarded.
     *
     * The app watches this to show a non-blocking prompt to sign in again. Playback, browsing and
     * playlists keep working throughout: the app-password does not expire, so an expired session
     * degrades Needler to a pure music player rather than bricking it.
     */
    public val sessionStale: StateFlow<Boolean> = sessionStaleState.asStateFlow()

    /**
     * True while the app-password has been rejected and no replacement has been stored yet.
     *
     * **Not a request to re-onboard.** With the bearer still alive the data layer mints a
     * replacement app-password with it and playback resumes with nothing shown to the user; this
     * flow is what tells it there is a repair to do, and what the session state renders as
     * `RepairingAppPassword` while it happens. See [isReonboardingRequired] for the case that does
     * reach the user.
     */
    private val appPasswordRepairNeededState: MutableStateFlow<Boolean> = MutableStateFlow(false)

    public val appPasswordRepairNeeded: StateFlow<Boolean> = appPasswordRepairNeededState.asStateFlow()

    init {
        // The server address falls back to the unencrypted mirror, which is the whole reason the
        // mirror exists: see `saveServerUrl`. Every other field has no fallback and must not have
        // one - they are the secrets.
        val encryptedServerUrl: String? = preferences.getString(KEY_SERVER_URL, null)
        cachedServerUrl = encryptedServerUrl ?: serverMirror?.getString(KEY_SERVER_URL, null)
        // Backfilled on a successful open, so an install that was signed in before the mirror
        // existed gains one without waiting for the user to re-enter an address. Without this, the
        // first launch after upgrading is the one launch where a keystore failure would still cost
        // the address - which is exactly the launch an upgrade is most likely to break.
        if (encryptedServerUrl != null && serverMirror?.getString(KEY_SERVER_URL, null) == null) {
            runCatching { serverMirror?.edit()?.putString(KEY_SERVER_URL, encryptedServerUrl)?.apply() }
        }
        cachedBearer = preferences.getString(KEY_COMPANION_BEARER, null)
        cachedAppPassword = preferences.getString(KEY_APP_PASSWORD, null)
        cachedFingerprint = preferences.getString(KEY_CERT_FINGERPRINT, null)
        cachedFingerprintHost = preferences.getString(KEY_CERT_HOST, null)
        cachedProxyCredentials = ProxyCredentials.decode(preferences.getString(KEY_PROXY_HEADERS, null))
        parsedServerUrl = ServerUrl.parseOrNull(cachedServerUrl)
        sessionStaleState.value = cachedBearer == null && cachedAppPassword != null
        // A process that died mid-repair comes back with a bearer and no app-password. The repair is
        // resumed rather than restarted from a sign-in screen, which is the whole point of it.
        appPasswordRepairNeededState.value = cachedAppPassword == null && cachedBearer != null
    }

    // ------------------------------------------------------------------ CredentialProvider

    override fun serverUrl(): ServerUrl? = parsedServerUrl

    override fun bearerToken(): String? = cachedBearer

    override fun appPassword(): String? = cachedAppPassword

    override fun proxyCredentials(): ProxyCredentials = cachedProxyCredentials

    /**
     * Saves or clears the proxy headers.
     *
     * Committed rather than applied, like the other secrets: onboarding sends the very first probe
     * with these attached, and a write that silently failed would produce a connect attempt that
     * is intercepted for no visible reason.
     */
    override fun saveProxyCredentials(credentials: ProxyCredentials): Boolean {
        val committed: Boolean = commit(KEY_PROXY_HEADERS) {
            if (credentials.isEmpty) {
                it.remove(KEY_PROXY_HEADERS)
            } else {
                it.putString(KEY_PROXY_HEADERS, credentials.encode())
            }
        }
        if (committed) cachedProxyCredentials = credentials
        return committed
    }

    /**
     * The `/api/v1` lane answered `401`.
     *
     * The bearer is discarded rather than kept and retried: a companion session cannot mint another
     * device session, so silent renewal is impossible without storing the account password, and
     * Needler does not store it. Sending a known-dead token on every subsequent call would only
     * spend battery and radio.
     *
     * Non-blocking, as the contract requires: the in-memory value is cleared first and the removal
     * from disk is queued with `apply()`. This is the one write that does not use `commit()` -
     * losing the removal costs nothing, because the token is already useless.
     */
    override fun onBearerRejected() {
        cachedBearer = null
        sessionStaleState.value = true
        // Logged for the same reason a successful write is: this is one of the two ways a session
        // the user had can stop existing, and a log that records only the unlock failures cannot
        // tell it apart from a session that was never saved.
        report(DiagnosticsLevel.Warn, KEY_COMPANION_BEARER, "discarded - the /api/v1 lane said 401")
        runCatching {
            preferences.edit()
                .remove(KEY_COMPANION_BEARER)
                .remove(KEY_BEARER_ISSUED_AT)
                .apply()
        }
    }

    /**
     * The Subsonic lane answered code 40 or 44: the app-password has been revoked.
     *
     * **The bearer is deliberately kept.** It is the credential that mints the replacement -
     * `POST /api/v1/connect-apps/app-passwords` needs nothing else - so discarding it here would
     * destroy the only tool the repair has and turn one revoked secret into a lost session, a lost
     * cache and a sign-in screen. The user revoking an app-password from the web UI without
     * realising which app it belonged to is the common case, not an exotic one.
     *
     * Only the app-password goes, and [appPasswordRepairNeeded] is raised so the data layer can mint
     * a replacement. Playback pauses until it does, because streaming is on the Subsonic lane, and
     * then resumes with nothing shown to the user.
     *
     * Non-blocking and safe to call repeatedly, as the contract requires: several Subsonic requests
     * are usually in flight when the first one is refused. The removal is queued with `apply()`
     * rather than committed - a lost removal costs nothing, because the secret is already useless.
     */
    override fun onAppPasswordRejected() {
        cachedAppPassword = null
        appPasswordRepairNeededState.value = true
        report(
            DiagnosticsLevel.Warn,
            KEY_APP_PASSWORD,
            "discarded - Subsonic said the secret was revoked; the bearer is kept to mint another",
        )
        runCatching {
            preferences.edit()
                .remove(KEY_APP_PASSWORD)
                .apply()
        }
    }

    /**
     * True when both credentials are gone and a server is saved: the one credential state the user
     * has to be told about, because nothing left on the device can mint anything.
     *
     * A missing server is not this state - that is an app that has never been set up, which the
     * Connect screen owns.
     *
     * Also true of a store whose file would not open, because a secret that cannot be read is as
     * unusable as one that has been revoked. The two are **not** the same thing to the user, and
     * [state] is what separates them: the secrets may still be on the disk, so nothing is deleted
     * and the message says the saved session could not be unlocked rather than that it is gone.
     */
    public fun isReonboardingRequired(): Boolean =
        parsedServerUrl != null && cachedAppPassword == null && cachedBearer == null

    // ------------------------------------------------------------------ writes

    /**
     * Saves the normalised server address. Stored as [ServerUrl.baseUrl], which is also the
     * identity string `sync_state.server_identity` holds, so the two comparisons cannot disagree.
     *
     * ## Why it is written twice
     *
     * Once encrypted with the secrets, and once to an ordinary unencrypted preferences file. **The
     * server address is not a secret.** It is the one thing in this store that is not, and keeping
     * it only inside the encrypted file meant that a Keystore failure cost the user their address
     * and their username as well as their credentials - so the app they re-onboarded into was
     * indistinguishable from a fresh install, which is precisely the confusion that made the
     * logged-out state ambiguous in the first place. The mirror survives a key the secrets cannot,
     * so the Connect screen can say "this is your server, sign in again" instead of showing an
     * empty form.
     *
     * It is written unconditionally, before the encrypted commit, because a [CredentialStoreState]
     * of `Locked` is exactly the case where the encrypted write is the one that fails and the
     * address is the one thing still worth keeping. `apply()` rather than `commit()`: the caller is
     * told whether the *credential* store took the value, which is the answer that matters, and
     * blocking onboarding on the mirror reaching the disk would be paying latency for a hint.
     *
     * The rejected alternative was a second Keystore-encrypted file for the non-secret. It buys
     * nothing - the threat model for a plain server address in app-private storage that
     * `android:allowBackup="false"` keeps on the device is the same as for the Room mirror next to
     * it, which already holds every album title the user owns - and it would fail for the same
     * reason the first file did.
     *
     * ## A pin does not follow the address
     *
     * Changing the **host** drops the pinned certificate. REQUIREMENTS.md "Secrets and migrations"
     * already drops the mirror and the audio cache when the server identity changes, and the pin
     * belongs in that list for a stronger reason than either: the mirror and the cache are only
     * stale data, while a fingerprint confirmed for one host, left behind, is an exception the
     * **next** host inherits without the user ever seeing it. REQUIREMENTS.md "Self-signed
     * certificates" scopes a pin to "the one host", so a pin whose host is gone has nothing left
     * to be scoped to.
     *
     * Only the host is compared, not the whole address. A scheme or port change is the same server
     * - `probeServer` walks several rungs of one host before anything has failed, and dropping the
     * pin on each of them would revoke the user's decision mid-walk.
     *
     * A fingerprint whose host cannot be worked out at all goes too. [pinnedCertificateHost]
     * answers null for it, and the alternative - leaving it to be adopted by whatever address is
     * saved next - is the one failure mode this whole class is arranged to prevent: a host
     * inheriting an exception the user granted to a different one.
     *
     * The caller is told whether the *address* was committed, as it always was. A failure to remove
     * the pin cannot be reported here, and does not need to be: the pin is host-scoped on the way
     * out as well, so a stale one grants nothing to the new host even if it survives on disk.
     */
    public fun saveServerUrl(serverUrl: ServerUrl): Boolean {
        val rendered: String = serverUrl.baseUrl
        if (cachedFingerprint != null &&
            pinnedCertificateHost() != CertificatePinStore.normaliseHost(serverUrl.host)
        ) {
            clearPinnedCertificate()
        }
        runCatching { serverMirror?.edit()?.putString(KEY_SERVER_URL, rendered)?.apply() }
        val committed: Boolean = commit(KEY_SERVER_URL + " (and the unencrypted mirror)") {
            it.putString(KEY_SERVER_URL, rendered)
        }
        if (committed) {
            cachedServerUrl = rendered
            parsedServerUrl = serverUrl
        }
        return committed
    }

    /**
     * Saves the companion bearer and when it was issued.
     *
     * The issue time is stored because the 30-day lifetime is fixed and does not slide on use, so
     * the app can warn from day 25 rather than letting re-authentication be a surprise.
     */
    public fun saveCompanionBearer(token: String, issuedAtMillis: Long): Boolean {
        val committed: Boolean = commit(KEY_COMPANION_BEARER + " + " + KEY_BEARER_ISSUED_AT) {
            it.putString(KEY_COMPANION_BEARER, token)
            it.putLong(KEY_BEARER_ISSUED_AT, issuedAtMillis)
        }
        if (committed) {
            cachedBearer = token
            sessionStaleState.value = false
        }
        return committed
    }

    /**
     * Saves the app-password secret, whether from onboarding or from a silent repair.
     *
     * **Check the result.** The secret is shown exactly once by
     * `POST /api/v1/connect-apps/app-passwords` and is never re-fetchable, so a false return must
     * abort onboarding and revoke the app-password server-side rather than leaving the user with a
     * credential neither side can use.
     */
    public fun saveAppPassword(secret: String): Boolean {
        val committed: Boolean = commit(KEY_APP_PASSWORD) { it.putString(KEY_APP_PASSWORD, secret) }
        if (committed) {
            cachedAppPassword = secret
            appPasswordRepairNeededState.value = false
        }
        return committed
    }

    /**
     * Pins one leaf certificate for one host, by SHA-256 fingerprint.
     *
     * Scoped to the single host the user pinned - never a global disabling of validation. A changed
     * fingerprint must fail loudly and require re-confirmation, which is why replacing a pin is an
     * explicit call and not a side effect of a TLS failure.
     *
     * ## Why the host is a parameter and not read off the saved address
     *
     * [pinsFor][app.needler.core.network.tls.CertificatePinStore.pinsFor] is keyed on a host, and
     * this file used to hold a bare fingerprint with nothing beside it. The host a restored pin
     * belongs to is the host of the **saved server URL** - `DefaultSessionRepository.probeServer`
     * saves each rung before dialling it, so the address saved when a rung fails TLS is exactly the
     * one that presented the certificate, and `trustCertificate` reads the host from there.
     *
     * It is then written down rather than re-derived, because the saved address can change later.
     * A fingerprint attributed to whatever server happens to be configured at read time is a pin
     * the user granted to one host being handed to another, which REQUIREMENTS.md "Self-signed
     * certificates" forbids outright: "pin the leaf certificate for the one host".
     *
     * Both keys go down in one commit. Half a pin is worse than none: a fingerprint with no host
     * cannot be scoped, and a host with no fingerprint matches nothing.
     *
     * The fingerprint is stored in the canonical hex of
     * [sha256Hex][app.needler.core.network.tls.CertificatePinStore.sha256Hex] so that this file and
     * the in-memory store the handshake consults hold the identical string. That is the one place
     * the spelling is settled; nothing downstream converts.
     */
    public fun pinCertificate(host: String, sha256Fingerprint: String): Boolean {
        val normalisedHost: String = CertificatePinStore.normaliseHost(host)
        val normalisedPin: String = CertificatePinStore.normalisePin(sha256Fingerprint)
        val committed: Boolean = commit(KEY_CERT_FINGERPRINT + " + " + KEY_CERT_HOST) {
            it.putString(KEY_CERT_FINGERPRINT, normalisedPin)
            it.putString(KEY_CERT_HOST, normalisedHost)
        }
        if (committed) {
            cachedFingerprint = normalisedPin
            cachedFingerprintHost = normalisedHost
        }
        return committed
    }

    /**
     * Drops the pin, whichever host it belonged to: the server's certificate validates normally
     * now, or the saved server has been replaced by one this pin says nothing about.
     */
    public fun clearPinnedCertificate(): Boolean {
        if (cachedFingerprint == null && cachedFingerprintHost == null) return true
        val committed: Boolean = commit(KEY_CERT_FINGERPRINT + " + " + KEY_CERT_HOST) {
            it.remove(KEY_CERT_FINGERPRINT)
            it.remove(KEY_CERT_HOST)
        }
        if (committed) {
            cachedFingerprint = null
            cachedFingerprintHost = null
        }
        return committed
    }

    /**
     * The host the pinned certificate belongs to, or null when nothing is pinned.
     *
     * An install that pinned a certificate before the host was recorded beside it has a fingerprint
     * and no host. Its pin is attributed to the saved server's host, which is the only server that
     * install ever had an address for and therefore the only one the fingerprint can have come
     * from. The alternative - discarding a pin that cannot name its host - would send a user who
     * had already confirmed their certificate back through the prompt for no reason.
     */
    public fun pinnedCertificateHost(): String? {
        if (cachedFingerprint == null) return null
        val recorded: String? = cachedFingerprintHost
        if (recorded != null) return recorded
        return parsedServerUrl?.host?.let(CertificatePinStore::normaliseHost)
    }

    /**
     * The pinned leaf-certificate fingerprint **for [host]**, or null when the user has pinned
     * nothing for it.
     *
     * Host-scoped rather than a bare read, because the one caller is the decision between
     * `CertificateUntrusted` and `CertificateChanged`. An unscoped read would compare the
     * fingerprint of a server the user pinned against the certificate of one they did not, and
     * announce that their certificate had changed on a host that was never pinned - the loud
     * failure REQUIREMENTS.md asks for, fired at the wrong server.
     *
     * Not part of [CredentialProvider] as `:core:network` declares it: a pin is not a credential,
     * it is never sent on a request, and folding it in would hand every interceptor a handle to it.
     */
    public fun pinnedCertificateFor(host: String): String? {
        val pinnedHost: String = pinnedCertificateHost() ?: return null
        if (pinnedHost != CertificatePinStore.normaliseHost(host)) return null
        return cachedFingerprint
    }

    /**
     * Every pin on this device, in the shape
     * [MutableCertificatePinStore][app.needler.core.network.tls.MutableCertificatePinStore] is
     * seeded with at startup.
     *
     * At most one entry, because one server is configured at a time. It is a map rather than a pair
     * so that the TLS layer's host scoping stays the only model of this: a pin is something a host
     * has, not something the app has.
     */
    public fun pinnedCertificates(): Map<String, Set<String>> {
        val host: String = pinnedCertificateHost() ?: return emptyMap()
        val fingerprint: String = cachedFingerprint ?: return emptyMap()
        return mapOf(host to setOf(fingerprint))
    }

    // ------------------------------------------------------------------ session state

    /** Epoch milliseconds the companion bearer was minted, or null when there is no session. */
    public fun companionBearerIssuedAt(): Long? {
        val issued: Long = preferences.getLong(KEY_BEARER_ISSUED_AT, 0L)
        return if (issued > 0L) issued else null
    }

    /** Epoch milliseconds the companion bearer expires: issue time plus a fixed 30 days. */
    public fun companionBearerExpiresAt(): Long? =
        companionBearerIssuedAt()?.plus(COMPANION_BEARER_LIFETIME_MILLIS)

    /**
     * True when the session has passed its fixed 30-day life, so `/api/v1` calls will fail even
     * though playback will not.
     */
    public fun isCompanionBearerExpired(nowMillis: Long): Boolean {
        val expiry: Long = companionBearerExpiresAt() ?: return cachedBearer == null
        return nowMillis >= expiry
    }

    /**
     * True from day 25 of the session's life: the point at which the app warns, so that
     * re-authentication is rarely a surprise.
     */
    public fun shouldWarnAboutExpiry(nowMillis: Long): Boolean {
        val issued: Long = companionBearerIssuedAt() ?: return false
        return nowMillis - issued >= COMPANION_BEARER_WARNING_MILLIS
    }

    /** True when onboarding has produced everything the two lanes need. */
    public fun isFullyProvisioned(): Boolean =
        parsedServerUrl != null && cachedAppPassword != null && cachedBearer != null

    /** True when playback can work: a server and an app-password, with or without a live session. */
    public fun canPlay(): Boolean = parsedServerUrl != null && cachedAppPassword != null

    /**
     * Wipes every secret: sign-out, or the start of re-onboarding against a different server.
     *
     * The audio cache and the mirror are dropped separately, by
     * `NeedlerDatabase.clearForServerChange`.
     *
     * The unencrypted server mirror goes too. It exists so that a *failure* cannot cost the user
     * their address; a sign-out is not a failure, and leaving the last server behind after one
     * would be a stale address pre-filled on a form the user reached deliberately.
     */
    public fun clear(): Boolean {
        runCatching { serverMirror?.edit()?.clear()?.apply() }
        val committed: Boolean = commit("every key (sign-out)") { it.clear() }
        if (committed) {
            cachedServerUrl = null
            cachedBearer = null
            cachedAppPassword = null
            cachedFingerprint = null
            cachedFingerprintHost = null
            cachedProxyCredentials = ProxyCredentials.None
            parsedServerUrl = null
            sessionStaleState.value = false
            appPasswordRepairNeededState.value = false
        }
        return committed
    }

    /** Says nothing. A credential holder that renders its contents is a credential in a log file. */
    override fun toString(): String =
        "SecureCredentialStore(provisioned=" + isFullyProvisioned() + ", state=" + storeState + ")"

    /**
     * One synchronous write, reported either way.
     *
     * ## Why a *successful* write is logged, when nothing else on a healthy launch is
     *
     * Because the absence of a line was indistinguishable from the absence of a write. The unlock
     * path logs only failures - deliberately, and `EncryptedCredentialFile` argues the case - so a
     * device that came back on the Connect screen with a silent log supported two readings at once:
     * the store was never written, or the store was written and something else lost the session.
     * Those have different fixes, and separating them took a device, a cable and an afternoon. One
     * line per write settles it from a log the user can share, which is what REQUIREMENTS.md
     * "Observability" asks a diagnostics log to be for.
     *
     * It carries no secret and cannot be made to. [what] is a **key name**, supplied by this class
     * from its own `KEY_` constants and never derived from a value, and the only other thing on the
     * line is whether the commit returned true. The `SecurityException` from a failed encryption
     * stays unlogged for the reason it always did: its message can quote the value that failed.
     *
     * The level is deliberately asymmetric. A write that landed is `Info`, which is one line per
     * sign-in and two per silent repair; a write that did not is `Error`, because the app-password
     * is shown exactly once and the caller is about to abort and revoke on the strength of it.
     *
     * @param what the preference key or keys this write touches, for the log line.
     */
    private fun commit(what: String, block: (SharedPreferences.Editor) -> Unit): Boolean {
        if (!healIfLocked()) {
            report(DiagnosticsLevel.Error, "wrote " + what, "refused, the store is locked")
            return false
        }
        val editor: SharedPreferences.Editor = preferences.edit()
        block(editor)
        val committed: Boolean = try {
            editor.commit()
        } catch (error: SecurityException) {
            // Deliberately swallowed without logging: the exception message can contain the value
            // that failed to encrypt. The boolean is the signal the caller must act on.
            false
        }
        report(
            level = if (committed) DiagnosticsLevel.Info else DiagnosticsLevel.Error,
            what = "wrote " + what,
            outcome = if (committed) "reached the disk" else "did NOT reach the disk",
        )
        return committed
    }

    /** One line, in the same `NeedlerCreds` voice as the unlock sequence. Never a value. */
    private fun report(level: DiagnosticsLevel, what: String, outcome: String) {
        diagnostics.record(level, "credential store: " + what + " - " + outcome)
    }

    /**
     * Makes a [CredentialStoreState.Locked] store writable, if it can be made writable at all.
     *
     * ## Why a write is the moment the store may destroy itself
     *
     * A locked store is holding ciphertext it cannot read. Nothing is deleted while that is all
     * that is true, because the next launch may well read it. But a *write* means the user has
     * typed a replacement credential: at that instant the unreadable blob is a blob they have
     * asked to replace, so discarding it costs them nothing they have not already decided to give
     * up - and refusing to discard it would leave the Connect screen as a dead end where sign-in
     * appears to succeed and nothing is ever persisted.
     *
     * So this is the one path that passes `mayRebuild = true` to [unlock], and the ordering inside
     * [unlock] still gives the data every chance first: the file is opened twice, and only if both
     * attempts fail is anything thrown away. A store that opens on the retry keeps every secret it
     * had, and the write lands on top of them.
     *
     * Returns false only when there is no file behind this store at all, which is the unit-test
     * construction. A production store always has one and always ends up writable.
     */
    private fun healIfLocked(): Boolean {
        if (storeState.canPersist) return true
        val target: CredentialStoreFiles = files ?: return false
        val healed: UnlockResult = unlock(files = target, diagnostics = diagnostics, mayRebuild = true)
        preferences = healed.preferences
        storeState = healed.state
        return healed.state.canPersist
    }

    public companion object {

        /** Fixed 30-day companion-session lifetime. It does not slide on use. */
        public const val COMPANION_BEARER_LIFETIME_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

        /** Warn from day 25. */
        public const val COMPANION_BEARER_WARNING_MILLIS: Long = 25L * 24 * 60 * 60 * 1000

        /** The encrypted file's name. Frozen: changing it orphans every existing credential. */
        public const val FILE_NAME: String = "needler_credentials"

        /**
         * The unencrypted file holding the one value in this store that is not a secret: the server
         * address. Frozen for the same reason as [FILE_NAME].
         */
        public const val SERVER_FILE_NAME: String = "needler_server"

        private const val KEY_SERVER_URL: String = "server_url"
        private const val KEY_COMPANION_BEARER: String = "companion_bearer"
        private const val KEY_BEARER_ISSUED_AT: String = "companion_bearer_issued_at"
        private const val KEY_APP_PASSWORD: String = "app_password"
        private const val KEY_CERT_FINGERPRINT: String = "pinned_certificate_sha256"

        /**
         * The host [KEY_CERT_FINGERPRINT] was confirmed for. Added after the pin itself, so a
         * fingerprint with no host beside it is an older install's - see `pinnedCertificateHost`.
         */
        private const val KEY_CERT_HOST: String = "pinned_certificate_host"
        private const val KEY_PROXY_HEADERS: String = "proxy_headers"

        /**
         * Opens the store, creating the Keystore master key if needed.
         *
         * ## Recovery from an unreadable keystore
         *
         * A Keystore-backed key can become unusable: a device migration, a restore onto different
         * hardware, or the user changing their lock screen in a way that invalidates keys. The
         * symptom is a `GeneralSecurityException` or an `IOException` from
         * `EncryptedSharedPreferences.create`, and the hard part is that the same two exceptions
         * also come out of a failure that means nothing at all - the factory is known to fail
         * transiently under concurrent access and around process death.
         *
         * The previous implementation did not distinguish them. It deleted the credential file and
         * its backup on either, logged nothing, and reopened; a `force-stop` or an ordinary
         * low-memory process kill therefore destroyed a working session, and the user was returned
         * to a blank first-run form having lost their server address and username with their
         * secrets. Reproduced twice on a device on 2026-10-01.
         *
         * [unlock] is the replacement and carries the reasoning. In short: every failure is logged
         * and retried once; a transient failure deletes nothing and leaves the store
         * [CredentialStoreState.Locked] with the secrets intact on disk; only a
         * `KeyPermanentlyInvalidatedException`, or a write the user has already asked for, discards
         * anything. The saved server address survives all three, in the unencrypted mirror
         * `saveServerUrl` keeps.
         *
         * Deleting the file does **not** revoke anything server-side. The companion session and the
         * app-password remain listed on the server until the user revokes them or they expire; the
         * onboarding flow replaces the app-password named `Needler` rather than adding a second one,
         * and the cap is 25 per user.
         */
        public fun create(context: Context): SecureCredentialStore {
            val application: Context = context.applicationContext
            val files = EncryptedCredentialFile(application)
            val diagnostics: DiagnosticsSink = credentialStoreDiagnostics()
            val opened: UnlockResult = unlock(files = files, diagnostics = diagnostics)
            return SecureCredentialStore(
                initialPreferences = opened.preferences,
                files = files,
                serverMirror = application.getSharedPreferences(
                    SERVER_FILE_NAME,
                    Context.MODE_PRIVATE,
                ),
                diagnostics = diagnostics,
                initialState = opened.state,
            )
        }

        /** For tests: wrap any [SharedPreferences], including an in-memory fake. */
        public fun createForTesting(preferences: SharedPreferences): SecureCredentialStore =
            SecureCredentialStore(
                initialPreferences = preferences,
                files = null,
                serverMirror = null,
                diagnostics = DiagnosticsSink.None,
                initialState = CredentialStoreState.Opened,
            )

        /**
         * For tests of the unlock and heal paths: a store over a stand-in file, in a chosen state.
         *
         * `internal` because [CredentialStoreFiles] is, which is the point - the decision this
         * exercises is the one that destroyed credentials on a device, and it has to be assertable
         * without a device.
         */
        internal fun createForTesting(
            preferences: SharedPreferences,
            files: CredentialStoreFiles?,
            serverMirror: SharedPreferences?,
            state: CredentialStoreState,
            diagnostics: DiagnosticsSink = DiagnosticsSink.None,
        ): SecureCredentialStore = SecureCredentialStore(
            initialPreferences = preferences,
            files = files,
            serverMirror = serverMirror,
            diagnostics = diagnostics,
            initialState = state,
        )
    }
}
