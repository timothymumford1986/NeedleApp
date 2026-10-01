package app.needler.core.domain.usecase

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.ConnectivityState
import app.needler.core.domain.model.NetworkStatus
import app.needler.core.domain.model.ServerCapabilities
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.StreamRung
import app.needler.core.domain.model.StreamRungs
import app.needler.core.domain.usecase.ResolvePlayableSourceUseCase.Companion.requestedRung
import app.needler.core.domain.usecase.ResolvePlayableSourceUseCase.Companion.resolveStreamFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stream-quality rule: which rung is asked for, and what the source lets it be.
 *
 * Two halves, tested in that order. **Step 5** picks a rung - a per-track override, else a per-album
 * override, else the mode default for this connection. **Steps 6 and 7** cap that rung against what
 * the source actually is, because a rung is a ceiling and not a request: you cannot transcode upward,
 * and a re-encode that saves nothing costs a scarce server slot and forfeits the cached copy for
 * nothing.
 *
 * The capping half is the older of the two and the one most likely to be "simplified" later. It was
 * missing entirely for a while, and on an all-MP3-320 library that made the mobile-data setting
 * actively harmful: a 320 kbps re-encode of a 320 kbps file, on the connection the setting exists to
 * protect, with the bytes then thrown away because REQUIREMENTS.md "Why transcoded bytes are never
 * cached" forbids retaining them. Widening one rung into seven made that rule matter more, not less.
 *
 * ## What is not here
 *
 * Cases about *local* bytes - a downloaded album beating any setting, a partial cache staying
 * unavailable offline, stale bytes being discarded on a server-side upgrade - are decided in
 * `ResolvePlayableSourceUseCase.invoke` before any rung is chosen, and are unchanged by this work.
 * The `429` fallback is not here either: it is a transport rule and lives in
 * `player/service`'s `StreamRetryPolicy`, which already tests it.
 */
class StreamFormatResolutionTest {

    // ---- step 5: which rung was asked for ----------------------------------------------------

    @Test
    fun `a metered connection uses the data rung`() {
        assertEquals(
            StreamRung.MP3_192,
            requestedRung(
                rungs = StreamRungs(wifi = StreamRung.ORIGINAL, data = StreamRung.MP3_192),
                connectivity = METERED,
            ),
        )
    }

    @Test
    fun `an unmetered connection uses the wifi rung`() {
        assertEquals(
            StreamRung.ORIGINAL,
            requestedRung(
                rungs = StreamRungs(wifi = StreamRung.ORIGINAL, data = StreamRung.MP3_192),
                connectivity = UNMETERED,
            ),
        )
    }

    @Test
    fun `a track override beats the album override and both beat the mode`() {
        assertEquals(
            StreamRung.ORIGINAL,
            requestedRung(
                rungs = StreamRungs(wifi = StreamRung.MP3_128, data = StreamRung.MP3_128),
                connectivity = METERED,
                trackOverride = StreamRung.ORIGINAL,
                albumOverride = StreamRung.OPUS_96,
            ),
        )
    }

    @Test
    fun `an album override beats the mode when the track has none`() {
        assertEquals(
            StreamRung.OPUS_96,
            requestedRung(
                rungs = StreamRungs(wifi = StreamRung.ORIGINAL, data = StreamRung.ORIGINAL),
                connectivity = UNMETERED,
                albumOverride = StreamRung.OPUS_96,
            ),
        )
    }

    @Test
    fun `the default rungs are original on wifi and mp3 320 on data`() {
        // The pair the retired "Stream MP3 320 on mobile data" toggle already stored, which is what
        // makes widening it a change of controls rather than a migration of values.
        assertEquals(StreamRung.ORIGINAL, StreamRungs.Default.wifi)
        assertEquals(StreamRung.MP3_320, StreamRungs.Default.data)
    }

    // ---- the fifteen cases of the plan's table -----------------------------------------------
    // Local and offline cases (3, 4, 5, 6, 13) belong to `invoke`; case 8's fallback belongs to the
    // player service. The ten that are this function's are here, in the table's order.

    @Test
    fun `case 1 - original on wifi from a flac source streams the flac`() {
        assertEquals(StreamFormat.Original, resolve(connectivity = UNMETERED, source = FLAC))
    }

    @Test
    fun `case 2 - mp3 192 on mobile from a flac source transcodes`() {
        // And so the bytes are not kept: a transcode is exactly what REQUIREMENTS.md "Why transcoded
        // bytes are never cached" refuses to retain, which is the cliff the Settings screen has to name.
        assertEquals(
            StreamFormat.Transcoded(codec = "mp3", maxBitrateKbps = 192),
            resolve(data = StreamRung.MP3_192, source = FLAC),
        )
    }

    @Test
    fun `case 7 - mp3 320 from an mp3 320 source streams original rather than re-encoding itself`() {
        // Original, so the bytes are kept: the same rung on a library already at it is no cliff at all.
        assertEquals(
            StreamFormat.Original,
            resolve(data = StreamRung.MP3_320, source = AudioQuality(AudioFormat.MP3, 320)),
        )
    }

    @Test
    fun `case 9 - a server without transcoding streams original whatever the rung`() {
        // No ffmpeg means nothing is ever re-encoded, so no rung has a cliff on such a server.
        assertEquals(
            StreamFormat.Original,
            resolve(data = StreamRung.MP3_192, capabilities = null, source = FLAC),
        )
        assertEquals(
            StreamFormat.Original,
            resolve(data = StreamRung.MP3_192, capabilities = NO_FFMPEG, source = FLAC),
        )
    }

    @Test
    fun `case 10 - original on wifi from a lossy source streams that lossy source`() {
        assertEquals(
            StreamFormat.Original,
            resolve(connectivity = UNMETERED, source = AudioQuality(AudioFormat.MP3, 270)),
        )
    }

