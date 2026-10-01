package app.needler.core.data.security

import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import app.needler.core.domain.diagnostics.DiagnosticsLevel
import app.needler.core.domain.diagnostics.DiagnosticsSink
import app.needler.core.network.redactLogLine

/**
 * How [SecureCredentialStore] came to be holding the preferences it is holding.
 *
 * ## Why this is a modelled state rather than a boolean
 *
 * Opening `EncryptedSharedPreferences` can fail, and the three ways it can fail are not
 * interchangeable - which is the whole of the bug this type exists to make impossible. The previous
 * implementation caught `GeneralSecurityException` and `IOException`, deleted the credential file
 * and its backup, and reopened; a momentary failure around process death therefore destroyed a
 * working session, and because nothing in that path logged, the resulting sign-out was "cause
 * unknown" for the length of an audit. Reproduced twice on a device on 2026-10-01, once by
 * `force-stop` and once by `am kill` - the second being an ordinary process death, which is what
 * Android does on its own under memory pressure.
 *
 * REQUIREMENTS.md "Expiry, and why playback survives it" already settles the principle that was
 * being broken: "an expired session degrades the app to a pure music player rather than bricking
 * it", and re-onboarding is required "in exactly one case: both credentials are dead". A dead
 * credential degrades Needler; it does not erase it. An *unreadable* credential is not even a dead
 * one, and must not be treated as worse than one.
 *
 * So each outcome is named, and the two that cost the user something say so differently:
 * [Rebuilt] means the secrets are genuinely gone, [Locked] means they are still on disk and this
 * launch could not read them.
 */
public enum class CredentialStoreState {

    /** Opened first time, which is every launch that is working properly. */
    Opened,

    /**
     * Opened on the second attempt.
     *
     * Worth its own value only because it is worth a diagnostics line: it is the evidence that the
     * retry added by this change is doing something, and the evidence that would have identified
     * the original fault in an afternoon rather than an audit.
     */
    OpenedOnRetry,

    /**
     * The master key was permanently invalidated, so the file was discarded and a fresh store was
     * built in its place. The secrets are gone and the user has to sign in again.
     *
     * The only state in which anything is deleted, and it is reached only from
     * [KeyPermanentlyInvalidatedException] or from a write the user has already asked for - see
     * [nextStep].
     */
    Rebuilt,

    /**
     * The store could not be opened and **nothing was deleted**. The secrets are still on disk,
     * encrypted under a key this launch could not use.
     *
     * The app cannot read them, so it behaves as though the user were signed out, but it says so in
     * those words and it does not throw the file away: the next launch may well open it, and until
     * the user signs in again there is nothing to gain by destroying the only copy.
     */
    Locked,
    ;

    /** True when a write can reach the disk. A [Locked] store heals itself before writing. */
    public val canPersist: Boolean get() = this != Locked

    /**
     * True when the saved session is unavailable to this launch, whether it is gone ([Rebuilt]) or
     * merely unreadable ([Locked]).
     *
     * This is what the Connect screen branches on. It gets one message either way - the user cannot
     * act on the difference - but it must not be the blank first-run form, because a user who sees
     * onboarding assumes they were never signed in.
     */
    public val savedSessionUnavailable: Boolean get() = this == Rebuilt || this == Locked
}

/**
 * The encrypted file, as the unlock sequence needs to see it.
 *
 * Two methods, and both are side effects on the filesystem and the Android Keystore, which is
 * exactly why they are behind an interface: [nextStep] and [unlock] are then decidable on the JVM,
 * and the test that would have caught the original bug - "which exception means delete" - needs no
 * device. `SecureCredentialStoreKeystoreTest` covers the real implementation on hardware, and says
 * in its own KDoc that it cannot provoke this path honestly.
 *
 * Declared `internal`: nothing outside this package has any business opening the credential file,
 * and the public surface is [CredentialStoreState] plus the store itself.
 */
internal interface CredentialStoreFiles {

    /**
     * Opens the encrypted file, creating the master key and the keysets if they are absent.
     *
     * Throws when the key cannot be used or the file cannot be read. The caller classifies the
     * failure; this does not.
     */
    fun open(): SharedPreferences

    /**
     * Discards the master key and **both** the credential file and its `.bak`, so that the next
     * [open] builds a fresh store.
     *
     * ## Why the backup goes too, when the whole point was to keep it
     *
     * Because `.bak` is not a recovery path the app can choose to take. `SharedPreferencesImpl`
     * promotes it itself: on the first load of a file it deletes the primary and renames the backup
     * over it, which is how an interrupted write is recovered, and it happens before anything in
     * this module sees a byte. So by the time [open] has failed, the backup has already been used
     * if it existed - and deleting it, as the previous implementation did, took the copy the *next*
     * launch would have restored. That is the loss, and the fix for it is the [Locked] path, which
     * deletes nothing at all.
     *
     * A rebuild is the opposite situation. The key is provably gone, or the user is in the middle
     * of typing a replacement credential, so every byte of ciphertext on disk is worthless. Leaving
     * the `.bak` behind would be actively harmful: the platform would promote it over the freshly
     * built store on the next launch, the new keysets would vanish under the old undecryptable
     * ones, and the user would be signed out again on every single launch.
     *
     * Must not throw. A failure to delete leaves [open] to fail again and the store [Locked], which
     * is the safe direction.
     */
    fun rebuild()
}

