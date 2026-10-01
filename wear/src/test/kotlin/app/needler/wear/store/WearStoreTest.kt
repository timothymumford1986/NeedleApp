package app.needler.wear.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch store's pure parts: the key, the sidecar format, the free-space bound and the grouping.
 *
 * [WearAudioStore] itself needs a filesystem and a `Context`, and everything in it that decides anything
 * has been pushed out into the four objects below so that it can be asserted here instead. Three of these
 * would fail silently if they were wrong: a key that named the wrong file, a codec that lost a title
 * containing a tab, and a bound that let the watch fill its own volume.
 */
class WearStoreTest {

    private val mbid: String = "a1b2c3d4-0000-4000-8000-000000000001"

    // ---- the key ---------------------------------------------------------------------------------

    @Test
    fun `a key round-trips through its canonical form`() {
        val key = WearAudioKey(releaseGroupMbid = mbid, discNumber = 2, trackNumber = 7)
        assertEquals(mbid + "/2/7", key.canonical)
        assertEquals(key, WearAudioKey.parse(key.canonical))
    }

    @Test
    fun `the canonical form is the phone's`() {
        // TrackKey.canonicalString in :core:domain renders "<mbid>/<disc>/<track>". The two stores have to
        // talk about the same track in the same words or nothing can be diffed against anything.
        assertEquals(mbid + "/1/1", WearAudioKey(mbid, 1, 1).canonical)
    }

    @Test
    fun `the file stem substitutes the path separator`() {
        val key = WearAudioKey(mbid, 2, 7)
        assertEquals(mbid + "_2_7", key.fileStem)
        assertFalse(key.fileStem.contains("/"))
    }

    @Test
    fun `a key that is not three parts is refused`() {
        assertNull(WearAudioKey.parse(null))
        assertNull(WearAudioKey.parse(""))
        assertNull(WearAudioKey.parse(mbid))
        assertNull(WearAudioKey.parse(mbid + "/1"))
        assertNull(WearAudioKey.parse(mbid + "/1/2/3"))
    }

    @Test
    fun `a disc or track that is not a positive number is refused`() {
        // The domain requires both 1-based. A zero or a negative comes from a mismatched build, not a
        // record, and it would name a perfectly valid file for a track that does not exist.
        assertNull(WearAudioKey.parse(mbid + "/x/2"))
        assertNull(WearAudioKey.parse(mbid + "/1/y"))
        assertNull(WearAudioKey.parse(mbid + "/0/2"))
        assertNull(WearAudioKey.parse(mbid + "/1/-4"))
    }

    @Test
    fun `an identifier that could escape the store directory is refused`() {
        // The store names every file it writes from this. A key arriving over a wire is untrusted text.
        assertFalse(WearAudioKey.isSafeIdentifier(".."))
        assertFalse(WearAudioKey.isSafeIdentifier("."))
        assertFalse(WearAudioKey.isSafeIdentifier("../../etc"))
        assertFalse(WearAudioKey.isSafeIdentifier("a/b"))
        assertFalse(WearAudioKey.isSafeIdentifier("a\\b"))
        assertFalse(WearAudioKey.isSafeIdentifier(""))
        assertNull(WearAudioKey.parse("../../etc/1/1"))
    }

    @Test
    fun `an identifier containing the file separator is refused`() {
        // Excluded from the alphabet so the key-to-filename mapping is obviously injective rather than
        // provably injective. No MusicBrainz identifier contains an underscore.
        assertFalse(WearAudioKey.isSafeIdentifier("a_b"))
        assertTrue(WearAudioKey.isSafeIdentifier(mbid))
    }

    @Test
    fun `an over-long identifier is refused`() {
        val long: String = "a".repeat(WearAudioKey.MAX_IDENTIFIER_LENGTH + 1)
        assertFalse(WearAudioKey.isSafeIdentifier(long))
        assertTrue(WearAudioKey.isSafeIdentifier("a".repeat(WearAudioKey.MAX_IDENTIFIER_LENGTH)))
    }

    // ---- the sidecar -----------------------------------------------------------------------------

