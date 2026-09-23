package app.needler.core.data.local.cache

import app.needler.core.data.fake.FakeAudioCacheDao
import app.needler.core.data.fake.RG
import app.needler.core.data.fake.cacheRow
import app.needler.core.domain.cache.AudioCacheWriteHandle
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What happens between "the bytes are on disk" and "the track is kept".
 *
 * This is the half of the store that REQUIREMENTS.md's "Offline and caching" section records as
 * broken: a pull ran for fifteen minutes, four tracks each reached several megabytes, and the device
 * kept nothing. Everything before this point worked, which is why the tests that came before all
 * passed - they exercised one track, in isolation, with nothing else touching the store. The
 * failures were all in the seams: a stream and a download sharing one partial file, a commit that
 * answered a short file by deleting it, a row outliving its bytes, and a row's tier being rewritten
 * from a snapshot taken before the pin existed.
 *
 * So these tests are about interference and about second attempts rather than about the happy path,
 * which [AudioCacheWriteTest] and `AlbumDownloadTest` already cover. The store is the real one over
 * the real [CacheIndex] for the same reason as those: the rules being checked are supposed to live
 * in one place, and a faked store would agree with whatever it was told.
 */
public class DownloadFinaliseTest {

    @get:Rule
    public val folder: TemporaryFolder = TemporaryFolder()

    private val dao = FakeAudioCacheDao()

    private val key = TrackKey(ReleaseGroupMbid(RG), discNumber = 1, trackNumber = 6)

    private val fetchHandle = TrackFetchHandle(
        fileId = FileId("8801"),
        sizeBytes = TRACK_BYTES,
        durationMs = 240_000L,
        format = AudioFormat.FLAC,
        bitrateKbps = null,
    )

    private fun writer(
        freeBytes: Long = 100_000_000L,
        floorBytes: Long = 1_000L,
    ): AudioCacheStoreWriter = AudioCacheStoreWriter(
        audioCacheDao = dao,
        cacheIndex = CacheIndex(
            audioCacheDao = dao,
            deviceFreeSpace = DeviceFreeSpace.of(freeBytes = freeBytes, floorBytes = floorBytes),
        ),
        audioDirectory = folder.root,
        nowMillis = { NOW },
    )

    private fun stream(): PlayableSource.Stream = PlayableSource.Stream(
        key = key,
        fetchHandle = fetchHandle,
        format = StreamFormat.Original,
        cacheWhileStreaming = true,
    )

    private suspend fun slotFrom(store: AudioCacheStoreWriter): AudioDownloadSlot.Open =
        store.openDownload(key, fetchHandle, TRACK_BYTES) as AudioDownloadSlot.Open

    /** Puts [bytes] into the download's part file, which is what `RangeDownloader` does. */
    private fun fillPart(slot: AudioDownloadSlot.Open, bytes: Long) {
        slot.partFile.writeBytes(ByteArray(bytes.toInt()) { 7 })
    }

    // ------------------------------------------------- the two write paths must not collide

    @Test
    public fun `playing a track cannot delete the download of it`(): Unit = runTest {
        // The reported data loss, in one test. Playback opens a cache write on every
        // original-format stream, and the player abandons that handle on every track change and
        // every seek - so an album being pulled while the user listened to it had the partial of
        // whichever track was in flight deleted underneath it, again and again, and retained
        // nothing. REQUIREMENTS.md asks that "a download still in flight never blocks playback of
        // the very track it is fetching", which has to hold in both directions to mean anything.
        val store: AudioCacheStoreWriter = writer()
        val slot: AudioDownloadSlot.Open = slotFrom(store)
        fillPart(slot, HALF_TRACK)

        val streaming: AudioCacheWriteHandle = requireNotNull(store.openWrite(stream()))
        streaming.write(ByteArray(8) { 1 })
        streaming.abandon()

        assertTrue("the download's bytes must survive a stream of the same track", slot.partFile.isFile)
        assertEquals(HALF_TRACK, slot.partFile.length())
    }

