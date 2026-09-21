package app.needler.player.service.source

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.OfflineCause
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey

/**
 * What the Media3 `DataSource` chain must actually do for one track, derived from the
 * [PlayableSource] the resolver produced.
 *
 * The split exists so the decision is a value that can be asserted on, rather than a shape the data
 * source factory takes on the loading thread where nothing can see it. Three cases, and nothing
 * else: bytes on disk, bytes over HTTP, or no bytes at all.
 */
public sealed interface SourcePlan {

    public val key: TrackKey

    /**
     * Read the local file. No network, no cache write, and the buffered position equals the duration
     * because the bytes are all already there.
     */
    public data class LocalFile(
        override val key: TrackKey,
        val filePath: String,
        val sizeBytes: Long,
        val pinned: Boolean,
    ) : SourcePlan

    /**
     * Fetch over HTTP with `Range`, optionally writing the bytes into the audio store on the way
     * past.
     *
     * [writeThrough] is the write-through decision and nothing more: it is true only when the
     * resolver said these bytes may be retained. It is never re-derived from the format here, because
     * the resolver is the single place that decides it - see
     * [PlayableSource.Stream.cacheWhileStreaming].
     */
    public data class HttpStream(
        override val key: TrackKey,
        val fetchHandle: TrackFetchHandle,
        val format: StreamFormat,
        val writeThrough: Boolean,
    ) : SourcePlan {
        /** True when the server is being asked to transcode, which is what a `429` falls back from. */
        public val isTranscode: Boolean get() = format != StreamFormat.Original
    }

    /**
     * Nothing to read. Carried as a plan rather than an exception so the reason survives all the way
     * to [PlaybackState.error] on every surface sharing the session.
     */
    public data class NotPlayable(
        override val key: TrackKey,
        val error: NeedlerError,
    ) : SourcePlan
}

/**
 * Turns a resolved [PlayableSource] into a [SourcePlan].
 *
 * It re-decides nothing. Staleness, the cached-versus-stream choice and the transcode choice were all
 * made by `ResolvePlayableSourceUseCase`; duplicating any of them here is how two places end up
 * disagreeing about whether a file on disk is the current copy.
 */
public object SourcePlanner {

    public fun plan(source: PlayableSource): SourcePlan = when (source) {
        is PlayableSource.Cached -> SourcePlan.LocalFile(
            key = source.key,
            filePath = source.filePath,
            sizeBytes = source.sizeBytes,
            pinned = source.pinned,
        )

        is PlayableSource.Stream -> SourcePlan.HttpStream(
            key = source.key,
            fetchHandle = source.fetchHandle,
            format = source.format,
            writeThrough = source.cacheWhileStreaming,
        )

        // Offline with nothing on the device is an expected state, not a fault, so it is modelled as
        // an Offline error rather than as something that reads like a bug in the player.
        is PlayableSource.UnavailableOffline -> SourcePlan.NotPlayable(
            key = source.key,
            error = NeedlerError.Offline(OfflineCause.NO_NETWORK),
        )

        is PlayableSource.Unavailable -> SourcePlan.NotPlayable(source.key, source.error)
    }

    /**
     * Whether a cache write may be opened for this read.
     *
     * A write-through handle appends from byte zero, so a read that starts anywhere else cannot
     * produce a complete file. Seeking into an un-cached track therefore streams without retaining -
     * keeping the tail of a track as though it were the track is the same silent failure as keeping a
     * truncated one.
     */
    public fun mayWriteThrough(plan: SourcePlan, readPosition: Long): Boolean =
        plan is SourcePlan.HttpStream && plan.writeThrough && readPosition == 0L
}
