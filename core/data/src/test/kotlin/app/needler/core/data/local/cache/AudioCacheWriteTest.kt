package app.needler.core.data.local.cache

import app.needler.core.data.local.dao.AudioCacheDao
import app.needler.core.data.local.entity.AudioCacheEntity
import app.needler.core.data.local.projection.CacheUsageRow
import app.needler.core.domain.cache.AudioCacheWriteHandle
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.CachedAudio
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.PlayableSource
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The streamed-audio write path: what may be retained, and the commit/abandon rules.
 *
 * The rule these tests exist for is the abandon rule. A half-written file published as a complete
 * cached track is the worst failure this store has, because it is silent: months later a play finds
 * a local file, plays it, and stops two minutes into a four-minute song with nothing anywhere
 * reporting an error. The user concludes their music is corrupt. So an incomplete write must leave
 * *nothing* behind - no file, and above all no row claiming a complete track.
 *
 * The retention rules are the other half, and they are the reason Media3's `CacheDataSource` is not
 * used: it would keep whatever passed through it, including a 320 kbps transcode standing in for a
 * FLAC, with no fingerprint, no pinned-download exemption and a fixed byte cap in place of the
 * device's free-space floor.
 */
public class AudioCacheWriteTest {

    @get:Rule
    public val folder: TemporaryFolder = TemporaryFolder()

    private val dao: AudioCacheDao = mockk(relaxed = true)

    private val key = TrackKey(ReleaseGroupMbid("rg-1"), discNumber = 1, trackNumber = 4)

    private val fetchHandle = TrackFetchHandle(
        fileId = FileId("9001"),
        sizeBytes = 8L,
        durationMs = 240_000L,
        format = AudioFormat.FLAC,
        bitrateKbps = null,
    )

    private val bytes: ByteArray = ByteArray(8) { index -> index.toByte() }

    private fun stream(
        cacheWhileStreaming: Boolean = true,
        handle: TrackFetchHandle = fetchHandle,
        format: StreamFormat = StreamFormat.Original,
    ): PlayableSource.Stream = PlayableSource.Stream(
        key = key,
        fetchHandle = handle,
        format = format,
        cacheWhileStreaming = cacheWhileStreaming,
    )

    /**
     * A writer over a device with plenty of room. Deliberately built from the real [CacheIndex] and
     * [EvictionPlanner]: the free-space rule has one home, and a writer that re-implemented it would
     * be the second.
     */
    private fun writer(
        freeBytes: Long = 100_000L,
        floorBytes: Long = 1_000L,
        unpinnedBytes: Long = 0L,
        directory: File = folder.root,
    ): AudioCacheStoreWriter {
        coEvery { dao.getUsage() } returns CacheUsageRow(
            pinnedBytes = 0L,
            unpinnedBytes = unpinnedBytes,
            pinnedTracks = 0,
            unpinnedTracks = 0,
        )
        coEvery { dao.getEvictionCandidates(any()) } returns emptyList()
        return AudioCacheStoreWriter(
            audioCacheDao = dao,
            cacheIndex = CacheIndex(
                audioCacheDao = dao,
                deviceFreeSpace = DeviceFreeSpace.of(freeBytes = freeBytes, floorBytes = floorBytes),
            ),
            audioDirectory = directory,
            nowMillis = { FIXED_NOW },
        )
    }

    private fun cachedRow(fileId: String, complete: Boolean = true): AudioCacheEntity = AudioCacheEntity(
        releaseGroupMbid = key.releaseGroupMbid.value,
        discNo = key.discNumber,
        trackNo = key.trackNumber,
        recordingMbid = null,
        filePath = File(folder.root, "existing.audio").path,
        sizeBytes = 8L,
        complete = complete,
        pinned = true,
        lastPlayedAt = 42L,
        playCount = 3,
        downloadedAt = 7L,
        sourceFileId = fileId,
        sourceSizeBytes = 8L,
        sourceDurationMs = 240_000L,
        sourceFormat = "flac",
        sourceBitrateKbps = null,
    )

    // ---------------------------------------------------------------- what may be retained