/**
 * One attempt in the unlock sequence.
 *
 * Ordered by how much it costs to be wrong: [Open] and [Reopen] cost a file read, [Rebuild] costs
 * the user their saved session, and [Fail] costs nothing because it changes nothing.
 */
internal enum class UnlockStep {

    /** The first attempt, which is the only one that happens on a healthy launch. */
    Open,

    /** The retry. */
    Reopen,

    /** Discard the key and the files, then open a fresh store. */
    Rebuild,

    /** Give up. Nothing has been deleted and the secrets are still on disk. */
    Fail,
}

/**
 * The step to take after [previous] has failed. The whole of the decision, in one pure function.
 *
 * ## The rule, and the four things it gets right that the old catch did not
 *
 * 1. **Every failure is retried once.** `EncryptedSharedPreferences.create` is known to fail
 *    transiently under concurrent access and around process death, and a retry costs one file read.
 *    Even a failure that looks permanent is retried, because misreading a transient fault as a
 *    permanent one is the expensive mistake and this is the cheap guard against it.
 * 2. **A read failure is not proof of corruption.** `IOException` used to lead straight to the
 *    delete; it now leads to [Fail], which deletes nothing.
 * 3. **Only a permanently invalidated key means the data is unrecoverable**, which is the one thing
 *    [isPermanentKeyLoss] answers. A plain `GeneralSecurityException` does not: a keyset that
 *    cannot be unwrapped right now may unwrap on the next launch, and if it never does, the user
 *    will have signed in again by then and [mayRebuild] will be true.
 * 4. **Rebuilding is otherwise the user's decision, not this function's.** [mayRebuild] is true only
 *    on the heal-before-write path, where the user has already typed a replacement credential. The
 *    data being discarded is then data they have asked to replace, which is the difference between
 *    a consented loss and the silent one this change exists to remove.
 *
 * The alternative considered and rejected was a longer escalation: discard the primary file, try
 * again, and only then discard the backup. It cannot work. The platform has already promoted the
 * backup over the primary by this point, and the process holds a cached `SharedPreferencesImpl` for
 * that file name whose in-memory contents a file deletion does not touch, so the extra attempt
 * would read exactly what the previous one read while adding a state in which half the store had
 * been destroyed.
 *
 * @param previous the step that just failed.
 * @param permanentKeyLoss whether [isPermanentKeyLoss] recognised the failure as the master key
 *   being gone for good - a device migration, a restore onto different hardware, or a lock-screen
 *   change that invalidated keys.
 * @param mayRebuild whether the caller is allowed to discard the saved session. False on the launch
 *   path; true only when the user is writing a replacement credential.
 */
internal fun nextStep(
    previous: UnlockStep,
    permanentKeyLoss: Boolean,
    mayRebuild: Boolean,
): UnlockStep = when (previous) {
    UnlockStep.Open -> UnlockStep.Reopen
    UnlockStep.Reopen -> if (permanentKeyLoss || mayRebuild) UnlockStep.Rebuild else UnlockStep.Fail
    UnlockStep.Rebuild -> UnlockStep.Fail
    UnlockStep.Fail -> UnlockStep.Fail
}

/** The state a store is in when [step] is the attempt that succeeded. */
internal fun stateAfter(step: UnlockStep): CredentialStoreState = when (step) {
    UnlockStep.Open -> CredentialStoreState.Opened
    UnlockStep.Reopen -> CredentialStoreState.OpenedOnRetry
    UnlockStep.Rebuild -> CredentialStoreState.Rebuilt
    UnlockStep.Fail -> CredentialStoreState.Locked
}

/**
 * The exception chain, outermost first, with a cap and a cycle guard.
 *
 * Tink wraps, and `EncryptedSharedPreferences.create` wraps what Tink throws, so the one exception
 * type that actually decides anything is never the one caught. A classifier that looked only at the
 * top of the chain would treat a permanent key loss as an ordinary security exception, which is the
 * side of this decision that is still expensive to get wrong.
 *
 * `Throwable.cause` can be self-referential - a `fillInStackTrace` of its own cause is legal, and
 * some wrappers do it - so the walk is bounded both by [MAX_CAUSE_DEPTH] and by identity.
 */
