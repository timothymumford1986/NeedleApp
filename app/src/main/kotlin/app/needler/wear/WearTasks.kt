package app.needler.wear

import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * `await()` for a Google Play services [Task], without adding a dependency for it.
 *
 * The idiomatic version is `kotlinx-coroutines-play-services`, which is one artifact and about the
 * same number of lines as what follows. It is not used, for the reason `wear/.../GmsTasks.kt` records
 * in full: adding it means a new entry in `gradle/libs.versions.toml`, and a version catalogue whose
 * header records the day and the repository every number was read from is not a file to add a guessed
 * version to. This file is the phone's copy of that one, duplicated for the same reason
 * [WearPlaybackProtocol] is - `:app` and `:wear` are two APKs with no module between them - and it
 * deletes cleanly if the project later takes the real artifact.
 *
 * Three completions, all of which happen in practice:
 *
 *  * **Failure.** Every data-layer call fails when Google Play services is absent or out of date, or
 *    when no watch has ever been paired. The exception is resumed, not swallowed; callers decide what
 *    a failure means, and here it always means "the watch does not get this update", never "crash the
 *    music player".
 *  * **Cancellation.** A `Task` can be cancelled underneath us, which is not a failure.
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
 * Every data-layer call on this side is wrapped in this rather than in `runCatching`, and the
 * difference is the whole point. `runCatching` catches `Throwable`, which includes the
 * `CancellationException` a coroutine throws on its way out. Swallowing that turns "this publishing
 * window was cancelled" into "this call returned null" and lets a collector carry on observing the
 * Media3 session inside a scope that has already been cancelled - which here means a phone that goes
 * on publishing to a watch nobody is looking at, the exact cost the window exists to bound.
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
