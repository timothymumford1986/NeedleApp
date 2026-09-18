package app.needler.core.network

/**
 * Everything this module needs to authenticate a request, and nothing else.
 *
 * Declared here — not in `:core:data` or `:core:domain` — so `:core:network` stays a leaf:
 * it depends on no other project module. `:core:data` implements this over
 * `EncryptedSharedPreferences` under a Keystore master key (REQUIREMENTS.md §Authentication,
 * point 4) and Hilt binds the implementation.
 *
 * Contract for implementers:
 *  * Reads are called on background dispatchers from OkHttp interceptors. They must be cheap
 *    (an in-memory cache over the encrypted store) and must never block on network I/O.
 *  * Returning `null` means "not provisioned yet". Interceptors then send the request without
 *    that credential and the server answers `401` / Subsonic code 44, which maps to
 *    [NetworkError.Unauthorised].
 *  * Values returned here are secrets. This module never logs them: see
 *    [app.needler.core.network.RedactingLogInterceptor]. Implementations must not log them either.
 */
public interface CredentialProvider {

    /** The saved server, or `null` when onboarding has not completed. */
    public fun serverUrl(): ServerUrl?

    /**
     * The 30-day companion bearer minted by `POST /api/v1/auth/device-sessions`, used for the
     * whole `/api/v1` lane. `null` once the session has been marked stale and cleared.
     */
    public fun bearerToken(): String?

    /**
     * The app-password secret from `POST /api/v1/connect-apps/app-passwords`, sent as the
     * `apiKey` query parameter on the Subsonic lane. Never expires; only revocation kills it.
     */
    public fun appPassword(): String?

    /**
     * Called by the module when the `/api/v1` lane answers `401`, so the data layer can mark the
     * companion session stale and prompt for re-authentication while playback keeps working
     * (REQUIREMENTS.md §"Expiry, and why playback survives it").
     *
     * Must be non-blocking and safe to call repeatedly from any thread.
     */
    public fun onBearerRejected() {}

    /**
     * Called when the Subsonic lane answers error code 40 or 44: the app-password has been revoked.
     *
     * **This is not a request to re-onboard.** As long as [bearerToken] is still alive the data
     * layer mints a replacement app-password with it - the minting endpoint needs only the bearer -
     * and playback resumes with nothing shown to the user. Only when both credentials are dead does
     * anyone have to sign in again. Implementations must therefore keep the bearer here and discard
     * the app-password alone; clearing both would destroy the very thing the repair runs on and turn
     * one revoked secret, often revoked from the web UI by mistake, into a lost session and a lost
     * cache.
     *
     * Must be non-blocking and safe to call repeatedly from any thread: several Subsonic requests
     * are usually in flight when the first one is refused, and each of them reports it. The repair
     * itself is single-flight in the data layer, not here.
     */
    public fun onAppPasswordRejected() {}
}