internal fun causeChain(error: Throwable?): List<Throwable> {
    val chain: MutableList<Throwable> = ArrayList(4)
    var current: Throwable? = error
    while (current != null && chain.size < MAX_CAUSE_DEPTH) {
        if (chain.any { it === current }) break
        chain.add(current)
        current = current.cause
    }
    return chain
}

/**
 * True when the failure means the Keystore master key is gone for good, so the ciphertext on disk
 * can never be decrypted by anything.
 *
 * ## Why this matches on a class name
 *
 * [KeyPermanentlyInvalidatedException] is an Android framework class, and `:core:data`'s unit tests
 * run on the JVM against the mockable `android.jar`, where every framework member is a stub. An
 * `is` check against it could therefore never be exercised by the test that matters - the one
 * asserting which exception means delete - and an untested branch is exactly how the original
 * defect survived. Matching on the fully qualified name over the cause chain is as precise (the
 * class is a framework leaf; nothing in this app subclasses it) and is decidable in a unit test
 * with a stand-in, which is what [permanentTypes] is for.
 *
 * The names in [PERMANENT_KEY_LOSS_TYPES] are taken from the class references themselves rather
 * than typed out, so deleting or renaming one is a compile error here rather than a branch that
 * quietly stops firing.
 *
 * `UserNotAuthenticatedException` is deliberately **absent**. It is the other
 * `InvalidKeyException` the Keystore throws, it means the key needs the device unlocked rather than
 * that it is gone, and the master key this app builds carries no authentication requirement - so if
 * it ever appears it is a transient condition and must not cost anyone their credentials.
 *
 * @param permanentTypes the class names that mean permanent loss. A parameter solely so the JVM
 *   test can drive the chain walk without the framework class.
 */
internal fun isPermanentKeyLoss(
    error: Throwable?,
    permanentTypes: Set<String> = PERMANENT_KEY_LOSS_TYPES,
): Boolean = causeChain(error).any { permanentTypes.contains(it.javaClass.name) }

/**
 * One diagnostics line for a failed attempt: which step, which exception types, what the message
 * said.
 *
 * ## Why the message is included, and why that is safe
 *
 * REQUIREMENTS.md "Security" rule 1 - "Credentials in Keystore-backed storage, never in logs,
 * analytics or crash reports" - is the reason this file is the only one in the module that logs at
 * all, and the reason it logs nothing it has not thought about. The exception types are the useful
 * part and carry nothing. The message is included because "cannot read keyset" and "Signature/MAC
 * verification failed" and "Key permanently invalidated" are three different bugs and the type
 * alone does not separate them - and because nothing is being decrypted at the point this fails:
 * `EncryptedSharedPreferences.create` unwraps the two Tink keysets, so no stored value has been
 * touched yet.
 *
 * It is nonetheless run through [redactLogLine] before it goes anywhere. `SessionDiagnosticsLog`
 * redacts again on ingest and would make that unnecessary for the in-app log, but this line also
 * reaches logcat, which is a system buffer; a credential that was never put into the string cannot
 * be read out of either destination.
 */
internal fun describeFailure(step: UnlockStep, error: Throwable): String = buildString {
    append("credential store: ")
    append(
        when (step) {
            UnlockStep.Open -> "could not be opened"
            UnlockStep.Reopen -> "could not be opened on retry"
            UnlockStep.Rebuild -> "could not be opened after a rebuild"
            UnlockStep.Fail -> "could not be opened"
        },
    )
    append(" - ")
    append(causeChain(error).joinToString(separator = " <- ") { it.javaClass.name })
    val message: String? = causeChain(error).firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
    if (message != null) {
        append(" - ")
        append(redactLogLine(message))
    }
}

/** The outcome of [unlock]: what to read through, and how it was obtained. */
internal class UnlockResult(
    val preferences: SharedPreferences,
    val state: CredentialStoreState,
)

/**
 * Opens the credential file, retrying once and reporting every failure before anything is deleted.
 *
 * Item one of the fix order, and the one everything else depended on: "log the exception before
 * deleting anything - everything else about this bug is unknowable until that line exists". Every
 * failed attempt writes a line, including the attempts that then succeed, so a diagnostics log
 * shared from a device now answers the question the audit could not.
 *
 * On [CredentialStoreState.Locked] the returned preferences are [EmptySharedPreferences]: the store
 * then reads nothing and writes nothing, which is honest about what this launch can see. It is not
 * an in-memory stand-in that accepts writes, because a store that silently accepted a replacement
 * credential and lost it on the next launch would be the same silent data loss wearing a different
 * hat.
 *
 * @param mayRebuild true only on the heal-before-write path. See [nextStep].
 * @param permanentTypes handed to [isPermanentKeyLoss]. A parameter for the same reason it is one
 *   there: the framework exception that decides this is a stub on the JVM, and the sequence that
 *   acts on it has to be assertable without a device.
 */
