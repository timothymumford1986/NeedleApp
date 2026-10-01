package app.needler.core.domain.usecase

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.CacheEvictionReason
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.PlaybackPreferences
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.StreamOverrideScope
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.model.StreamRungs
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
 *   requested when the rung in force is below what the source already is, *and* the server actually
 *   offers it. A transcoded stream is never written to the audio cache: caching a 320 kbps rendering of
 *   a FLAC would quietly downgrade the offline copy of that track for ever.
 *
 * ## The rung in force
 *
 * The quality decision is a small chain rather than a switch, and the order is the whole of it:
 * a per-track override, else a per-album override, else the mode default for this connection - the
 * Data rung while metered, the Wi-Fi rung otherwise - and then the source caps whatever that produced.
 * [Companion.requestedRung] is the first half, [Companion.streamFormatFor] the second, and both are
 * public and pure so the rule is testable without a repository.
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

        // Two point lookups on a table with one row per overridden item, and only on the streaming
        // path: a local copy has already returned above, so a downloaded album never pays for them.
        val trackOverride: StreamRung? = playbackSettingsRepository.getStreamOverride(
            scope = StreamOverrideScope.TRACK,
            id = key.canonicalString,
        )
        val albumOverride: StreamRung? = playbackSettingsRepository.getStreamOverride(
            scope = StreamOverrideScope.ALBUM,
            id = key.releaseGroupMbid.value,
        )

        val format: StreamFormat = resolveStreamFormat(
            rungs = preferences.streamRungs,
            connectivity = connectivity,
            capabilities = capabilities,
            source = track.quality,
            trackOverride = trackOverride,
            albumOverride = albumOverride,
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

        // The app-password is being replaced right now. This is the reason playback pauses for the
        // length of the repair and then resumes: the track is not unplayable, its credential is
        // simply a few hundred milliseconds away. Nothing here reaches the user.
        is SessionState.RepairingAppPassword -> NeedlerError.AppPasswordRevoked()
        is SessionState.SubsonicDisabled -> NeedlerError.SubsonicProtocolDisabled
        SessionState.NotConfigured -> NeedlerError.CapabilityUnavailable("no server configured")
        is SessionState.PlayerOnly -> NeedlerError.Unexpected("library lane refused in player-only mode")
        is SessionState.Authenticated -> NeedlerError.Unexpected("library lane refused while authenticated")
    }

    public companion object {

        /**
         * **Step 5 of the chain: which rung was asked for.** First match wins.
         *
         * ```
         * override(track) ?: override(album) ?: (metered ? Data rung : Wi-Fi rung)
         * ```
         *
         * ## Why the override beats the mode and not the other way round
         *
         * The mode defaults answer "what do I usually want on this connection"; an override answers
         * "this record is different". A general rule that overruled a specific one would make the
         * override useless in exactly the case it exists for - the one lossless record on a phone
         * whose data rung is MP3 128 - and there would be no way to express that at all.
         *
         * An override is **absolute**: one value, applied on Wi-Fi and on mobile data alike. See
         * [app.needler.core.domain.model.StreamOverride] for why it is not stored per network.
         *
         * Nothing here decides whether a transcode actually happens. An override of
         * [StreamRung.ORIGINAL] is a real, useful value - "never re-encode this record" - and a
         * rung at or below the source still resolves to [StreamFormat.Original] at step 6.
         *
         * Pure and public so the precedence can be tested without a repository.
         */
        public fun requestedRung(
            rungs: StreamRungs,
            connectivity: ConnectivityState,
            trackOverride: StreamRung? = null,
            albumOverride: StreamRung? = null,
        ): StreamRung = trackOverride ?: albumOverride ?: rungs.forConnectivity(connectivity)

        /**
         * The whole streaming decision: steps 5 to 7 of the chain, for one track.
         *
         * Steps 1 to 4 - in the library, local bytes, online, library lane usable - have already run
         * in [invoke] by the time this is reached, and this function never sees them. It is pure and
         * public precisely so REQUIREMENTS.md "Streaming" can be tested case by case against it
         * rather than through a UI.
         *
         * @param rungs the user's two mode defaults.
         * @param connectivity chooses between them. Metered gets [StreamRungs.data].
         * @param capabilities null, or a server that cannot transcode, forces
         *   [StreamFormat.Original] - REQUIREMENTS.md rule 3 of "Streaming".
         * @param source the track's own format and bitrate, from [Track.quality]. This is the
         *   parameter whose absence made the whole rule wrong.
         * @param trackOverride a rung pinned to this track, or null.
         * @param albumOverride a rung pinned to this track's album, or null.
         */
        public fun resolveStreamFormat(
            rungs: StreamRungs,
            connectivity: ConnectivityState,
            capabilities: ServerCapabilities?,
            source: AudioQuality,
            trackOverride: StreamRung? = null,
            albumOverride: StreamRung? = null,
        ): StreamFormat = streamFormatFor(
            requested = requestedRung(
                rungs = rungs,
                connectivity = connectivity,
                trackOverride = trackOverride,
                albumOverride = albumOverride,
            ),
            capabilities = capabilities,
            source = source,
        )

        /**
         * **Steps 6 and 7: the requested rung, capped by what the source actually is.**
         *
         * `effective = min(requested, source)`, and a rung that caps to the source streams the
         * original bytes. Original unless **three** conditions hold: the rung is a transcode rung,
         * the server reports transcoding available, and the source is genuinely bigger than the
         * transcode would be.
         *
         * Separate from [resolveStreamFormat] because two callers want exactly this and have no
         * connection to ask about: the Settings screen, which has to tell the user that a rung below
         * their library's quality never builds an offline library, and the album and player screens,
         * which draw what pressing play would fetch right now.
         *
         * ## Why the cap is not a request
         *
         * You cannot transcode upward - asking for FLAC from an MP3 270 source yields the MP3 - so
         * the rung is a ceiling and this is where it is enforced. The alternative, treating it as a
         * target, would have the app request re-encodings that cannot improve anything.
         *
         * ## The condition that was missing, and why it matters more now
         *
         * The server-and-user conditions are the ones REQUIREMENTS.md "Streaming" states outright,
         * and for a long time they were the whole rule. On a library that is already MP3 320 - which
         * is exactly what the reference library turned out to be - that rule asks the server to
         * re-encode a 320 kbps MP3 into a 320 kbps MP3 on every metered play. It is not a saving that
         * happens to be small: it is no saving at all, paid for three times over.
         *
         * - **It loses quality.** Lossy-to-lossy at the same nominal bitrate is a generational loss.
         * - **It destroys caching.** REQUIREMENTS.md "Why transcoded bytes are never cached" forbids
         *   retaining transcoded bytes, so every metered play of an already-compressed track becomes a
         *   fetch that can never be kept. The track is re-fetched for ever, on the connection the
         *   setting existed to protect.
         * - **It burns a scarce slot.** The server allows one transcode per user and two in total, so
         *   a pointless transcode can lock out a second listener or a Cast session.
         *
         * Note the ordering that makes this work: [StreamFormat.Original] is what restores retention,
         * because the caller sets `cacheWhileStreaming` from `format == Original`. Fixing the format
         * fixes the caching; there is no second switch to remember.
         *
         * ## Unknown is not evidence
         *
         * A source with no format or no bitrate resolves to [StreamFormat.Original], on the same
         * principle REQUIREMENTS.md "Invalidating upgraded files" applies to staleness: "A field counts
         * as changed only when both sides carry a value. A null on either side means unknown, never
         * changed."
         *
         * The asymmetry is deliberate. Transcoding on a guess costs a scarce server slot and
         * permanently forfeits the cached copy; declining to transcode on a guess costs one track's
         * worth of mobile data, once, and the bytes are kept. The cheap mistake is the one to make.
         *
         * Widening the ladder from one rung to seven made this rule matter more, not less: every new
         * rung is another way to ask for a re-encode that would send more bytes than it saves.
         *
         * @param requested the rung chosen at step 5, by [requestedRung].
         * @param capabilities null, or a server with no ffmpeg, forces [StreamFormat.Original].
         * @param source the track's own format and bitrate, from [Track.quality]. This is the
         *   parameter whose absence made the whole rule wrong.
         */
        public fun streamFormatFor(
            requested: StreamRung,
            capabilities: ServerCapabilities?,
            source: AudioQuality,
        ): StreamFormat {
            val target: StreamFormat.Transcoded = requested.transcode ?: return StreamFormat.Original
            if (capabilities == null || !capabilities.transcodingAvailable) return StreamFormat.Original
            return if (transcodeWouldSaveBytes(source, target)) target else StreamFormat.Original
        }

        /**
         * Whether re-encoding [source] to [target] would send fewer bytes than the original.
         *
         * Lossless always would, by a wide margin - a FLAC is several times the size of a 320 kbps
         * MP3, and that case is the entire reason the setting exists. A lossy source only would when
         * its bitrate is genuinely higher than the target; equal or lower means the transcode is at
         * best a copy and at worst a second generation of loss.
         *
         * **The comparison is on bitrate alone, across codecs included.** Opus 128 from an MP3 320
         * source sends fewer bytes, so it transcodes; MP3 320 from an Opus 128 source does not, so it
         * does not. That reads oddly beside the fact that Opus 128 sounds better than MP3 128, and it
         * is still right: this function answers "would this send less", not "would this sound
         * better". Modelling perceptual equivalence here would put an argument about codecs inside a
         * cache rule, with no way to test the answer.
         *
         * A format Needler does not model ([AudioFormat.UNKNOWN]) is treated as unknown rather than as
         * lossy, because guessing wrong in that direction is the expensive mistake - see the note on
         * evidence above.
         */
        private fun transcodeWouldSaveBytes(
            source: AudioQuality,
            target: StreamFormat.Transcoded,
        ): Boolean {
            val format: AudioFormat = source.format ?: return false
            if (format == AudioFormat.UNKNOWN) return false
            if (source.isLossless) return true

            val sourceBitrate: Int = source.bitrateKbps ?: return false
            return sourceBitrate > target.maxBitrateKbps
        }
    }
}
