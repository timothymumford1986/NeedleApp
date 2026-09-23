package app.needler.update

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * The three numbers the update check has to remember between launches.
 *
 * An interface rather than a concrete class purely so the policy above it can be tested without a
 * device: [UpdateCheckPolicy] is pure arithmetic, but [UpdateRepository] is the thing that decides
 * *when* to run it, and that decision is only interesting when its memory can be faked.
 *
 * ## None of this is a secret
 *
 * REQUIREMENTS.md "Security" reserves `EncryptedSharedPreferences` under a Keystore master key for
 * the companion bearer and the app-password, and `SecureCredentialStore` is the only class allowed
 * to hold either. A last-check timestamp is not in that category — it says nothing about the
 * listener, their server or their library, and encrypting it would put a third writer into the one
 * file whose integrity the whole session depends on. Plain [SharedPreferences] is the right storage
 * for a plain fact.
 *
 * ## Why not `AppStateStore`, and why not DataStore
 *
 * `:core:data`'s `AppStateStore` is the app's settings surface and is not this package's to change.
 * More to the point, these values are not settings: nobody sets them, nobody sees them, and they
 * are meaningless outside this package. They live in their own small file, named after the feature,
 * so removing the update checker one day is deleting a directory and a `<uses-permission>` rather
 * than unpicking three keys from a shared store.
 *
 * DataStore was considered and rejected for the same reason. It is the right tool for settings that
 * are observed — a `Flow` the UI collects — and these are read exactly once per check and written
 * at most once a day. A `Flow`, a `CoroutineScope` and a serialiser would all be ceremony around
 * what is genuinely three `Long`s behind an `if`.
 */
interface UpdatePreferences {

    /**
     * When a check last *completed with an answer*, in `System.currentTimeMillis` terms.
     *
     * Deliberately not "when a check was last attempted". A check that failed because the device
     * was on a train did not answer the question, and recording it would hide the next real
     * opportunity behind a 24-hour wall. Attempts are throttled in memory instead — see
     * [UpdateCheckPolicy].
     */
    fun lastCheckAtMillis(): Long

    fun recordCheckedAt(millis: Long)

    /**
     * An explicit floor on the next attempt, set only when GitHub asked us to wait.
     *
     * Persisted, unlike the in-memory attempt throttle, because the unauthenticated rate limit is
     * per IP address and lasts up to an hour: restarting the app does not restore the allowance,
     * so a process restart must not be a way to keep hammering a limiter that has already said no.
     */
    fun retryNotBeforeMillis(): Long

    fun recordRetryNotBefore(millis: Long)

    /**
     * The newest `versionCode` the listener has waved away.
     *
     * Dismissal is per version, not forever: saying "not now" to 1.2.3 must not mean never hearing
     * about 1.3.0. Storing the number rather than a boolean is what makes that work, and it is why
     * the comparison is `<=` — an update at or below what was dismissed has already been declined.
     */
    fun dismissedVersionCode(): Long

    fun recordDismissed(versionCode: Long)
}

/**
 * [UpdatePreferences] over a small private [SharedPreferences] file of this package's own.
 *
 * Writes use `apply()`, not `commit()`. `SecureCredentialStore` uses `commit()` because the
 * app-password is issued once and a failed write is unrecoverable; nothing here is remotely in that
 * class. The worst case for a lost write is one extra HTTP request tomorrow, or a banner reappearing
 * once, and neither is worth a synchronous disk write on whatever thread the check happens to be on.
 */
class AndroidUpdatePreferences @Inject constructor(
    @ApplicationContext context: Context,
) : UpdatePreferences {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun lastCheckAtMillis(): Long = preferences.getLong(KEY_LAST_CHECK_AT, 0L)

    override fun recordCheckedAt(millis: Long) {
        preferences.edit().putLong(KEY_LAST_CHECK_AT, millis).apply()
    }

    override fun retryNotBeforeMillis(): Long = preferences.getLong(KEY_RETRY_NOT_BEFORE, 0L)

    override fun recordRetryNotBefore(millis: Long) {
        preferences.edit().putLong(KEY_RETRY_NOT_BEFORE, millis).apply()
    }

    override fun dismissedVersionCode(): Long = preferences.getLong(KEY_DISMISSED_VERSION, 0L)

    override fun recordDismissed(versionCode: Long) {
        preferences.edit().putLong(KEY_DISMISSED_VERSION, versionCode).apply()
    }

    private companion object {
        /**
         * Named after the feature, not the app. `android:allowBackup="false"` in the manifest keeps
         * it off cloud backups along with everything else, which is incidental here — there is
         * nothing in it worth restoring.
         */
        const val FILE_NAME = "needler_update_check"

        const val KEY_LAST_CHECK_AT = "last_check_at_millis"
        const val KEY_RETRY_NOT_BEFORE = "retry_not_before_millis"
        const val KEY_DISMISSED_VERSION = "dismissed_version_code"
    }
}
