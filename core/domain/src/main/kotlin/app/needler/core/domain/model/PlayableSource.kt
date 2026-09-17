package app.needler.core.domain.model

/**
 * Where the bytes for one track are going to come from.
 *
 * Resolved by `ResolvePlayableSourceUseCase`, which is the only place that decides between cached bytes
 * and the network. The player service consumes this rather than reasoning about the cache itself, so
 * the staleness rule is applied exactly once.
 */
public sealed interface PlayableSource {

    public val key: TrackKey

    /**
     * Complete bytes already on the device, at [filePath] in app-private internal storage. Plays with
     * no network at all, and must meet the "play from tap, cached, under 150 ms" budget.
     */
    public data class Cached(
        override val key: TrackKey,
        val filePath: String,
        val sizeBytes: Long,
        val pinned: Boolean,
    ) : PlayableSource

    /**
     * Fetch from the server.
     *
     * [fetchHandle] carries the current `file_id`; it is passed through rather than stored anywhere as
     * a key. [format] is already resolved against the user's preference, the connection's metered-ness
     * and the server's capabilities, so the caller must not re-decide it.
     *
     * All audio requests must be `Range`-capable: the server honours ranges, returns `416` correctly
     * and disables gzip on audio so seeking works.
     */
    public data class Stream(
        override val key: TrackKey,
        val fetchHandle: TrackFetchHandle,
        val format: StreamFormat,
        /** True when the streamed bytes may be written into the LRU audio cache as they arrive. */
        val cacheWhileStreaming: Boolean = true,
        /**
         * True when a stale cached copy was discarded during this resolution, because the server had
         * replaced the file. Useful for the diagnostics log and for explaining an unexpected re-fetch.
         */
        val staleBytesDiscarded: Boolean = false,
    ) : PlayableSource

    /**
     * Not on the device and the server is unreachable.
     *
     * This is an expected state, not an error: offline playback of on-device music is a first-class
     * feature, so the UI skips or greys the track rather than showing a failure dialog.
     */
    public data class UnavailableOffline(
        override val key: TrackKey,
    ) : PlayableSource

    /** Not playable for a modelled reason, e.g. the track is no longer in the library. */
    public data class Unavailable(
        override val key: TrackKey,
        val error: NeedlerError,
    ) : PlayableSource
}