    @Test
    public fun `a download and a stream of one track write different partial files`() {
        // The mechanism behind the test above, stated directly. The two paths agree on where a
        // finished track lives and on nothing else: one shared partial meant a stream's rule -
        // delete whatever you find - was being applied to a download whose whole design says keep
        // it.
        val store: AudioCacheStoreWriter = writer()

        assertTrue(store.partFileFor(key).path.endsWith(AudioCacheStoreWriter.PART_SUFFIX))
        assertTrue(
            store.streamPartFileFor(key).path.endsWith(AudioCacheStoreWriter.STREAM_PART_SUFFIX),
        )
        assertFalse(
            "one name cannot obey both rules",
            store.partFileFor(key).path == store.streamPartFileFor(key).path,
        )
    }

    @Test
    public fun `a stream finishing after a download must not hand the bytes to the LRU`(): Unit =
        runTest {
            // The streaming handle was opened before the download's row existed, so its snapshot
            // says "no row, therefore unpinned". Writing that snapshot back at commit demoted a
            // downloaded track into the cached tier, where the next eviction pass is free to delete
            // it - a file the user explicitly asked to keep, removed by a policy REQUIREMENTS.md
            // says may never touch it.
            val store: AudioCacheStoreWriter = writer()
            val streaming: AudioCacheWriteHandle = requireNotNull(store.openWrite(stream()))

            val slot: AudioDownloadSlot.Open = slotFrom(store)
            fillPart(slot, TRACK_BYTES)
            assertTrue(slot.commit(TRACK_BYTES) is Outcome.Success)
            assertTrue(
                "a download lands in the tier that is never evicted",
                dao.rows.values.single().pinned,
            )

            streaming.write(ByteArray(TRACK_BYTES.toInt()) { 1 })
            streaming.commit()

            assertTrue(
                "a streamed write may join the downloaded tier but never take a row out of it",
                dao.rows.values.single().pinned,
            )
        }

    // ------------------------------------------------------------------- refusing to publish

    @Test
    public fun `a refused commit keeps the bytes for the next Range GET`(): Unit = runTest {
        // Refusing to publish and throwing the bytes away are different decisions, and only the
        // first belongs to a short file. Deleting turned one finalise failure into a fresh download
        // of the whole track, so a failure that repeats means the device retains nothing however
        // long the pull runs.
        val store: AudioCacheStoreWriter = writer()
        val slot: AudioDownloadSlot.Open = slotFrom(store)
        fillPart(slot, HALF_TRACK)

        val outcome: Outcome<CachedAudio> = slot.commit(TRACK_BYTES)

        assertTrue(outcome is Outcome.Failure)
        assertTrue("nothing may claim the partial bytes", dao.rows.isEmpty())
        assertFalse("a short file is never published", store.fileFor(key).exists())
        assertTrue("the bytes are the resume point and must survive", slot.partFile.isFile)
        assertEquals(HALF_TRACK, slot.partFile.length())
        // And the next attempt continues from them rather than starting again.
        assertEquals(HALF_TRACK, slotFrom(store).bytesOnDisk)
    }

    @Test
    public fun `a partial longer than the server's file is thrown away rather than published`(): Unit =
        runTest {
            // The one case where a partial is *proven* wrong rather than merely unfinished: it
            // cannot be resumed into anything correct, so it goes and the next attempt starts at
            // zero. It is also how a partial left behind by an older build, written under a
            // different rule, is caught before it can be published as music.
            val store: AudioCacheStoreWriter = writer()
            val slot: AudioDownloadSlot.Open = slotFrom(store)
            fillPart(slot, TRACK_BYTES + 16L)

            val outcome: Outcome<CachedAudio> = slot.commit(TRACK_BYTES)

            assertTrue(outcome is Outcome.Failure)
            assertFalse(slot.partFile.exists())
            assertFalse(store.fileFor(key).exists())
            assertTrue(dao.rows.isEmpty())
        }

