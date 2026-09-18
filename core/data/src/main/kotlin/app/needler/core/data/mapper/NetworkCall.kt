package app.needler.core.data.mapper

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import kotlinx.coroutines.CancellationException

/**
 * Runs one network call and folds whatever it throws onto [NeedlerError].
 *
 * Every repository write goes through here rather than catching `NetworkError` by hand, so a new
 * failure shape cannot reach a screen as an exception. Cancellation is re-thrown: a cancelled
 * coroutine is not a modelled failure and swallowing it breaks structured concurrency.
 */
public suspend inline fun <T> networkCall(
    downloadContext: Boolean = false,
    transcodeRequest: Boolean = false,
    crossinline block: suspend () -> T,
): Outcome<T> = try {
    Outcome.Success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    Outcome.Failure(
        if (error is app.needler.core.network.NetworkError) {
            ErrorMapper.toNeedlerError(error, downloadContext, transcodeRequest)
        } else {
            ErrorMapper.toNeedlerError(error)
        },
    )
}
