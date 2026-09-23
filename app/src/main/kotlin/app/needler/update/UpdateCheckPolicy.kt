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
 */
object UpdateCheckPolicy {

    /** REQUIREMENTS.md has no rule for this; once a day is the judgement call, and it is here. */
    const val CHECK_INTERVAL_MILLIS: Long = 24L * 60L * 60L * 1_000L

    /** How long a failed attempt suppresses the next one. In memory only; see [UpdatePreferences]. */
    const val OFFLINE_RETRY_MILLIS: Long = 15L * 60L * 1_000L

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