    private fun record(
        title: String = "Sienna",
        artist: String = "The Marias",
        albumTitle: String = "Submarine",
        durationMs: Long = 214_000L,
        format: String = "FLAC",
        sizeBytes: Long = 41_000_000L,
        fingerprint: String = "4711|41000000|214000|FLAC|1010",
    ): WearTrackRecord = WearTrackRecord(
        key = WearAudioKey(mbid, 1, 3),
        title = title,
        artist = artist,
        albumTitle = albumTitle,
        durationMs = durationMs,
        format = format,
        sizeBytes = sizeBytes,
        fingerprint = fingerprint,
    )

    @Test
    fun `a record round-trips`() {
        val original: WearTrackRecord = record()
        assertEquals(original, WearTrackRecordCodec.decode(WearTrackRecordCodec.encode(original)))
    }

    @Test
    fun `a title containing a tab or a newline round-trips`() {
        // Without escaping, a newline in a title turns one record into two half-records, and the sweep
        // then deletes a perfectly good track. Titles can legitimately contain anything.
        val awkward: WearTrackRecord = record(title = "one\ttwo\nthree\r\\four")
        val decoded: WearTrackRecord? =
            WearTrackRecordCodec.decode(WearTrackRecordCodec.encode(awkward))
        assertEquals("one\ttwo\nthree\r\\four", decoded?.title)
    }

    @Test
    fun `a value that already contained a backslash round-trips`() {
        // The ordering test: the backslash has to be escaped first on the way out and handled last on the
        // way in, or a title ending in one swallows the next character.
        val awkward: WearTrackRecord = record(artist = "AC\\DC\\")
        assertEquals(
            "AC\\DC\\",
            WearTrackRecordCodec.decode(WearTrackRecordCodec.encode(awkward))?.artist,
        )
    }

    @Test
    fun `a record with no version is refused`() {
        assertNull(WearTrackRecordCodec.decode("key\t" + mbid + "/1/3\n"))
    }

    @Test
    fun `a record from a future version is refused rather than read hopefully`() {
        val text: String = WearTrackRecordCodec.encode(record())
            .replace("v\t1", "v\t99")
        assertNull(WearTrackRecordCodec.decode(text))
    }

    @Test
    fun `a record whose key will not parse is refused`() {
        // A record that cannot be trusted to identify its own bytes is worse than a missing one: it would
        // name a file, and the file it named would not be the track it described.
        val text: String = WearTrackRecordCodec.encode(record())
            .replace(mbid + "/1/3", "../../escape")
        assertNull(WearTrackRecordCodec.decode(text))
    }

    @Test
    fun `an unknown line is ignored and a missing field defaults`() {
        // A store written by an older build must stay readable, and one written by a newer build must
        // degrade rather than fail.
        val text: String = "v\t1\nkey\t" + mbid + "/1/3\ntitle\tSienna\nsomethingNew\twhatever\n"
        val decoded: WearTrackRecord? = WearTrackRecordCodec.decode(text)
        assertEquals("Sienna", decoded?.title)
        assertEquals("", decoded?.artist)
        assertEquals(0L, decoded?.durationMs)
        assertEquals(0L, decoded?.sizeBytes)
    }

    @Test
    fun `a negative number in a sidecar clamps to zero`() {
        val text: String = WearTrackRecordCodec.encode(record())
            .replace("sizeBytes\t41000000", "sizeBytes\t-5")
        assertEquals(0L, WearTrackRecordCodec.decode(text)?.sizeBytes)
    }

    @Test
    fun `a blank sidecar decodes to nothing`() {
        assertNull(WearTrackRecordCodec.decode(null))
        assertNull(WearTrackRecordCodec.decode(""))
        assertNull(WearTrackRecordCodec.decode("   "))
    }

    @Test
    fun `the held entry is the key and the fingerprint`() {
        val row: WearTrackRecord = record(fingerprint = "4711|1|2|FLAC|1010")
        assertEquals(
            mbid + "/1/3" + WearTrackRecord.HELD_SEPARATOR + "4711|1|2|FLAC|1010",
            row.heldEntry,
        )
    }

    // ---- the bound -------------------------------------------------------------------------------

    @Test
    fun `a small volume gets the minimum floor`() {
        // 512 MB of a 4 GB watch. The phone's 2 GB would leave the store unusable on a device like that,
        // which is why the watch's minimum is its own number.
        assertEquals(
            WearStoreSpace.MINIMUM_FREE_BYTES,
            WearStoreSpace.floorFor(4L * 1000L * 1000L * 1000L),
        )
    }

