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

/**
 * What to say when the **history** or **wanted** lane could not be read.
 *
 * A separate function from [problemMessage], not a parameter on it, because the two differ on the
 * one error they are both most likely to see. A cancel or a retry made offline is journalled and
 * replayed — REQUIREMENTS.md, "Write queue" — so [problemMessage] can honestly promise it will be
 * sent. **A read cannot be queued.** There is nothing to replay and nothing arrives later, and
 * telling a user their attempt to look at a list has been queued for sending would be a sentence
 * with no event behind it.
 *
 * The rest of the differences follow from these two lanes having no mirror. `PullRepository`'s own
 * KDoc explains why there is none: REQUIREMENTS.md "Local persistence" fixes the schema and "the
 * `pull` table it lists is the live queue … with no history sibling anywhere in it", so the lane
 * "reads through to the server and fails when there is no server, which is honest". Every message
 * below therefore says what is still true offline rather than implying the app has lost something —
 * the server keeps its own record and keeps looking either way.
 *
 * @param lane the subject of the sentence, "your request history" or "the wanted list", so the
 *   message names which list failed. Two lanes are drawn by the same code and a message that said
 *   only "that could not be loaded" would not say which tab it was about.
 */
internal fun laneProblemMessage(error: NeedlerError, lane: String): String = when (error) {
    // Not an error. The server's record is intact, the server is still working, and the one thing
    // that is missing is the connection to read it over.
    is NeedlerError.Offline ->
        "Offline, so " + lane + " cannot be read. It is kept on the server, not on this device, " +
            "and nothing has been lost — the server carries on whether or not this app can reach " +
            "it. Pulls already in progress are on the Pulls tab, which works offline."

    NeedlerError.SessionExpired ->
        "Your sign-in has expired, so " + lane + " cannot be read until you sign in again. Your " +
            "library and downloaded music keep playing."

    is NeedlerError.AppPasswordRevoked ->
        "This device's access to the library was revoked on the server. Sign in again from " +
            "Settings."

    // The interface's own default answer, raised as `CapabilityUnavailable`: an implementation that
    // serves the mirror and has no server to ask. Worth distinguishing from a server that answered
    // badly, because the next move is different and neither is the user's fault.
    is NeedlerError.CapabilityUnavailable ->
        "This build cannot read " + lane + " yet. Everything else on this screen still works."

    is NeedlerError.PermissionDenied ->
        "Your account on this server is not allowed to read " + lane + "."

    is NeedlerError.RateLimited -> {
        val seconds: Long? = error.retryAfter?.inWholeSeconds?.coerceAtLeast(1L)
        if (seconds == null) {
            "The server is rate limiting requests. Try again in a moment."
        } else {
            "The server is rate limiting requests. Try again in " + seconds + "s."
        }
    }

    // A read is not a write, so there is no in-band `success=false` to unpack here —
    // `DefaultPullRepository.requestHistory` says as much. A status is all this can be.
    is NeedlerError.ServerError ->
        "The server answered " + error.statusCode + " when asked for " + lane + ". Try again in a " +
            "moment."

    is NeedlerError.NotFound ->
        "This server has no " + lane + " endpoint. It may be older than this app expects."

    else -> error.diagnostic
}

/** The subject [laneProblemMessage] puts in its sentences for `GET /api/v1/requests/history`. */
internal const val HISTORY_LANE_SUBJECT: String = "your request history"

/** The subject [laneProblemMessage] puts in its sentences for `GET /api/v1/requests/wanted`. */
internal const val WANTED_LANE_SUBJECT: String = "the wanted list"