    @Test
    fun `case 11 - opus 128 from an mp3 320 source transcodes to opus`() {
        assertEquals(
            StreamFormat.Transcoded(codec = "opus", maxBitrateKbps = 128),
            resolve(data = StreamRung.OPUS_128, source = AudioQuality(AudioFormat.MP3, 320)),
        )
    }

    @Test
    fun `case 12 - mp3 320 from an opus 128 source streams original`() {
        // Bitrate alone decides, across codecs included: 128 is not above 320, so there is nothing to
        // save. That it would also sound worse is true and is not the reason.
        assertEquals(
            StreamFormat.Original,
            resolve(data = StreamRung.MP3_320, source = AudioQuality(AudioFormat.OPUS, 128)),
        )
    }

    @Test
    fun `case 14 - an original override on a track beats an mp3 128 data rung`() {
        assertEquals(
            StreamFormat.Original,
            resolve(
                data = StreamRung.MP3_128,
                source = FLAC,
                trackOverride = StreamRung.ORIGINAL,
            ),
        )
    }

    @Test
    fun `case 15 - an mp3 128 override on an album transcodes on wifi`() {
        // The mirror image of case 14, and the half that proves an override is absolute rather than
        // "metered only": the Wi-Fi rung is Original and the album still transcodes.
        assertEquals(
            StreamFormat.Transcoded(codec = "mp3", maxBitrateKbps = 128),
            resolve(
                connectivity = UNMETERED,
                source = FLAC,
                albumOverride = StreamRung.MP3_128,
            ),
        )
    }

    // ---- the cap, rung by rung ---------------------------------------------------------------

    @Test
    fun `lossless transcodes on every rung below original`() {
        // A FLAC is several times the size of any of these, which is the case the setting exists for.
        StreamRung.entries.filter { it.isTranscode }.forEach { rung ->
            assertEquals(
                "rung " + rung,
                rung.transcode,
                resolve(data = rung, source = FLAC),
            )
        }
    }

    @Test
    fun `a lossy source above the rung transcodes and at or below it does not`() {
        assertEquals(
            StreamFormat.Transcoded.Mp3_320,
            resolve(data = StreamRung.MP3_320, source = AudioQuality(AudioFormat.MP3, 512)),
        )
        assertEquals(
            StreamFormat.Original,
            resolve(data = StreamRung.MP3_320, source = AudioQuality(AudioFormat.MP3, 256)),
        )
    }

    @Test
    fun `the original rung never transcodes, however large the source`() {
        assertEquals(
            StreamFormat.Original,
            resolve(data = StreamRung.ORIGINAL, source = AudioQuality(AudioFormat.FLAC, 1_000)),
        )
    }

    // ---- unknown is not evidence -------------------------------------------------------------
    // REQUIREMENTS.md "Invalidating upgraded files": "A field counts as changed only when both sides
    // carry a value. A null on either side means unknown, never changed." The asymmetry is
    // deliberate - transcoding on a guess burns a scarce slot and permanently forfeits the cached
    // copy, while declining to costs one track's worth of mobile data, once, and keeps the bytes.

    @Test
    fun `an unknown format streams original`() {
        assertEquals(
            StreamFormat.Original,
            resolve(data = StreamRung.MP3_128, source = AudioQuality(AudioFormat.UNKNOWN, 999)),
        )
    }

    @Test
    fun `a null format streams original`() {
        assertEquals(
            StreamFormat.Original,
            resolve(data = StreamRung.MP3_128, source = AudioQuality.Unknown),
        )
    }

    @Test
    fun `a lossy source with no bitrate streams original`() {
        assertEquals(
            StreamFormat.Original,
            resolve(data = StreamRung.MP3_128, source = AudioQuality(AudioFormat.MP3, bitrateKbps = null)),
        )
    }

    @Test
    fun `a lossless source with no bitrate still transcodes`() {
        // Losslessness is itself the evidence: a FLAC is several times a 320 kbps MP3 whatever its
        // exact bitrate, so the missing number changes nothing about the decision.
        assertEquals(
            StreamFormat.Transcoded.Mp3_320,
            resolve(
                data = StreamRung.MP3_320,
                source = AudioQuality(AudioFormat.FLAC, bitrateKbps = null),
            ),
        )
    }

    private fun resolve(
        wifi: StreamRung = StreamRung.ORIGINAL,
        data: StreamRung = StreamRung.MP3_320,
        connectivity: ConnectivityState = METERED,
        capabilities: ServerCapabilities? = TRANSCODING_SERVER,
        source: AudioQuality,
        trackOverride: StreamRung? = null,
        albumOverride: StreamRung? = null,
    ): StreamFormat = resolveStreamFormat(
        rungs = StreamRungs(wifi = wifi, data = data),
        connectivity = connectivity,
        capabilities = capabilities,
        source = source,
        trackOverride = trackOverride,
        albumOverride = albumOverride,
    )

    private companion object {
        val METERED: ConnectivityState = ConnectivityState(NetworkStatus.METERED)
        val UNMETERED: ConnectivityState = ConnectivityState(NetworkStatus.UNMETERED)

        val FLAC: AudioQuality = AudioQuality(AudioFormat.FLAC, bitrateKbps = 1_000)

        val TRANSCODING_SERVER: ServerCapabilities = ServerCapabilities(
            subsonicEnabled = true,
            transcodingAvailable = true,
            libraryDownloadAllowed = true,
        )

        /** A server with no ffmpeg: every rung resolves to original bytes. */
        val NO_FFMPEG: ServerCapabilities = TRANSCODING_SERVER.copy(transcodingAvailable = false)
    }
}
