package app.needler.player.service.source

import app.needler.core.domain.model.NeedlerError
import java.io.IOException

/**
 * A playback failure with the domain reason still attached.
 *
 * Media3 wraps whatever a `DataSource` throws into a `PlaybackException` with one of its own error codes,
 * and those codes cannot say "the app-password was revoked" or "the server's stream slots are gone". So the
 * reason travels as the cause, and the controller unwraps it on the way back out to
 * `PlaybackState.error` - which is where every surface sharing the session reads it.
 *
 * An `IOException` specifically, because that is the only kind of failure Media3's loader treats as
 * retryable rather than fatal, and most of these are retryable.
 */
public class NeedlerPlaybackException(
    public val needlerError: NeedlerError,
    cause: Throwable? = null,
) : IOException(needlerError.diagnostic, cause) {

    public companion object {

        /**
         * Digs the domain error out of a throwable chain, or null when there is none.
         *
         * Walks the causes because the loader nests: `PlaybackException` around a
         * `Loader.UnexpectedLoaderException` around what the data source actually threw.
         */
        public fun errorIn(throwable: Throwable?): NeedlerError? {
            var current: Throwable? = throwable
            var guard = 0
            while (current != null && guard < MAX_CAUSE_DEPTH) {
                if (current is NeedlerPlaybackException) return current.needlerError
                current = current.cause
                guard++
            }
            return null
        }

        private const val MAX_CAUSE_DEPTH: Int = 16
    }
}
