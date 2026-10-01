package app.needler.core.data.local.cache

import app.needler.core.data.fake.FakeAudioCacheDao
import app.needler.core.data.fake.RG
import app.needler.core.data.local.entity.AudioCacheEntity
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A whole track through the streaming write path, from the first read to the figure screen 12 shows.
 *
 * ## Why this test exists, when the parts were already tested
 *
 * Every rule this path obeys had a passing test and the feature did not work: a device played three
 * tracks to completion and the Storage section read `Cached while listening 0 B`. The tests were
 * about the pieces - "a truncated commit is refused", "a short body is abandoned", "the planner skips
 * an incoming write that cannot fit" - and each piece was right. What nothing asserted was the
 * sentence the user cares about: **bytes read by the player become a row the app can find and a
 * number the Storage screen can show.**
 *
 * That gap is the reason a silent refusal could survive. Every way this path declines to keep a track
 * is deliberately quiet - REQUIREMENTS.md "Offline and caching" requires it, because a cache write may
 * never fail the playback it rides along with - so a wrong refusal is indistinguishable from a right
 * one from the outside. The only test that can catch it is one that insists a *good* write lands.
 *
 * So these tests drive bytes through the real [AudioCacheStoreWriter] over the real [CacheIndex] and
 * the real [EvictionPlanner], in the chunks Media3 hands over, and then read the accounting back
 * through the same query the Storage screen uses. The one thing they cannot include is Media3 itself:
 * `NeedlerAudioDataSource` needs the player, so the half of the path that decides *when* to commit
 * lives in `WriteThroughSinkTest` next door.
 *
 * ## What the numbers mean
 *
 * [TRACK_BYTES] is deliberately not a multiple of [CHUNK]: a reader's last read is short, and a path
 * that only ever saw whole chunks would not notice an off-by-one in the final one. The mirror's
 * recorded size is deliberately *wrong* in [MIRROR_SIZE], because that is the state a server-side
 * quality upgrade leaves the mirror in between the upgrade and the next sync, and the write must be
 * held to what the response declared rather than to what the mirror remembers.
 */
public class StreamRetentionPathTest {

    @get:Rule
    public val folder: TemporaryFolder = TemporaryFolder()

    private val dao = FakeAudioCacheDao()

    private val key = TrackKey(ReleaseGroupMbid(RG), discNumber = 2, trackNumber = 9)

    /**
     * The handle the resolver passes, carrying the size **the mirror** recorded at the last sync.
     *
     * It disagrees with the bytes the server is now serving on purpose. See the class comment.
     */
    private val fetchHandle = TrackFetchHandle(
        fileId = FileId("7700"),
        sizeBytes = MIRROR_SIZE,
        durationMs = 245_000L,
        format = AudioFormat.MP3,
        bitrateKbps = 320,
    )

    /** The bytes on the wire. Content rather than length alone, so a lost or doubled chunk shows up. */
    private val track: ByteArray = ByteArray(TRACK_BYTES.toInt()) { index -> (index % 251).toByte() }

    /** The real index over the real planner: the free-space rule has one home and this is not it. */
    private val cacheIndex: CacheIndex by lazy {
        CacheIndex(
            audioCacheDao = dao,
            deviceFreeSpace = DeviceFreeSpace.of(freeBytes = FREE_BYTES, floorBytes = FLOOR_BYTES),
        )
    }

    /**
     * Lazily, because [TemporaryFolder] has no root until JUnit has applied the rule, which happens
     * after this instance is built. Reading it in an initialiser throws.
     */
    private val store: AudioCacheStoreWriter by lazy {
        AudioCacheStoreWriter(
            audioCacheDao = dao,
            cacheIndex = cacheIndex,
            audioDirectory = folder.root,
            nowMillis = { NOW },
        )
    }

    private fun stream(): PlayableSource.Stream = PlayableSource.Stream(
        key = key,
        fetchHandle = fetchHandle,
        format = StreamFormat.Original,
        cacheWhileStreaming = true,
    )