    @Test
    public fun `transcoded bytes are never retained`(): Unit = runTest {
        // The single most important refusal here. Retaining an MP3 320 rendering would make it the
        // permanent offline copy of a track the user owns as FLAC, and nothing would ever say so.
        coEvery { dao.get(any(), any(), any()) } returns null

        val handle: AudioCacheWriteHandle? = writer().openWrite(
            stream(cacheWhileStreaming = false, format = StreamFormat.Transcoded.Mp3_320),
        )

        assertNull(handle)
    }

    @Test
    public fun `a stream of unknown length is not retained`(): Unit = runTest {
        // Without a size there is no way to ask whether the bytes fit above the floor *before*
        // writing them, and a floor discovered after the write is not a floor.
        coEvery { dao.get(any(), any(), any()) } returns null

        val handle: AudioCacheWriteHandle? = writer().openWrite(
            stream(handle = fetchHandle.copy(sizeBytes = null)),
        )

        assertNull(handle)
    }

    @Test
    public fun `a write that cannot fit above the free-space floor is skipped, not forced in`(): Unit =
        runTest {
            // The device is already below its floor and the cached tier has nothing to give up.
            // The track still streams and plays; it is simply not kept, and nothing extra is
            // evicted for it - evicting further would cost recently played music and still not fit.
            coEvery { dao.get(any(), any(), any()) } returns null

            val handle: AudioCacheWriteHandle? =
                writer(freeBytes = 500L, floorBytes = 1_000L).openWrite(stream())

            assertNull(handle)
        }

    @Test
    public fun `bytes already on the device from the same file are not fetched again`(): Unit = runTest {
        coEvery { dao.get(any(), any(), any()) } returns cachedRow(fileId = "9001")

        assertNull(writer().openWrite(stream()))
    }

    @Test
    public fun `bytes on the device from a replaced file are written over`(): Unit = runTest {
        // The server upgraded the file in place. The cached copy is the older, worse one, so this
        // write is the replacement rather than a duplicate.
        coEvery { dao.get(any(), any(), any()) } returns cachedRow(fileId = "8000")

        assertNotNull(writer().openWrite(stream()))
    }

    // ---------------------------------------------------------------- commit

    @Test
    public fun `a complete write commits one row describing the bytes on disk`(): Unit = runTest {
        coEvery { dao.get(any(), any(), any()) } returns null
        val row = slot<AudioCacheEntity>()
        coEvery { dao.upsert(capture(row)) } returns Unit

        val handle: AudioCacheWriteHandle = writer().openWrite(stream())!!
        handle.write(bytes)
        val outcome: Outcome<CachedAudio> = handle.commit()

        val cached: CachedAudio = (outcome as Outcome.Success).value
        assertEquals(key, cached.key)
        assertEquals(8L, cached.sizeOnDiskBytes)
        assertTrue(cached.isComplete)
        assertTrue(File(cached.filePath).exists())
        assertEquals(8L, File(cached.filePath).length())
        // Nothing is left in progress, and the published name is derived from the track key so a
        // later retry reclaims it rather than adding a second copy.
        assertFalse(partFile().exists())

        assertTrue(row.captured.complete)
        assertEquals(8L, row.captured.sizeBytes)
        // The fingerprint is the file *as fetched*, which is the whole basis of the staleness check:
        // sync overwrites the mirror, so a comparison against the mirror compares it with itself.
        assertEquals("9001", row.captured.sourceFileId)
        assertEquals(8L, row.captured.sourceSizeBytes)
        assertEquals(240_000L, row.captured.sourceDurationMs)
        assertEquals("flac", row.captured.sourceFormat)
        assertEquals(FIXED_NOW, row.captured.downloadedAt)
        // A streamed write never changes tier. Pinning is the user's decision, made elsewhere.
        assertFalse(row.captured.pinned)
    }

    @Test
    public fun `a pinned row keeps its exemption when its bytes are rewritten`(): Unit = runTest {
        coEvery { dao.get(any(), any(), any()) } returns cachedRow(fileId = "8000")
        val row = slot<AudioCacheEntity>()
        coEvery { dao.upsert(capture(row)) } returns Unit

        val handle: AudioCacheWriteHandle = writer().openWrite(stream())!!
        handle.write(bytes)
        handle.commit()

        // Losing the pin here would quietly hand a downloaded album to the LRU pass.
        assertTrue(row.captured.pinned)
        assertEquals(3, row.captured.playCount)
    }