    @Test
    fun `a large volume gets the proportional floor`() {
        val total: Long = 32L * 1000L * 1000L * 1000L
        assertEquals(total / 100L * WearStoreSpace.VOLUME_PERCENT, WearStoreSpace.floorFor(total))
    }

    @Test
    fun `an unmeasurable volume still gets a floor`() {
        // Never zero: an unmeasurable volume is the device least well understood, and removing the floor
        // there is removing it where it is most needed.
        assertEquals(WearStoreSpace.MINIMUM_FREE_BYTES, WearStoreSpace.floorFor(0L))
        assertEquals(WearStoreSpace.MINIMUM_FREE_BYTES, WearStoreSpace.floorFor(-1L))
    }

    @Test
    fun `the proportional floor does not overflow a huge volume`() {
        // Computed as total / 100 * percent rather than total * percent / 100, exactly as the phone's is.
        val huge: Long = Long.MAX_VALUE / 2L
        assertTrue(WearStoreSpace.floorFor(huge) > 0L)
    }

    @Test
    fun `headroom is what is left above the floor`() {
        val total: Long = 8L * 1000L * 1000L * 1000L
        val floor: Long = WearStoreSpace.floorFor(total)
        val space = WearStoreSpace(usableBytes = floor + 1_000_000_000L, totalBytes = total)
        assertEquals(1_000_000_000L, space.headroomBytes)
        assertTrue(space.canAccept(900_000_000L))
        assertFalse(space.canAccept(1_100_000_000L))
    }

    @Test
    fun `a watch at its floor accepts nothing`() {
        // And nothing is evicted to change that. The watch's tier is entirely REQUIREMENTS.md's
        // "Downloaded", which is "never evicted automatically", so the floor refuses instead.
        val total: Long = 8L * 1000L * 1000L * 1000L
        val space = WearStoreSpace(usableBytes = WearStoreSpace.floorFor(total), totalBytes = total)
        assertEquals(0L, space.headroomBytes)
        assertFalse(space.canAccept(1L))
    }

    @Test
    fun `an unreadable volume is refused rather than guessed at`() {
        // REQUIREMENTS.md: a failed reading "suspends the policy rather than guessing". On the watch,
        // suspending means not accepting - the bytes are still on the phone, so nothing is lost.
        val unknown = WearStoreSpace.Unknown
        assertTrue(unknown.isUnknown)
        assertEquals(0L, unknown.headroomBytes)
        assertFalse(unknown.canAccept(1L))
    }

    @Test
    fun `a genuinely full volume is distinguished from an unreadable one`() {
        // The pair of readings is what separates them, and they call for opposite explanations to the
        // user: "your watch is full" against "the watch could not be measured".
        val full = WearStoreSpace(usableBytes = 0L, totalBytes = 8L * 1000L * 1000L * 1000L)
        assertFalse(full.isUnknown)
        assertEquals(0L, full.headroomBytes)
    }

    @Test
    fun `nothing accepts a zero or negative incoming size`() {
        val space = WearStoreSpace(usableBytes = 8_000_000_000L, totalBytes = 8_000_000_000L)
        assertFalse(space.canAccept(0L))
        assertFalse(space.canAccept(-1L))
    }

    // ---- the grouping ----------------------------------------------------------------------------

    private fun track(
        album: String,
        disc: Int,
        number: Int,
        artist: String = "The Marias",
        albumTitle: String = "Submarine",
        bytes: Long = 30_000_000L,
        format: String = "FLAC",
    ): WearTrackRecord = WearTrackRecord(
        key = WearAudioKey(album, disc, number),
        title = "Track " + number,
        artist = artist,
        albumTitle = albumTitle,
        durationMs = 200_000L,
        format = format,
        sizeBytes = bytes,
        fingerprint = "4711|1|2|FLAC|1010",
    )

    private val albumA: String = "a1b2c3d4-0000-4000-8000-00000000000a"
    private val albumB: String = "a1b2c3d4-0000-4000-8000-00000000000b"