    /**
     * Feeds [upTo] bytes through the handle the way the data source does: a buffer and an offset into
     * it, a short final read, and nothing else.
     */
    private suspend fun readThrough(handle: AudioCacheWriteHandle, upTo: Long = TRACK_BYTES) {
        var offset = 0
        while (offset < upTo) {
            val length: Int = minOf(CHUNK.toLong(), upTo - offset).toInt()
            handle.write(track, offset, length)
            offset += length
        }
    }

    @Test
    public fun `a track read to the end becomes a row, a file and a usage figure`(): Unit = runTest {
        val handle: AudioCacheWriteHandle =
            requireNotNull(store.openWrite(stream(), declaredLengthBytes = TRACK_BYTES))

        readThrough(handle)
        val outcome: Outcome<CachedAudio> = handle.commit()

        val cached: CachedAudio = (outcome as Outcome.Success).value
        assertTrue(cached.isComplete)
        assertEquals(TRACK_BYTES, cached.sizeOnDiskBytes)

        // The bytes, not just their count: a path that dropped or repeated a chunk would still add up.
        assertArrayEquals(track, File(cached.filePath).readBytes())
        assertEquals(store.fileFor(key).path, cached.filePath)
        assertFalse("nothing is left in progress", store.streamPartFileFor(key).exists())

        // The row, as the app will find it on the next play.
        val row: AudioCacheEntity = dao.rows.values.single()
        assertTrue(row.complete)
        assertEquals(TRACK_BYTES, row.sizeBytes)
        assertFalse("streaming never puts a track in the downloaded tier", row.pinned)
        assertEquals(NOW, row.lastPlayedAt)

        // And the figure screen 12 reads. This is the assertion the device disagreed with: three
        // tracks played to completion and "Cached while listening" still said 0 B.
        val usage: CacheUsage = cacheIndex.usage()
        assertEquals(TRACK_BYTES, usage.unpinnedBytes)
        assertEquals(0L, usage.pinnedBytes)
        assertEquals(1, usage.trackCount)
    }

    @Test
    public fun `a track the player moved off part way leaves nothing behind`(): Unit = runTest {
        // The other half of the same rule, and the reason the rule is worth the trouble: a truncated
        // file published as a complete track plays and stops halfway through, months later, with
        // nothing anywhere reporting an error.
        val handle: AudioCacheWriteHandle =
            requireNotNull(store.openWrite(stream(), declaredLengthBytes = TRACK_BYTES))

        readThrough(handle, upTo = TRACK_BYTES / 3)
        handle.abandon()

        assertFalse(store.streamPartFileFor(key).exists())
        assertFalse(store.fileFor(key).exists())
        assertTrue(dao.rows.isEmpty())
        assertEquals(0L, cacheIndex.usage().totalBytes)
    }

    @Test
    public fun `a second play of a retained track is not written again`(): Unit = runTest {
        // What makes the store cheap to live with: once a track is on the device the next play reads
        // the file, and the write path declines rather than rewriting identical bytes.
        val first: AudioCacheWriteHandle =
            requireNotNull(store.openWrite(stream(), declaredLengthBytes = TRACK_BYTES))
        readThrough(first)
        assertTrue(first.commit() is Outcome.Success)

        assertNull(store.openWrite(stream(), declaredLengthBytes = TRACK_BYTES))
        assertEquals(1, dao.rows.size)
        assertEquals(TRACK_BYTES, cacheIndex.usage().unpinnedBytes)
    }

    private companion object {

        /** Not a multiple of [CHUNK], so the final read is a short one. */
        private const val TRACK_BYTES: Long = 200_003L

        /**
         * What the mirror recorded at the last sync: eleven bytes out, as a file replaced in place
         * leaves it. Holding the write to this number is what discarded every stream on the device.
         */
        private const val MIRROR_SIZE: Long = 199_992L

        /** Media3 hands bytes over in blocks of this order; the exact figure does not matter. */
        private const val CHUNK: Int = 8_192

        private const val FREE_BYTES: Long = 8L * 1_024L * 1_024L * 1_024L
        private const val FLOOR_BYTES: Long = 2L * 1_024L * 1_024L * 1_024L

        private const val NOW: Long = 1_700_000_000_000L
    }
}