internal fun unlock(
    files: CredentialStoreFiles,
    diagnostics: DiagnosticsSink,
    mayRebuild: Boolean = false,
    permanentTypes: Set<String> = PERMANENT_KEY_LOSS_TYPES,
): UnlockResult {
    var step: UnlockStep = UnlockStep.Open
    while (step != UnlockStep.Fail) {
        if (step == UnlockStep.Rebuild) {
            diagnostics.record(
                DiagnosticsLevel.Error,
                "credential store: discarding the master key and the saved secrets - " +
                    if (mayRebuild) {
                        "a replacement credential is being written"
                    } else {
                        "the Keystore key is permanently invalidated"
                    },
            )
            files.rebuild()
        }
        val opened: SharedPreferences? = openOrReport(files, diagnostics, step) { failure ->
            step = nextStep(
                previous = step,
                permanentKeyLoss = isPermanentKeyLoss(failure, permanentTypes),
                mayRebuild = mayRebuild,
            )
        }
        if (opened != null) {
            val state: CredentialStoreState = stateAfter(step)
            if (state != CredentialStoreState.Opened) {
                diagnostics.record(
                    if (state == CredentialStoreState.Rebuilt) {
                        DiagnosticsLevel.Error
                    } else {
                        DiagnosticsLevel.Warn
                    },
                    "credential store: opened as " + state.name,
                )
            }
            return UnlockResult(preferences = opened, state = state)
        }
    }
    diagnostics.record(
        DiagnosticsLevel.Error,
        "credential store: locked - the saved secrets are still on disk and nothing was deleted. " +
            "The user is asked to sign in again; the stored server address is kept.",
    )
    return UnlockResult(preferences = EmptySharedPreferences, state = CredentialStoreState.Locked)
}

/**
 * One attempt, with the failure reported and handed to [onFailure]. Null means it failed.
 *
 * ## Why this catches more than the two exceptions the contract names
 *
 * `EncryptedSharedPreferences.create` documents `GeneralSecurityException` and `IOException`, and
 * the previous implementation caught exactly those. Anything else - an `IllegalStateException` out
 * of Tink, a `NullPointerException` from a half-written keyset - therefore crashed the app on every
 * launch with no way out but reinstalling, which is strictly worse than being signed out. So every
 * exception is treated as "could not open", classified the same way, and reported; `Error` is
 * rethrown, because an `OutOfMemoryError` is not a keystore problem and pretending otherwise would
 * discard a session over it.
 */
private inline fun openOrReport(
    files: CredentialStoreFiles,
    diagnostics: DiagnosticsSink,
    step: UnlockStep,
    onFailure: (Throwable) -> Unit,
): SharedPreferences? {
    val outcome: Result<SharedPreferences> = runCatching { files.open() }
    val failure: Throwable = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
    if (failure is Error) throw failure
    diagnostics.record(DiagnosticsLevel.Warn, describeFailure(step, failure))
    onFailure(failure)
    return null
}

/**
 * The exception types that mean the Keystore master key can never decrypt anything again.
 *
 * Built from the class reference rather than written out, so the name cannot drift.
 */
internal val PERMANENT_KEY_LOSS_TYPES: Set<String> =
    setOf(KeyPermanentlyInvalidatedException::class.java.name)

/** Four links is more chain than any of these failures has ever produced. */
private const val MAX_CAUSE_DEPTH: Int = 8

/**
 * A `SharedPreferences` that holds nothing and keeps nothing.
 *
 * What a [CredentialStoreState.Locked] store reads through. Every getter answers the caller's own
 * default and every write reports failure, which is the truthful answer: this launch can neither
 * see the saved secrets nor replace them, and `SecureCredentialStore`'s setters already return
 * whether the value reached the disk because the app-password is shown exactly once and a lost
 * write has to abort the flow.
 *
 * The listener registrations are no-ops rather than throwing. Nothing in this app registers one,
 * and a diagnostics-time crash from a store that is already in its failure state would be the worst
 * possible moment for one.
 */
internal object EmptySharedPreferences : SharedPreferences {

    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()

    override fun getString(key: String?, defValue: String?): String? = defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        defValues

    override fun getInt(key: String?, defValue: Int): Int = defValue

    override fun getLong(key: String?, defValue: Long): Long = defValue

    override fun getFloat(key: String?, defValue: Float): Float = defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

    override fun contains(key: String?): Boolean = false

    override fun edit(): SharedPreferences.Editor = RefusingEditor

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ): Unit = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ): Unit = Unit

    override fun toString(): String = "EmptySharedPreferences"

    /** Accepts every edit and commits none of them, so the caller learns the write did not land. */
    private object RefusingEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = this
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = false
        override fun apply(): Unit = Unit
    }
}
