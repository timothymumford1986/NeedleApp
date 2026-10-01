package app.needler.wear

import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.TrackFetchHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's sync decisions, which is the half of this feature that can be wrong without anybody
 * noticing.
 *
 * Three things are asserted here and the first is by far the most important.
 *
 * **The staleness rule.** REQUIREMENTS.md "Invalidating upgraded files" states it twice, because getting
 * it wrong costs the user their library in two opposite ways. Too strict - treating an absent field as a
 * change - and "a server release that stopped reporting durations... would declare every cached track
 * stale and re-download the user's entire offline library". Over Bluetooth that is a day of transfers on
 * two batteries. Too lax - never noticing a real replacement - and the user keeps "the older, usually
 * lower-quality copy" on their wrist for ever, with nothing reporting it. Both failures are silent, which
 * is why every branch of [WearSyncFingerprint.differs] has a test.
 *
 * **What goes next.** A held track that is current is skipped; a held track whose bytes have moved is
 * not. That second one *is* the eviction, across two devices.
 *
 * **What is offered.** Only the Downloaded tier, and only the part of it that is really on the phone.
 */
class WearSyncPlanTest {

    // ---- the staleness rule ---------------------------------------------------------------------

    private fun handle(
        fileId: String = "4711",
        sizeBytes: Long? = 41_000_000L,
        durationMs: Long? = 254_000L,
        format: AudioFormat? = AudioFormat.FLAC,
        bitrateKbps: Int? = 1_010,
    ): TrackFetchHandle = TrackFetchHandle(
        fileId = FileId(fileId),
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        format = format,
        bitrateKbps = bitrateKbps,
    )

    @Test
    fun `the same handle is not stale`() {
        val token: String = WearSyncFingerprint.of(handle())
        assertFalse(WearSyncFingerprint.differs(token, token))
    }

    @Test
    fun `a changed file id is stale`() {
        // The one field compared directly. REQUIREMENTS.md: "a changed id is the server telling us
        // plainly that these are different bytes." This is the ordinary quality-upgrade signal.
        val held: String = WearSyncFingerprint.of(handle(fileId = "4711"))
        val current: String = WearSyncFingerprint.of(handle(fileId = "5122"))
        assertTrue(WearSyncFingerprint.differs(held, current))
    }

    @Test
    fun `a changed size is stale`() {
        val held: String = WearSyncFingerprint.of(handle(sizeBytes = 41_000_000L))
        val current: String = WearSyncFingerprint.of(handle(sizeBytes = 52_400_000L))
        assertTrue(WearSyncFingerprint.differs(held, current))
    }

    @Test
    fun `a changed format is stale`() {
        val held: String = WearSyncFingerprint.of(handle(format = AudioFormat.MP3))
        val current: String = WearSyncFingerprint.of(handle(format = AudioFormat.FLAC))
        assertTrue(WearSyncFingerprint.differs(held, current))
    }

    @Test
    fun `a field the server has stopped reporting is not a change`() {
        // The test this whole file exists for. REQUIREMENTS.md: "A field counts as changed only when both
        // sides carry a value. A null on either side means unknown, never changed... Unknown is not
        // evidence." Without this the watch re-fetches everything it holds, over Bluetooth, for nothing.
        val held: String = WearSyncFingerprint.of(handle(durationMs = 254_000L))
        val current: String = WearSyncFingerprint.of(handle(durationMs = null))
        assertFalse(WearSyncFingerprint.differs(held, current))
    }

    @Test
    fun `a field the server has started reporting is not a change`() {
        // The converse, which matters for bytes sent by an older build of the phone app: the token it
        // wrote may be missing a field this build fills in, and that is not evidence of new bytes either.
        val held: String = WearSyncFingerprint.of(handle(bitrateKbps = null))
        val current: String = WearSyncFingerprint.of(handle(bitrateKbps = 1_010))
        assertFalse(WearSyncFingerprint.differs(held, current))
    }

    @Test
    fun `every field absent but the id is not a change`() {
        val held: String = WearSyncFingerprint.of(
            handle(sizeBytes = null, durationMs = null, format = null, bitrateKbps = null),
        )
        val current: String = WearSyncFingerprint.of(handle())
        assertFalse(WearSyncFingerprint.differs(held, current))
    }

