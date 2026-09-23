package app.needler.feature.pulls.common

import app.needler.core.domain.model.NeedlerError

/**
 * What to say to the user about a [NeedlerError] raised by cancelling or
 * retrying a pull.
 *
 * Every message names the next move, because each of these errors has a
 * different one and they are not interchangeable:
 *
 *  * a stale `/api/v1` session stops pulls but leaves the library and playback
 *    working, so the message says so rather than implying the app is broken;
 *  * offline is a queue, not a loss — REQUIREMENTS.md, "Offline is a
 *    first-class state, not an error";
 *  * a task the server has forgotten cannot be retried, and saying "404" would
 *    tell the user nothing about what to do next;
 *  * a refusal the server explained is repeated verbatim, because the server
 *    knows why and this module does not.
 *
 * ## Why the refusals need naming at all
 *
 * REQUIREMENTS.md, "Placing a request": "Cancel and retry of a *request* refuse
 * in-band; cancel and retry of a *download* do not." A refused **request**
 * cancel comes back as `200` with `success=false` and a reason, which
 * `:core:data` turns into [NeedlerError.Rejected] carrying that reason; a
 * refused **download** cancel comes back as a real `400`, `403` or `404`. Two
 * adjacent pairs of endpoints with opposite conventions land in the same list
 * on the same screen, so the messages below have to cover both shapes.
 *
 * `diagnostic` is deliberately never shown for a modelled error. It names
 * status codes and internal state and is written for the diagnostics log; the
 * only place it surfaces is the unmodelled fallback, where something specific
 * beats "something went wrong".
 */
internal fun problemMessage(error: NeedlerError): String = when (error) {
    NeedlerError.SessionExpired ->
        "Your sign-in has expired, so pulls cannot be changed until you sign in again. Your " +
            "library and downloaded music keep playing."

    is NeedlerError.AppPasswordRevoked ->
        "This device's access to the library was revoked on the server. Sign in again from " +
            "Settings."

    is NeedlerError.Offline ->
        "No connection. This is queued and sent as soon as you are back online."

    is NeedlerError.RateLimited -> {
        val seconds: Long? = error.retryAfter?.inWholeSeconds?.coerceAtLeast(1L)
        if (seconds == null) {
            "The server is rate limiting requests. Try again in a moment."
        } else {
            "The server is rate limiting requests. Try again in " + seconds + "s."
        }
    }

    // 404 on a download task. `retryDownload` mints a *new* task id and the old
    // one stops existing, so this is what a stale row looks like rather than a
    // bug: the next poll will drop it.
    is NeedlerError.NotFound ->
        "The server no longer has that task. It has either finished or been cleared already."

    is NeedlerError.PermissionDenied ->
        "That pull belongs to another account on this server, so it cannot be changed from here."

    // 400 on a download task, or the in-band refusal on a request. Either way
    // the server gave a reason and it is better than anything invented here.
    is NeedlerError.Rejected ->
        error.message ?: "The server refused that. It may have moved on since this screen loaded."

    is NeedlerError.ServerError ->
        "The server answered " + error.statusCode + ". Try again in a moment."

    NeedlerError.Cancelled -> "Cancelled."

    else -> error.diagnostic
}
