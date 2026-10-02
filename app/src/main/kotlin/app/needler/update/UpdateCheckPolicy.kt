package app.needler.update

/**
 * When to ask GitHub, and whether to say anything about the answer.
 *
 * Pure arithmetic over `Long`s, with no Android, no network and no clock of its own — the current
 * time is a parameter. That is the whole point: these are the rules that decide how often a
 * background HTTP request leaves the device and how often a listener is interrupted, and rules like
 * that should be readable and testable without a device in the room.
 *
 * ## The cadence, and why it is measured from the last *answer*
 *
 * One check a day. An APK-distributed app has no store to poll for it and no push channel —
 * REQUIREMENTS.md "Notifications" notes the same constraint for pull state: "Local, driven by
 * periodic background polling. Best-effort delivery; no server push exists" — but a release is not
 * a pull. Nobody is waiting on it. Checking more often would spend the listener's battery and
 * mobile data on a question whose answer changes a handful of times a year.
 *
 * The 24 hours run from the last check that *answered*, not the last attempt. A device that was in
 * flight mode at 09:00 did not learn anything, and making it wait until 09:00 tomorrow to try again
 * would turn one bad moment into a lost day. Attempts are throttled separately and briefly, in
 * memory, by [OFFLINE_RETRY_MILLIS] — enough that a rotation, a tab switch or twenty trips back to
 * the library cannot produce twenty requests, and short enough that reconnecting to Wi-Fi is
 * noticed within the same sitting.
 *
 * ## Clocks move backwards
 *
 * Every comparison here tolerates it. `System.currentTimeMillis` is wall-clock: it jumps when the
 * network time arrives on first boot, when a listener corrects their timezone, and when they set
 * the date by hand. A stored timestamp in the future, or a backoff floor a year away, is treated as
 * "check now" rather than "never check again", because the failure mode of being slightly too eager
 * is one extra HTTP request and the failure mode of the alternative is an app that silently stops
 * updating forever.
 *
 * ## None of these numbers is a requirement
 *
 * REQUIREMENTS.md names this feature exactly once, to say it is one of two surfaces that "exist in
 * the code and are specified nowhere in this document", listed "because neither has had its
 * contract decided". The only fixed decision it carries is the distribution row — "Signed APK on
 * GitHub releases" — which is what makes an updater necessary at all and says nothing about how it
 * should behave. So every number below is a judgement call made in this file, and none of them is a
 * rule being followed. They are all open to a decision, and the two that would most repay one are
 * [CHECK_INTERVAL_MILLIS], which decides how much of someone's battery and data goes on a question
 * they did not ask, and [INSTALL_HANDOVER_TIMEOUT_MILLIS], which decides how long someone can be
 * stuck looking at an install that is not happening.
 */
object UpdateCheckPolicy {

    /**
     * How often the ambient check talks to GitHub.
     *
     * Once a day. Unchanged, and deliberately so: there is nothing in REQUIREMENTS.md to check this
     * against, and a cadence invented twice is worse than a cadence invented once. Wants a
     * decision; does not want another guess.
     */
    const val CHECK_INTERVAL_MILLIS: Long = 24L * 60L * 60L * 1_000L

    /** How long a failed attempt suppresses the next one. In memory only; see [UpdatePreferences]. */
    const val OFFLINE_RETRY_MILLIS: Long = 15L * 60L * 1_000L

    /**
     * How long the app will wait for the platform to say what happened to a committed install
     * before concluding that nothing is going to.
     *
     * This exists because [UpdateState.Installing] is not a terminal state and must not be allowed
     * to behave like one. An install either replaces this process — in which case nothing needs
     * clearing, because the state died with it — or it did not happen. There is no third outcome in
     * which sitting on "Installing 1.2.3" with no action and no dismissal is the correct rendering,
     * and before this constant existed that was the only outcome the app could not leave.
     *
     * The hole is in the platform contract, not in this app's bookkeeping. `PackageInstaller`
     * reports the *outcome of a commit*, and a confirmation dialogue that is never answered has no
     * outcome: dismissed with Home, dismissed with Back, swiped out of the recents list, or
     * replaced by Play Protect's own sheet and dismissed there. The session stays open, the app is
     * never told, and `STATUS_FAILURE_ABORTED` — which [ApkInstaller] does handle, and folds back
     * to a plain offer — is simply never sent.
     *
     * Three minutes rather than thirty seconds, because this is the backstop and not the mechanism:
     * [HANDOVER_POLL_MILLIS] against [ApkInstaller.committedSessionIsGone] recovers the ordinary
     * case in seconds, so the only job left here is to be unmistakably longer than a real
     * hand-over. A system install dialogue plus a Play Protect scan is seconds, and the dialogue is
     * modal over the app, so a listener cannot be deliberating over it *and* looking at the banner
     * this governs. Being early costs an Update button appearing under a live install, which is
     * harmless — the install still proceeds and still replaces the process. Being late costs the
     * listener the only route the app has to its own successor.
     */
    const val INSTALL_HANDOVER_TIMEOUT_MILLIS: Long = 3L * 60L * 1_000L