    @Test
    fun `an unreadable held token is stale`() {
        // It describes bytes whose currency cannot be established, and REQUIREMENTS.md does not allow
        // those to survive. It cannot loop: the replacement carries a token this phone wrote.
        val current: String = WearSyncFingerprint.of(handle())
        assertTrue(WearSyncFingerprint.differs("", current))
        assertTrue(WearSyncFingerprint.differs("garbage", current))
        assertTrue(WearSyncFingerprint.differs("4711|41000000", current))
        assertTrue(WearSyncFingerprint.differs("4711|a|b|c|d|e", current))
    }

    @Test
    fun `the token never carries a separator inside a field`() {
        // Splitting is by count, so a field containing the separator would shift every later field along
        // and make two unrelated tracks compare equal - or unequal - by accident.
        val token: String = WearSyncFingerprint.of(handle(fileId = "4|7\t11"))
        assertEquals(5, token.split(WearPlaybackProtocol.FINGERPRINT_SEPARATOR).size)
    }

    @Test
    fun `a file id that looks like the absent marker is kept distinguishable`() {
        // An id rendered as "-" would be read back as unknown, which for the id field means "replace
        // these bytes" - so a real id is never allowed to collide with the marker.
        val token: String = WearSyncFingerprint.of(handle(fileId = "-"))
        val fields: List<String> = token.split(WearPlaybackProtocol.FINGERPRINT_SEPARATOR)
        assertTrue(fields[0] != WearPlaybackProtocol.FINGERPRINT_ABSENT)
    }

    // ---- what goes next --------------------------------------------------------------------------

    private fun candidate(
        key: String,
        fingerprint: String = "4711|1|2|FLAC|1010",
        sizeBytes: Long = 30_000_000L,
    ): WearSyncCandidate = WearSyncCandidate(
        keyCanonical = key,
        fingerprint = fingerprint,
        filePath = "/data/audio/" + key.replace("/", "_"),
        sizeBytes = sizeBytes,
        title = "Track " + key,
        artist = "An Artist",
        albumTitle = "An Album",
        durationMs = 200_000L,
        format = "FLAC",
    )

    private val albumMbid: String = "a1b2c3d4-0000-4000-8000-000000000001"

    private fun key(track: Int): String = albumMbid + "/1/" + track

    @Test
    fun `the first not-held tracks are chosen, in record order`() {
        val candidates: List<WearSyncCandidate> = (1..6).map { track -> candidate(key(track)) }
        val next: List<WearSyncCandidate> = WearSyncPlan.next(
            candidates = candidates,
            held = mapOf(key(1) to "4711|1|2|FLAC|1010", key(2) to "4711|1|2|FLAC|1010"),
            limit = 2,
            watchFreeBytes = 4_000_000_000L,
        )
        assertEquals(listOf(key(3), key(4)), next.map { chosen -> chosen.keyCanonical })
    }

    @Test
    fun `a held track whose bytes have moved is sent again`() {
        // This is the eviction, across two devices: the watch overwrites by key, so republishing the
        // track is what replaces the pre-upgrade copy on the wrist.
        val current: String = WearSyncFingerprint.of(handle(fileId = "5122"))
        val stale: String = WearSyncFingerprint.of(handle(fileId = "4711"))
        val next: List<WearSyncCandidate> = WearSyncPlan.next(
            candidates = listOf(candidate(key(1), fingerprint = current)),
            held = mapOf(key(1) to stale),
            limit = 2,
            watchFreeBytes = 4_000_000_000L,
        )
        assertEquals(listOf(key(1)), next.map { chosen -> chosen.keyCanonical })
    }

    @Test
    fun `nothing is chosen when the watch holds it all`() {
        val fingerprint = "4711|1|2|FLAC|1010"
        val next: List<WearSyncCandidate> = WearSyncPlan.next(
            candidates = listOf(candidate(key(1), fingerprint), candidate(key(2), fingerprint)),
            held = mapOf(key(1) to fingerprint, key(2) to fingerprint),
            limit = 2,
            watchFreeBytes = 4_000_000_000L,
        )
        assertTrue(next.isEmpty())
    }

