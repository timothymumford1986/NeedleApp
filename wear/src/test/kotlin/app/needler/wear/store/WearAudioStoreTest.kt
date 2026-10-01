package app.needler.wear.store

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The store's commit order, over a real directory.
 *
 * This is the part of the watch's on-device audio that a shipped bug has already been written about at the
 * other end of the link. REQUIREMENTS.md's "Downloads were not retained, and now are" ends with "a row
 * claiming a file is checked against the file existing", and every assertion below is a version of that
 * rule: a track counts as held only when its sidecar and its bytes agree, and every failure path leaves
 * the store in a state the sweep can clean rather than in one that plays half a song.
 *
 * It runs on the JVM against a temporary folder, which the `internal` constructor is what allows. Two
 * consequences worth knowing:
 *
 *  * The free-space figures are the *build machine's*, so the room checks pass on any machine with a few
 *    gigabytes spare and would fail on one that is nearly full. That is deliberate rather than
 *    overlooked - the alternative is a fake volume, which would test [WearStoreSpace] a second time
 *    instead of testing that the store consults it at all. [WearStoreTest] covers the bound itself with no
 *    filesystem.
 *  * Nothing here touches Google Play services, so an `Asset` is stood in for by a plain `InputStream`,
 *    which is exactly the seam [WearAudioStore.ingestTrack] takes a lambda for.
 */
class WearAudioStoreTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val mbid: String = "a1b2c3d4-0000-4000-8000-00000000000a"

    private fun store(): WearAudioStore = WearAudioStore(folder.root)

    private fun key(track: Int): WearAudioKey =
        WearAudioKey(releaseGroupMbid = mbid, discNumber = 1, trackNumber = track)

    private fun record(track: Int, bytes: Int): WearTrackRecord = WearTrackRecord(
        key = key(track),
        title = "Track " + track,
        artist = "The Marias",
        albumTitle = "Submarine",
        durationMs = 200_000L,
        format = "FLAC",
        sizeBytes = bytes.toLong(),
        fingerprint = "4711|" + bytes + "|200000|FLAC|1010",
    )

    private fun source(bytes: Int): suspend () -> InputStream? =
        { ByteArrayInputStream(ByteArray(bytes) { index -> index.toByte() }) }

    private fun audioDir(): File = File(folder.root, WearAudioStore.AUDIO_DIRECTORY)

    private fun filesIn(directory: File): List<String> =
        directory.listFiles()?.map { file -> file.name }?.sorted() ?: emptyList()

    @Test
    fun `an ingested track is held, playable and reported`() = runTest {
        val store: WearAudioStore = store()
        val outcome: WearIngestOutcome = store.ingestTrack(record(1, 2_048), source(2_048))
        assertEquals(WearIngestOutcome.Ingested, outcome)

        val contents: WearStoreContents = store.contents.value
        assertEquals(1, contents.trackCount)
        assertEquals(2_048L, contents.bytes)
        assertEquals(setOf(mbid + "/1/1"), contents.heldKeys)
        assertTrue(store.playableFile(key(1))?.isFile == true)
    }

    @Test
    fun `the sidecar is the commit record`() {
        // Written last and named separately, so the pair on disk is an audio file plus a sidecar. Anything
        // else is an interrupted transfer, which is what the next two tests are about.
        runTest {
            store().ingestTrack(record(1, 512), source(512))
        }
        assertEquals(
            listOf(mbid + "_1_1" + WearAudioStore.AUDIO_SUFFIX, mbid + "_1_1" + WearAudioStore.SIDECAR_SUFFIX),
            filesIn(audioDir()),
        )
    }

    @Test
    fun `audio with no sidecar is not held and is swept`() = runTest {
        // The state a crash between the rename and the sidecar write leaves. Without the sidecar nothing
        // knows what the bytes are, what they weigh, or which server file they came from - so the track is
        // not playable and not reportable, and the bytes are reclaimed rather than leaked.
        val store: WearAudioStore = store()
        store.ingestTrack(record(1, 512), source(512))
        File(audioDir(), mbid + "_1_1" + WearAudioStore.SIDECAR_SUFFIX).delete()

        store.refresh()
        assertEquals(0, store.contents.value.trackCount)
        assertNull(store.playableFile(key(1)))

        assertEquals(1, store.sweep())
        assertTrue(filesIn(audioDir()).isEmpty())
    }

    @Test
    fun `a sidecar with no audio is not held and is swept`() = runTest {
        val store: WearAudioStore = store()
        store.ingestTrack(record(1, 512), source(512))
        File(audioDir(), mbid + "_1_1" + WearAudioStore.AUDIO_SUFFIX).delete()

        store.refresh()
        assertEquals(0, store.contents.value.trackCount)
        assertNull(store.playableFile(key(1)))

        assertEquals(1, store.sweep())
        assertTrue(filesIn(audioDir()).isEmpty())
    }

    @Test
    fun `a file whose length disagrees with its record is not playable`() = runTest {
        // The rule REQUIREMENTS.md records as a shipped bug, in its sharpest form: a truncated file that
        // was renamed into place would play and stop halfway through with nothing reporting an error.
        val store: WearAudioStore = store()
        store.ingestTrack(record(1, 512), source(512))
        File(audioDir(), mbid + "_1_1" + WearAudioStore.AUDIO_SUFFIX).writeBytes(ByteArray(64))

        store.refresh()
        assertEquals(0, store.contents.value.trackCount)
        assertNull(store.playableFile(key(1)))
    }

    @Test
    fun `a short transfer is refused and leaves nothing behind`() = runTest {
        // The declared size is what arriving bytes are checked against. A part file here is not a resume
        // point - a data-layer Asset has no offset to resume at - so it goes, and the next pass starts the
        // track again.
        val store: WearAudioStore = store()
        val outcome: WearIngestOutcome = store.ingestTrack(record(1, 2_048), source(1_000))
        assertEquals(WearIngestOutcome.Failed, outcome)
        assertEquals(0, store.refresh().trackCount)
        assertTrue(filesIn(audioDir()).none { name -> name.endsWith(WearAudioStore.AUDIO_SUFFIX) })
        assertTrue(filesIn(audioDir()).none { name -> name.endsWith(WearAudioStore.PART_SUFFIX) })
    }

    @Test
    fun `a transfer that cannot be opened fails without leaving a part file`() = runTest {
        val store: WearAudioStore = store()
        val outcome: WearIngestOutcome = store.ingestTrack(record(1, 2_048)) {
            throw IOException("the link dropped")
        }
        assertEquals(WearIngestOutcome.Failed, outcome)
        assertTrue(filesIn(audioDir()).isEmpty())
    }

    @Test
    fun `a track with no declared size is refused before anything is fetched`() = runTest {
        // Nothing could be checked against zero, so the bytes would be unverifiable - and an unverifiable
        // file is exactly what the commit order exists to keep out of the store.
        var opened = false
        val store: WearAudioStore = store()
        val outcome: WearIngestOutcome = store.ingestTrack(record(1, 0)) {
            opened = true
            ByteArrayInputStream(ByteArray(0))
        }
        assertEquals(WearIngestOutcome.Refused, outcome)
        assertFalse("the source must not be opened for a refused track", opened)
    }

    @Test
    fun `the same bytes arriving twice are reported as already held`() = runTest {
        // Not a failure and not a silent no-op: reporting it is what lets the phone delete the item it
        // published and move on to the next track, so the pipeline advances instead of stalling.
        val store: WearAudioStore = store()
        assertEquals(WearIngestOutcome.Ingested, store.ingestTrack(record(1, 512), source(512)))
        assertEquals(WearIngestOutcome.AlreadyHeld, store.ingestTrack(record(1, 512), source(512)))
        assertEquals(1, store.contents.value.trackCount)
    }

    @Test
    fun `bytes with a new fingerprint replace the old ones`() = runTest {
        // This is REQUIREMENTS.md "Invalidating upgraded files" landing on the watch. The phone decides
        // that the server has replaced a file and republishes it; overwriting by key is the eviction.
        val store: WearAudioStore = store()
        store.ingestTrack(record(1, 512), source(512))

        val upgraded: WearTrackRecord = record(1, 1_024).copy(fingerprint = "9999|1024|200000|FLAC|1010")
        assertEquals(WearIngestOutcome.Ingested, store.ingestTrack(upgraded, source(1_024)))

        val contents: WearStoreContents = store.contents.value
        assertEquals(1, contents.trackCount)
        assertEquals(1_024L, contents.bytes)
        assertEquals(1_024L, store.playableFile(key(1))?.length() ?: 0L)
        assertEquals("9999|1024|200000|FLAC|1010", contents.albums.single().tracks.single().fingerprint)
    }

    @Test
    fun `a part file left by a dead process is swept`() = runTest {
        // Unlike the phone's download partials, which are "the bytes a Range GET resumes from", a part
        // file here stands for nothing: an Asset transfer restarts from zero. Anything found outside the
        // write lock was left by a process that is gone.
        val store: WearAudioStore = store()
        store.ingestTrack(record(1, 512), source(512))
        File(audioDir(), mbid + "_1_9" + WearAudioStore.PART_SUFFIX).writeBytes(ByteArray(300))

        assertEquals(1, store.sweep())
        assertEquals(1, store.contents.value.trackCount)
    }

    @Test
    fun `removing an album deletes its bytes and reports what that freed`() = runTest {
        // REQUIREMENTS.md is emphatic about the figure for the phone: "a remove that leaves the usage
        // figure unchanged is the one thing that would make this whole screen untrustworthy." A watch has
        // less room and one lever.
        val store: WearAudioStore = store()
        store.ingestTrack(record(1, 512), source(512))
        store.ingestTrack(record(2, 1_024), source(1_024))

        val removed: WearRemoved = store.removeAlbum(mbid)
        assertEquals(2, removed.trackCount)
        assertEquals(1_536L, removed.freedBytes)
        assertEquals(0, store.contents.value.trackCount)
        assertEquals(0L, store.contents.value.bytes)
        assertTrue(filesIn(audioDir()).isEmpty())
    }

    @Test
    fun `removing an album the watch does not hold reports nothing and is not an error`() = runTest {
        val store: WearAudioStore = store()
        val removed: WearRemoved = store.removeAlbum("a1b2c3d4-0000-4000-8000-00000000000b")
        assertEquals(0, removed.trackCount)
        assertEquals(0L, removed.freedBytes)
    }

    @Test
    fun `removing everything clears the audio and the covers`() = runTest {
        val store: WearAudioStore = store()
        store.ingestTrack(record(1, 512), source(512))
        store.ingestCover(mbid, source(400))
        assertTrue(store.coverFile(mbid)?.isFile == true)

        val removed: WearRemoved = store.removeAll()
        assertEquals(1, removed.trackCount)
        assertEquals(0, store.contents.value.trackCount)
        assertNull(store.coverFile(mbid))
    }

    @Test
    fun `a cover is written and found again`() = runTest {
        val store: WearAudioStore = store()
        assertTrue(store.ingestCover(mbid, source(4_096)))
        assertEquals(4_096L, store.coverFile(mbid)?.length() ?: 0L)
    }

    @Test
    fun `a cover past the ceiling is refused`() = runTest {
        // A refusal of a full-size scan rather than a size the watch needs: two megabytes of cover over
        // Bluetooth would take longer than the track it belongs to.
        val store: WearAudioStore = store()
        val tooBig: Int = (WearAudioStore.MAX_COVER_BYTES + 1L).toInt()
        assertFalse(store.ingestCover(mbid, source(tooBig)))
        assertNull(store.coverFile(mbid))
    }

    @Test
    fun `an unsafe album key never names a cover file`() = runTest {
        val store: WearAudioStore = store()
        assertFalse(store.ingestCover("../../escape", source(400)))
        assertNull(store.coverFile("../../escape"))
    }

    @Test
    fun `an empty store reports nothing rather than failing`() = runTest {
        val store: WearAudioStore = store()
        val contents: WearStoreContents = store.refresh()
        assertTrue(contents.isEmpty)
        assertTrue(contents.scanned)
        assertEquals(0, store.sweep())
        assertNull(store.playableFile(key(1)))
    }

    @Test
    fun `tracks of two albums are grouped and removed independently`() = runTest {
        val other = "a1b2c3d4-0000-4000-8000-00000000000b"
        val store: WearAudioStore = store()
        store.ingestTrack(record(1, 512), source(512))
        store.ingestTrack(
            record(1, 700).copy(key = WearAudioKey(other, 1, 1), albumTitle = "Another"),
            source(700),
        )
        assertEquals(2, store.contents.value.albums.size)

        store.removeAlbum(other)
        assertEquals(listOf(mbid), store.contents.value.albums.map { album -> album.albumKey })
    }
}
