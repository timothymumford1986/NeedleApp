package app.needler.core.domain.usecase

import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.PlaybackPreferences
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.StreamQualityPreference
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PlaybackSettingsRepository
import app.needler.core.domain.repository.SessionRepository
import kotlinx.coroutines.flow.first

/**
 * Decides where one track's bytes come from: the device, the network, or nowhere.
 *
 * This is the single place that applies the cache-staleness rule at playback time, and the single place
 * that chooses between original bytes and a transcode. Both decisions are easy to get subtly wrong and
 * both fail silently when they are:
 *
 * - **Staleness.** DroppedNeedle replaces audio files in place on a quality upgrade. Cached bytes whose
 *   `file_id`, size, duration or format no longer match the server's are the older, worse copy, so they
 *   are discarded here rather than played. This is also why the cache is keyed on [TrackKey] and never
 *   on `file_id`.
 * - **Transcoding.** The server allows one transcode per user and two in total, so a transcode is only
 *   requested when the user asked for it, the connection is metered, *and* the server actually offers
 *   it. A transcoded stream is never written to the audio cache: caching a 320 kbps rendering of a FLAC
 *   would quietly downgrade the offline copy of that track for ever.
 */
public class ResolvePlayableSourceUseCase(
    private val libraryRepository: LibraryRepository,
    private val pinRepository: PinRepository,
    private val playbackSettingsRepository: PlaybackSettingsRepository,
    private val sessionRepository: SessionRepository,
) {

    public suspend operator fun invoke(key: TrackKey): PlayableSource {
        val track: Track = libraryRepository.getTrack(key)
            ?: return PlayableSource.Unavailable(key, NeedlerError.NotFound("track " + key.canonicalString))

        val cached: CachedAudio? = pinRepository.getCachedAudio(key)
        var staleBytesDiscarded = false

        if (cached != null) {
            if (cached.isStaleFor(track.fetch)) {
                // The server upgraded this file. Drop the bytes; the pinned case is re-downloaded by
                // the pin repository, but this play must not use the old copy.
                pinRepository.evictCachedAudio(key, CacheEvictionReason.STALE_AFTER_QUALITY_UPGRADE)
                staleBytesDiscarded = true
            } else if (cached.isComplete) {
                return PlayableSource.Cached(
                    key = key,
                    filePath = cached.filePath,
                    sizeBytes = cached.sizeOnDiskBytes,
                    pinned = cached.pinned,
                )
            }
        }

        val connectivity: ConnectivityState = sessionRepository.currentConnectivity()
        if (!connectivity.isOnline) {
            // Expected, not an error: offline playback of on-device music is a first-class feature.
            return PlayableSource.UnavailableOffline(key)
        }

        val session: SessionState = sessionRepository.currentSession()
        if (!session.canUseLibraryLane) {
            return PlayableSource.Unavailable(key, libraryLaneBlocker(session))
        }

        val preferences: PlaybackPreferences = playbackSettingsRepository.observePlaybackPreferences().first()
        val capabilities: ServerCapabilities? = sessionRepository.currentCapabilities()
        val format: StreamFormat = resolveStreamFormat(
            preference = preferences.streamQuality,
            connectivity = connectivity,
            capabilities = capabilities,
        )

        return PlayableSource.Stream(
            key = key,
            fetchHandle = track.fetch,
            format = format,
            cacheWhileStreaming = format == StreamFormat.Original,
            staleBytesDiscarded = staleBytesDiscarded,
        )
    }

    /**
     * Why the Subsonic lane is unusable.
     *
     * Note the asymmetry with the catalogue lane: a *stale companion bearer* does not appear here at
     * all, because the app-password is independent and playback must keep working when the bearer
     * expires.
     */
    private fun libraryLaneBlocker(session: SessionState): NeedlerError = when (session) {
        is SessionState.ReonboardingRequired -> NeedlerError.AppPasswordRevoked()
        is SessionState.SubsonicDisabled -> NeedlerError.SubsonicProtocolDisabled
        SessionState.NotConfigured -> NeedlerError.CapabilityUnavailable("no server configured")
        is SessionState.PlayerOnly -> NeedlerError.Unexpected("library lane refused in player-only mode")
        is SessionState.Authenticated -> NeedlerError.Unexpected("library lane refused while authenticated")
    }

    public companion object {
        /**
         * Chooses the stream format.
         *
         * Original unless all three conditions hold: the user chose MP3 320 on mobile data, the
         * connection is metered, and the server reports transcoding available. Pure and public so the
         * rule can be tested directly.
         */
        public fun resolveStreamFormat(
            preference: StreamQualityPreference,
            connectivity: ConnectivityState,
            capabilities: ServerCapabilities?,
        ): StreamFormat {
            if (preference != StreamQualityPreference.MP3_320_ON_METERED) return StreamFormat.Original
            if (!connectivity.isMetered) return StreamFormat.Original
            if (capabilities == null || !capabilities.transcodingAvailable) return StreamFormat.Original
            return StreamFormat.Transcoded.Mp3_320
        }
    }
}