    @Test
    fun `the in-flight limit is respected`() {
        val candidates: List<WearSyncCandidate> = (1..9).map { track -> candidate(key(track)) }
        assertEquals(
            1,
            WearSyncPlan.next(candidates, emptyMap(), limit = 1, watchFreeBytes = 0L).size,
        )
        assertEquals(
            0,
            WearSyncPlan.next(candidates, emptyMap(), limit = 0, watchFreeBytes = 0L).size,
        )
    }

    @Test
    fun `a track past the size ceiling is never published`() {
        val next: List<WearSyncCandidate> = WearSyncPlan.next(
            candidates = listOf(
                candidate(key(1), sizeBytes = WearPlaybackProtocol.MAX_TRACK_BYTES + 1L),
                candidate(key(2), sizeBytes = 10_000_000L),
            ),
            held = emptyMap(),
            limit = 2,
            watchFreeBytes = 4_000_000_000L,
        )
        assertEquals(listOf(key(2)), next.map { chosen -> chosen.keyCanonical })
    }

    @Test
    fun `a track larger than the watch says it can hold is skipped`() {
        val next: List<WearSyncCandidate> = WearSyncPlan.next(
            candidates = listOf(candidate(key(1), sizeBytes = 30_000_000L)),
            held = emptyMap(),
            limit = 2,
            watchFreeBytes = 5_000_000L,
        )
        assertTrue(next.isEmpty())
    }

    @Test
    fun `a watch that reported no free figure is not treated as full`() {
        // Zero means "did not say" - an older build, or a volume that could not be read. Refusing on it
        // would stop the pipeline for ever; the watch's own check is what actually decides.
        val next: List<WearSyncCandidate> = WearSyncPlan.next(
            candidates = listOf(candidate(key(1), sizeBytes = 30_000_000L)),
            held = emptyMap(),
            limit = 2,
            watchFreeBytes = 0L,
        )
        assertEquals(1, next.size)
    }

    @Test
    fun `a track with no size is skipped`() {
        // Nothing can be checked against a declared size of zero, and the watch refuses it at ingest, so
        // publishing it would be a transfer thrown away.
        val next: List<WearSyncCandidate> = WearSyncPlan.next(
            candidates = listOf(candidate(key(1), sizeBytes = 0L)),
            held = emptyMap(),
            limit = 2,
            watchFreeBytes = 4_000_000_000L,
        )
        assertTrue(next.isEmpty())
    }

    // ---- what is offered -------------------------------------------------------------------------

    private fun album(
        key: String,
        title: String,
        tracks: Int = 10,
        bytes: Long = 300_000_000L,
        downloadedAt: Long = 1_000L,
    ): WearOfferAlbum = WearOfferAlbum(
        albumKey = key,
        title = title,
        artist = "An Artist",
        downloadedTrackCount = tracks,
        sizeBytes = bytes,
        downloadedAtEpochMs = downloadedAt,
    )

    @Test
    fun `the offer is newest first`() {
        // Not largest first, which is what the repository emits for a Storage screen hunting gigabytes. A
        // watch picker wants the album the user has just acquired.
        val window: WearOfferWindow = WearSyncPlan.offer(
            albums = listOf(
                album("a", "Older", downloadedAt = 100L),
                album("b", "Newest", downloadedAt = 900L),
                album("c", "Middle", downloadedAt = 500L),
            ),
            limit = 10,
        )
        assertEquals(listOf("Newest", "Middle", "Older"), window.rows.map { row -> row.title })
        assertEquals(0, window.notShown)
    }

    @Test
    fun `an album with nothing downloaded is not offered`() {
        // REQUIREMENTS.md "Partial content is a normal state" allows a pin whose download never landed.
        // Offering one would have the watch's pipeline wait for tracks that are not there.
        val window: WearOfferWindow = WearSyncPlan.offer(
            albums = listOf(album("a", "Empty", tracks = 0), album("b", "Real", tracks = 12)),
            limit = 10,
        )
        assertEquals(listOf("Real"), window.rows.map { row -> row.title })
    }

