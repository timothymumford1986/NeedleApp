package app.needler.player.service.session

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs [block] on this scope and hands Media3 a `ListenableFuture` for the result.
 *
 * Media3's session callbacks are future-shaped and everything behind them here is a suspend function, so
 * something has to bridge the two. `kotlinx-coroutines-guava` does exactly this and is deliberately not added
 * as a dependency: it is one more artifact on the version catalogue for twenty lines, in a project that pins
 * every version by hand and says why.
 *
 * Cancellation travels both ways. Media3 cancels these futures routinely - a browser that disconnects mid
 * request, a controller that goes away - and a coroutine still running after its future was cancelled is a
 * Room query whose result nobody will ever read.
 */
internal fun <T> CoroutineScope.mediaFuture(block: suspend () -> T): ListenableFuture<T> {
    val future: SettableFuture<T> = SettableFuture.create()
    val job: Job = launch {
        try {
            future.set(block())
        } catch (cancelled: CancellationException) {
            future.cancel(false)
            throw cancelled
        } catch (failure: Throwable) {
            // Reported through the future rather than rethrown: a failed browse request must not take the
            // session's scope down with it.
            future.setException(failure)
        }
    }
    future.addListener(
        { if (future.isCancelled) job.cancel() },
        MoreExecutors.directExecutor(),
    )
    return future
}

/**
 * Awaits a `ListenableFuture` without adding `kotlinx-coroutines-guava` for the one call site that needs it.
 *
 * Cancelling the coroutine cancels the future, which matters for the one future this is used on:
 * `MediaController.Builder.buildAsync()` binds to the service, and an abandoned connection attempt that is
 * never cancelled leaves a service binding behind.
 */
internal suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (failure: java.util.concurrent.CancellationException) {
                continuation.cancel(failure)
            } catch (failure: java.util.concurrent.ExecutionException) {
                continuation.resumeWithException(failure.cause ?: failure)
            } catch (failure: Throwable) {
                continuation.resumeWithException(failure)
            }
        },
        MoreExecutors.directExecutor(),
    )
    continuation.invokeOnCancellation { cancel(false) }
}
