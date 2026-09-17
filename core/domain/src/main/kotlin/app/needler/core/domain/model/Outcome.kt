package app.needler.core.domain.model

/**
 * The result of an operation that can fail with a modelled [NeedlerError].
 *
 * Used for every suspending write and every network-touching read in this module. Read paths that come
 * from the local mirror return plain `Flow`s instead: the mirror is always available, so there is
 * nothing to fail.
 *
 * Kotlin's own `Result` is deliberately not used - it carries a `Throwable`, and the UI has to
 * distinguish a stale `/api/v1` session from a revoked app-password from a rate limit, which means the
 * failure has to be a modelled value rather than an exception.
 */
public sealed interface Outcome<out T> {

    public data class Success<out T>(val value: T) : Outcome<T>

    public data class Failure(val error: NeedlerError) : Outcome<Nothing>

    public companion object {
        /** A successful result with no payload. */
        public val Ok: Outcome<Unit> = Success(Unit)

        public fun <T> success(value: T): Outcome<T> = Success(value)

        public fun failure(error: NeedlerError): Outcome<Nothing> = Failure(error)
    }
}

public val Outcome<*>.isSuccess: Boolean get() = this is Outcome.Success

public val Outcome<*>.isFailure: Boolean get() = this is Outcome.Failure

/** The value, or null when this is a failure. */
public fun <T> Outcome<T>.getOrNull(): T? = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> null
}

/** The error, or null when this is a success. */
public fun Outcome<*>.errorOrNull(): NeedlerError? = when (this) {
    is Outcome.Success -> null
    is Outcome.Failure -> error
}

/** The value, or [fallback] when this is a failure. */
public fun <T> Outcome<T>.getOrDefault(fallback: T): T = when (this) {
    is Outcome.Success -> value
    is Outcome.Failure -> fallback
}

public inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

public inline fun <T, R> Outcome<T>.flatMap(transform: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> transform(value)
    is Outcome.Failure -> this
}

public inline fun <T, R> Outcome<T>.fold(
    onSuccess: (T) -> R,
    onFailure: (NeedlerError) -> R,
): R = when (this) {
    is Outcome.Success -> onSuccess(value)
    is Outcome.Failure -> onFailure(error)
}

public inline fun <T> Outcome<T>.onSuccess(action: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) action(value)
    return this
}

public inline fun <T> Outcome<T>.onFailure(action: (NeedlerError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) action(error)
    return this
}

/** Wraps a value as a success. */
public fun <T> T.asOutcome(): Outcome<T> = Outcome.Success(this)

/** Wraps an error as a failure. */
public fun NeedlerError.asFailure(): Outcome<Nothing> = Outcome.Failure(this)