    @Test
    fun `the offer reports what it could not fit`() {
        val albums: List<WearOfferAlbum> = (1..8).map { index ->
            album("a" + index, "Album " + index, downloadedAt = index.toLong())
        }
        val window: WearOfferWindow = WearSyncPlan.offer(albums = albums, limit = 3)
        assertEquals(3, window.rows.size)
        assertEquals(5, window.notShown)
        // Newest first, so the three kept are the three highest timestamps.
        assertEquals(listOf("Album 8", "Album 7", "Album 6"), window.rows.map { row -> row.title })
    }

    @Test
    fun `albums downloaded at the same instant are ordered by title`() {
        // A stable tie-break, so the picker does not reshuffle itself between passes for no reason.
        val window: WearOfferWindow = WearSyncPlan.offer(
            albums = listOf(
                album("a", "Zoo", downloadedAt = 500L),
                album("b", "Aviary", downloadedAt = 500L),
            ),
            limit = 10,
        )
        assertEquals(listOf("Aviary", "Zoo"), window.rows.map { row -> row.title })
    }

    // ---- how many tracks an album can supply -----------------------------------------------------

    @Test
    fun `a complete download offers the album's length`() {
        assertEquals(12, WearSyncPlan.downloadedTracks(OfflineDownloadState.Complete, 12))
    }

    @Test
    fun `a complete download whose album length is unknown offers nothing`() {
        // Better than guessing: an offer claiming tracks the phone cannot produce would have the watch
        // wait for them for ever.
        assertEquals(0, WearSyncPlan.downloadedTracks(OfflineDownloadState.Complete, null))
    }

    @Test
    fun `a download in flight offers what has landed`() {
        assertEquals(
            4,
            WearSyncPlan.downloadedTracks(
                OfflineDownloadState.Downloading(tracksComplete = 4, tracksTotal = 12),
                12,
            ),
        )
    }

    @Test
    fun `a partial download offers what it has`() {
        assertEquals(
            7,
            WearSyncPlan.downloadedTracks(
                OfflineDownloadState.Partial(tracksComplete = 7, tracksTotal = 12),
                12,
            ),
        )
    }

    @Test
    fun `a pin that has not started offers nothing`() {
        assertEquals(0, WearSyncPlan.downloadedTracks(OfflineDownloadState.Queued, 12))
        assertEquals(
            0,
            WearSyncPlan.downloadedTracks(OfflineDownloadState.WaitingForUnmeteredNetwork, 12),
        )
    }

    // ---- what the watch reports holding ----------------------------------------------------------

    @Test
    fun `held entries parse into keys and fingerprints`() {
        val held: Map<String, String> = WearHeldTracks.parse(
            listOf(key(1) + "\t" + "4711|1|2|FLAC|1010", key(2) + "\t" + "5122|1|2|FLAC|1010"),
        )
        assertEquals(2, held.size)
        assertEquals("4711|1|2|FLAC|1010", held[key(1)])
    }

    @Test
    fun `a malformed held entry is dropped rather than guessed at`() {
        // With only one half there is no telling a key with no fingerprint from a fingerprint with no
        // key, and a dropped entry reads as "the watch does not hold this" - one redundant transfer.
        val held: Map<String, String> = WearHeldTracks.parse(
            listOf(
                key(1),
                "\tjust-a-fingerprint",
                key(2) + "\t",
                key(3) + "\ta\tb",
                key(4) + "\tgood",
            ),
        )
        assertEquals(mapOf(key(4) to "good"), held)
    }

    @Test
    fun `a duplicated held key takes the first entry`() {
        val held: Map<String, String> = WearHeldTracks.parse(
            listOf(key(1) + "\tfirst", key(1) + "\tsecond"),
        )
        assertEquals("first", held[key(1)])
    }

    @Test
    fun `no held entries is an empty map, not a failure`() {
        assertTrue(WearHeldTracks.parse(null).isEmpty())
        assertTrue(WearHeldTracks.parse(emptyList()).isEmpty())
    }
}