    // ---------------------------------------------------------------- abandon

    @Test
    public fun `an abandoned write leaves no file and no row`(): Unit = runTest {
        coEvery { dao.get(any(), any(), any()) } returns null

        val handle: AudioCacheWriteHandle = writer().openWrite(stream())!!
        handle.write(bytes, offset = 0, length = 3)
        handle.abandon()

        assertFalse(partFile().exists())
        assertFalse(publishedFile().exists())
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    public fun `a truncated commit is refused and behaves exactly as an abandon`(): Unit = runTest {
        // A short body is a truncated fetch however cleanly the connection closed. This is the
        // failure that must never be published: it produces silent early-stopping playback later.
        coEvery { dao.get(any(), any(), any()) } returns null

        val handle: AudioCacheWriteHandle = writer().openWrite(stream())!!
        handle.write(bytes, offset = 0, length = 5)
        val outcome: Outcome<CachedAudio> = handle.commit()

        assertTrue((outcome as Outcome.Failure).error is NeedlerError.ProtocolViolation)
        assertFalse(partFile().exists())
        assertFalse(publishedFile().exists())
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    public fun `a commit with no bytes at all is refused`(): Unit = runTest {
        coEvery { dao.get(any(), any(), any()) } returns null

        val handle: AudioCacheWriteHandle = writer().openWrite(stream())!!
        val outcome: Outcome<CachedAudio> = handle.commit()

        assertTrue(outcome is Outcome.Failure)
        assertFalse(publishedFile().exists())
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    public fun `a commit after an abandon cannot resurrect the file`(): Unit = runTest {
        coEvery { dao.get(any(), any(), any()) } returns null

        val handle: AudioCacheWriteHandle = writer().openWrite(stream())!!
        handle.write(bytes)
        handle.abandon()
        val outcome: Outcome<CachedAudio> = handle.commit()

        assertEquals(NeedlerError.Cancelled, (outcome as Outcome.Failure).error)
        assertFalse(publishedFile().exists())
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    public fun `writes after an abandon are ignored rather than failing playback`(): Unit = runTest {
        // A cache write must never be able to fail the playback it is riding along with, so a late
        // byte from a loading thread is dropped in silence rather than throwing into the player.
        coEvery { dao.get(any(), any(), any()) } returns null

        val handle: AudioCacheWriteHandle = writer().openWrite(stream())!!
        handle.abandon()
        handle.write(bytes)

        assertEquals(0L, handle.bytesWritten)
        assertFalse(partFile().exists())
    }

    @Test
    public fun `abandoning after a commit leaves the committed bytes alone`(): Unit = runTest {
        // The player abandons handles on every track change, including ones that have just
        // committed. Deleting the file there would undo the write it had just finished.
        coEvery { dao.get(any(), any(), any()) } returns null

        val handle: AudioCacheWriteHandle = writer().openWrite(stream())!!
        handle.write(bytes)
        handle.commit()
        handle.abandon()

        assertTrue(publishedFile().exists())
    }

    // ---------------------------------------------------------------- format token

    @Test
    public fun `an unknown format is stored as null, because unknown is not evidence`(): Unit {
        // A literal "unknown" on one side and a real token on the other reads as "changed", which
        // would re-download the user's whole offline library the day a server stopped reporting
        // formats.
        assertNull(AudioCacheStoreWriter.formatToken(null))
        assertNull(AudioCacheStoreWriter.formatToken(AudioFormat.UNKNOWN))
        assertEquals("flac", AudioCacheStoreWriter.formatToken(AudioFormat.FLAC))
        assertEquals("mp3", AudioCacheStoreWriter.formatToken(AudioFormat.MP3))
        assertEquals("ogg", AudioCacheStoreWriter.formatToken(AudioFormat.OGG_VORBIS))
    }

    /**
     * The **stream's** partial, which is not the download's.
     *
     * The two are separate files on purpose - a stream deletes its leftover and a download resumes
     * from it, and one name cannot obey both rules - so these tests have to name the streaming one
     * explicitly or they would be asserting about a file this path never touches.
     */
    private fun partFile(): File = writer().streamPartFileFor(key)

    private fun publishedFile(): File = writer().fileFor(key)

    private companion object {
        private const val FIXED_NOW: Long = 1_700_000_000_000L
    }
}
