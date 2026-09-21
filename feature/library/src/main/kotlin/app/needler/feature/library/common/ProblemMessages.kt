package app.needler.feature.library.common

import app.needler.core.domain.model.NeedlerError

/**
 * What to say to the user about a [NeedlerError] raised by an action on one of
 * these screens.
 *
 * Every message names the next move, because the errors this module can
 * actually produce all have one, and they are not interchangeable:
 *
 *  * a stale session stops pulls but leaves playback working, so the message
 *    says so rather than implying the app is broken;
 *  * a download the administrator has forbidden cannot be retried at all;
 *  * a rate limit is a wait, not a failure;
 *  * offline is a queue, not a loss.
 *
 * `diagnostic` is deliberately never shown for a modelled error. It is written
 * for the diagnostics log and it names status codes and internal state; the
 * only place it surfaces is the unmodelled fallback, where something specific
 * beats "something went wrong".
 */
internal fun problemMessage(error: NeedlerError): String = when (error) {
    NeedlerError.SessionExpired ->
        "Your sign-in has expired, so pulls and search are unavailable until you sign in " +
            "again. Your library and downloaded music keep playing."

    is NeedlerError.AppPasswordRevoked ->
        "This device's access to the library was revoked on the server. Sign in again from " +
            "Settings."

    NeedlerError.SubsonicProtocolDisabled ->
        "The Subsonic interface is switched off on the server. An administrator has to turn " +
            "on \"Enable Subsonic API\" under Settings, Connect Apps."

    is NeedlerError.RateLimited -> {
        val seconds: Long? = error.retryAfter?.inWholeSeconds?.coerceAtLeast(1L)
        if (seconds == null) {
            "The server is rate limiting requests. Try again in a moment."
        } else {
            "The server is rate limiting requests. Try again in " + seconds + "s."
        }
    }

    NeedlerError.StreamSlotsExhausted ->
        "The server has no free streaming slots right now. Try again in a moment."

    NeedlerError.DownloadForbidden ->
        "Downloading to a device is turned off for your account on this server."

    is NeedlerError.PermissionDenied ->
        "Your account is not allowed to do that on this server."

    is NeedlerError.Offline ->
        "No connection. This is queued and sent as soon as you are back online."

    is NeedlerError.ServerError ->
        "The server answered " + error.statusCode + ". Try again in a moment."

    is NeedlerError.NotFound ->
        "The server no longer has that."

    is NeedlerError.Rejected ->
        error.message ?: "The server refused that request."

    is NeedlerError.InsufficientStorage ->
        "There is not enough free space on this device for that download."

    NeedlerError.Cancelled -> "Cancelled."

    else -> error.diagnostic
}