    @Test
    public fun `a commit publishes the file and the row together`(): Unit = runTest {
        val store: AudioCacheStoreWriter = writer()
        val slot: AudioDownloadSlot.Open = slotFrom(store)
        fillPart(slot, TRACK_BYTES)

        val outcome: Outcome<CachedAudio> = slot.commit(TRACK_BYTES)

        assertTrue(outcome is Outcome.Success)
        assertFalse("the part file must not survive a commit", slot.partFile.exists())
        assertTrue(store.fileFor(key).isFile)
        assertEquals(TRACK_BYTES, store.fileFor(key).length())
        val row = dao.rows.values.single()
        assertTrue(row.complete)
        assertTrue(row.pinned)
        assertEquals(store.fileFor(key).path, row.filePath)
    }

    // ------------------------------------------------------- the index is a claim, not evidence

    @Test
    public fun `a row whose file has gone is downloaded again, not reported as on the device`(): Unit =
        runTest {
            // Rows outlive their files by design: eviction unlinks bytes before rows so that a crash
            // between the two costs a re-fetch rather than leaking gigabytes. A downloader that
            // believed the row alone would report the track as done for ever, mark the album
            // COMPLETE, and play nothing at all offline.
            dao.upsert(
                cacheRow(
                    track = key.trackNumber,
                    path = File(folder.root, "gone.audio").path,
                    complete = true,
                    pinned = true,
                    sourceFileId = "8801",
                    sizeBytes = TRACK_BYTES,
                ),
            )

            val slot: AudioDownloadSlot = writer().openDownload(key, fetchHandle, TRACK_BYTES)

            assertTrue(
                "a missing file means the track is not on the device",
                slot is AudioDownloadSlot.Open,
            )
        }

    @Test
    public fun `bytes already on the device join the downloaded tier`(): Unit = runTest {
        // Pinning an album you have been streaming is meant to be nearly free - the rows are
        // promoted rather than re-fetched. A row the streaming path writes *after* the pin was
        // recorded is not covered by the album-wide flip that pinning does, so the promotion has to
        // happen here or the track stays evictable while the album calls itself downloaded.
        val onDevice = File(folder.root, "already.audio")
        onDevice.writeBytes(ByteArray(TRACK_BYTES.toInt()) { 3 })
        dao.upsert(
            cacheRow(
                track = key.trackNumber,
                path = onDevice.path,
                complete = true,
                pinned = false,
                sourceFileId = "8801",
                sizeBytes = TRACK_BYTES,
            ),
        )

        val slot: AudioDownloadSlot = writer().openDownload(key, fetchHandle, TRACK_BYTES)

        assertEquals(AudioDownloadSlot.AlreadyOnDevice, slot)
        assertTrue(
            "bytes the user asked for are exempt from eviction from this moment on",
            dao.rows.values.single().pinned,
        )
    }

    // -------------------------------------------------------------------------- housekeeping

    @Test
    public fun `a partial is swept once its track is published, and only then`(): Unit = runTest {
        val store: AudioCacheStoreWriter = writer()
        val slot: AudioDownloadSlot.Open = slotFrom(store)
        fillPart(slot, TRACK_BYTES)
        slot.commit(TRACK_BYTES)

        // A partial left beside a published track: bytes nothing will ever read again.
        store.partFileFor(key).writeBytes(ByteArray(8) { 9 })
        // And one belonging to a track that is not on the device, which is a resume point.
        val pending = TrackKey(ReleaseGroupMbid(RG), discNumber = 1, trackNumber = 7)
        store.partFileFor(pending).writeBytes(ByteArray(8) { 9 })

        val removed: Int = store.sweepOrphanedParts(listOf(key, pending))

        assertEquals(1, removed)
        assertFalse(store.partFileFor(key).exists())
        assertTrue(
            "a partial whose track is not yet on the device is the next attempt's starting point",
            store.partFileFor(pending).isFile,
        )
        assertTrue("the published bytes are untouched", store.fileFor(key).isFile)
    }

    private companion object {
        const val TRACK_BYTES = 64L
        const val HALF_TRACK = 32L
        const val NOW = 1_700_000_000_000L
    }
}
