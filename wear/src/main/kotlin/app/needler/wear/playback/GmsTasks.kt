package app.needler.wear.playback

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * `await()` for a Google Play services [Task], without adding a dependency for it.
 *
 * The idiomatic version of this is `kotlinx-coroutines-play-services`, which is one artifact and
 * about the same number of lines as what follows. It is not used, on purpose: adding it means a new
 * entry in `gradle/libs.versions.toml`, and a version catalogue whose header records the day and the
 * repository every number was read from is not a file to add a guessed version to. Twelve lines here
 * cost less than a wrong number there. If the project later wants the real thing, the two additions
 * are recorded in this module's handover notes and this file deletes cleanly.
 *
 * Three completions, all of which happen in practice:
 *
 *  * **Failure.** Every data-layer call fails when Google Play services is absent, out of date, or
 *    the app is not signed with the key the paired phone app was signed with. The exception is
 *    resumed, not swallowed; callers decide what a failure means, and in this module it always means
 *    "carry on and show the user a truthful state" rather than "crash on a watch face".
 *  * **Cancellation.** A `Task` can be cancelled underneath us, which is not a failure and must not
 *    become one.
 *  * **Success.** `Task<Void>` resolves with null, which is why the result is passed through
 *    unchecked rather than asserted non-null.
 *
 * The listener fires on the main looper, and fires immediately if the task has already completed, so
 * this neither leaks nor deadlocks when called from a coroutine on any dispatcher.
 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { completed ->
        val failure: Exception? = completed.exception
        when {
            failure != null -> continuation.resumeWithException(failure)
            completed.isCanceled -> continuation.cancel()
            else -> continuation.resume(completed.result)
        }
    }
}

/**
 * Runs [block], answering null if it fails - but never swallowing a cancellation.
 *
 * Every data-layer call in this module is wrapped in this rather than in `runCatching`, and the
 * difference is the whole point. `runCatching` catches `Throwable`, which includes the
 * `CancellationException` a coroutine throws on its way out. Swallowing that turns "this collection
 * was cancelled" into "this call returned null" and lets a coroutine carry on working inside a scope
 * that has already been cancelled - here, after the watch screen has gone away. It is the classic
 * coroutine bug and it is invisible until something leaks.
 *
 * Inline, so a suspending call sits happily inside the lambda.
 */
internal inline fun <T> orNullOnFailure(block: () -> T): T? = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    null
}