    /**
     * How often, while waiting, to ask whether the session still exists.
     *
     * A timeout is a guess; a vanished session is evidence. Five seconds is short enough that the
     * listener who pressed "Don't install" on Play Protect sees the banner go back to offering the
     * update while they are still looking at it, and long enough that the whole wait costs at most
     * a few dozen binder calls — bounded by [INSTALL_HANDOVER_TIMEOUT_MILLIS], and only ever while
     * a banner is composed and an install is actually pending.
     */
    const val HANDOVER_POLL_MILLIS: Long = 5L * 1_000L

    /**
     * A backoff floor further away than this is not believed.
     *
     * It can only come from arithmetic against a wrong local clock — `X-RateLimit-Reset` is an
     * absolute epoch, so a device a month behind computes a month-long wait from a perfectly
     * correct header. Capping it at the ordinary cadence means the worst a bad clock can do is
     * delay a check by a day.
     */
    const val MAX_TRUSTED_BACKOFF_MILLIS: Long = CHECK_INTERVAL_MILLIS

    /**
     * Should a check go out now?
     *
     * @param now wall-clock milliseconds.
     * @param lastCheckAtMillis when a check last answered, `0` if never.
     * @param retryNotBeforeMillis a floor set by a rate-limited response, `0` if none.
     * @param lastAttemptAtMillis when an attempt was last made in this process, `0` if none. Not
     *   persisted, so a fresh process always gets one attempt however recently the last one failed.
     */
    fun isCheckDue(
        now: Long,
        lastCheckAtMillis: Long,
        retryNotBeforeMillis: Long,
        lastAttemptAtMillis: Long,
    ): Boolean {
        val backoffIsCredible = retryNotBeforeMillis - now <= MAX_TRUSTED_BACKOFF_MILLIS
        if (backoffIsCredible && now < retryNotBeforeMillis) return false

        if (lastAttemptAtMillis > 0L) {
            val sinceAttempt = now - lastAttemptAtMillis
            if (sinceAttempt in 0L until OFFLINE_RETRY_MILLIS) return false
        }

        if (lastCheckAtMillis <= 0L) return true
        // A stored time in the future means the clock moved. Check, rather than wait it out.
        if (lastCheckAtMillis > now) return true
        return now - lastCheckAtMillis >= CHECK_INTERVAL_MILLIS
    }

    /**
     * Has an install hand-over been unanswered for long enough to be treated as abandoned?
     *
     * @param now wall-clock milliseconds.
     * @param handedOverAtMillis when the app last gave an APK to the platform, `0` if never — in
     *   which case there is no hand-over to be stale and this is `false`.
     *
     * A timestamp in the *future* counts as stale, which is the opposite of the lenient reading
     * every other comparison in this object takes, and for the same underlying reason. Elsewhere a
     * clock that moved should not stop the app checking for updates; here a clock that moved must
     * not strand the listener on a bar that cannot be left. In both cases the answer is whichever
     * side of the comparison leads back to a working update path.
     */
    fun isHandoverStale(now: Long, handedOverAtMillis: Long): Boolean {
        if (handedOverAtMillis <= 0L) return false
        if (handedOverAtMillis > now) return true
        return now - handedOverAtMillis >= INSTALL_HANDOVER_TIMEOUT_MILLIS
    }

    /**
     * Should this release be put in front of the listener?
     *
     * Two conditions, and both are strict for a reason.
     *
     * `candidateVersionCode > installedVersionCode` — strictly greater, never "different". Android
     * refuses to install a lower `versionCode` over a higher one, so offering a downgrade would
     * produce a banner whose only possible outcome is a failed install. Equal is not an update
     * either; it is the release already running.
     *
     * `candidateVersionCode > dismissedVersionCode` — so dismissing 1.2.3 hides 1.2.3 and anything
     * that somehow sorts below it, and 1.3.0 still gets to speak. A dismissal is "not this one",
     * not "never again", and the listener never has to find a setting to undo it.
     */
    fun shouldOffer(
        candidateVersionCode: Long,
        installedVersionCode: Long,
        dismissedVersionCode: Long,
    ): Boolean = candidateVersionCode > installedVersionCode &&
        candidateVersionCode > dismissedVersionCode
}