    @Test
    fun `tracks within an album run in record order`() {
        // Not in whatever order the volume's directory listing came back in, which is stable enough to
        // look deliberate and arbitrary enough to be wrong.
        val contents: WearStoreContents = WearStoreContents.of(
            records = listOf(
                track(albumA, 2, 1),
                track(albumA, 1, 4),
                track(albumA, 1, 2),
            ),
            space = WearStoreSpace.Unknown,
        )
        val order: List<String> = contents.albums.single().tracks.map { row -> row.key.canonical }
        assertEquals(
            listOf(albumA + "/1/2", albumA + "/1/4", albumA + "/2/1"),
            order,
        )
    }

    @Test
    fun `albums run by track count, most first`() {
        val contents: WearStoreContents = WearStoreContents.of(
            records = listOf(
                track(albumA, 1, 1, albumTitle = "Small"),
                track(albumB, 1, 1, albumTitle = "Big"),
                track(albumB, 1, 2, albumTitle = "Big"),
                track(albumB, 1, 3, albumTitle = "Big"),
            ),
            space = WearStoreSpace.Unknown,
        )
        assertEquals(listOf("Big", "Small"), contents.albums.map { album -> album.title })
    }

    @Test
    fun `usage adds up and held keys are reported`() {
        val contents: WearStoreContents = WearStoreContents.of(
            records = listOf(
                track(albumA, 1, 1, bytes = 10L),
                track(albumA, 1, 2, bytes = 20L),
            ),
            space = WearStoreSpace.Unknown,
        )
        assertEquals(30L, contents.bytes)
        assertEquals(2, contents.trackCount)
        assertEquals(2, contents.heldCount(albumA))
        assertEquals(0, contents.heldCount(albumB))
        assertEquals(setOf(albumA + "/1/1", albumA + "/1/2"), contents.heldKeys)
    }

    @Test
    fun `held entries for the wire are capped`() {
        // The selection item rides a 100 KB DataMap. An album with hundreds of tracks must not be the
        // thing that silently stops it publishing.
        val records: List<WearTrackRecord> = (1..40).map { number -> track(albumA, 1, number) }
        val contents: WearStoreContents =
            WearStoreContents.of(records = records, space = WearStoreSpace.Unknown)
        assertEquals(5, contents.heldEntriesFor(albumA, limit = 5).size)
        assertEquals(40, contents.heldEntriesFor(albumA, limit = 100).size)
        assertTrue(contents.heldEntriesFor(albumA, limit = 0).isEmpty())
        assertTrue(contents.heldEntriesFor(albumB, limit = 10).isEmpty())
    }

    @Test
    fun `an album whose tracks share one artist reports it`() {
        val contents: WearStoreContents = WearStoreContents.of(
            records = listOf(track(albumA, 1, 1), track(albumA, 1, 2)),
            space = WearStoreSpace.Unknown,
        )
        assertEquals("The Marias", contents.albums.single().artist)
    }

    @Test
    fun `a compilation reports no album artist rather than inventing one`() {
        // REQUIREMENTS.md "Vocabulary" fixes the product's words, so a new user-visible phrase is not a
        // thing to mint inside a data class. The screen draws the title alone.
        val contents: WearStoreContents = WearStoreContents.of(
            records = listOf(
                track(albumA, 1, 1, artist = "One"),
                track(albumA, 1, 2, artist = "Two"),
            ),
            space = WearStoreSpace.Unknown,
        )
        assertNull(contents.albums.single().artist)
    }

    @Test
    fun `an album whose tracks share one format reports it as a badge`() {
        val contents: WearStoreContents = WearStoreContents.of(
            records = listOf(track(albumA, 1, 1), track(albumA, 1, 2)),
            space = WearStoreSpace.Unknown,
        )
        assertEquals("FLAC", contents.albums.single().format)
        val mixed: WearStoreContents = WearStoreContents.of(
            records = listOf(
                track(albumA, 1, 1, format = "FLAC"),
                track(albumA, 1, 2, format = "MP3"),
            ),
            space = WearStoreSpace.Unknown,
        )
        assertNull(mixed.albums.single().format)
    }

    @Test
    fun `an empty store is scanned but empty, and is not the not-scanned state`() {
        // Two different facts. An empty list before the first scan reads as a lost library, which is the
        // mistake WearPlaybackState separates Connecting from Idle to avoid.
        val empty: WearStoreContents =
            WearStoreContents.of(records = emptyList(), space = WearStoreSpace.Unknown)
        assertTrue(empty.isEmpty)
        assertTrue(empty.scanned)
        assertFalse(WearStoreContents.NotScanned.scanned)
    }
}
