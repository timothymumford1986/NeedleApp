package app.needler.feature.library.common

import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.feature.library.SampleLibrary
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The formatting rules, tested without rendering anything.
 *
 * Two of these are the whole reason the functions exist: `duration` must not
 * lose a leading zero on the seconds, and every "unknown" must come back null
 * rather than as a confident zero.
 */
class LibraryFormatTest {

    @Test
    fun `durations keep the leading zero on seconds`() {
        assertEquals("3:59", LibraryFormat.duration(239_000L))
        assertEquals("3:20", LibraryFormat.duration(200_000L))
        assertEquals("0:07", LibraryFormat.duration(7_000L))
        assertEquals("1:00:00", LibraryFormat.duration(3_600_000L))
        assertEquals("1:04:11", LibraryFormat.duration(3_851_000L))
    }

    @Test
    fun `an unknown duration is not zero`() {
        assertNull(LibraryFormat.duration(null))
        assertNull(LibraryFormat.spokenDuration(null))
        assertNull(LibraryFormat.runningTime(null))
        assertNull(LibraryFormat.bytes(null))
        assertNull(LibraryFormat.quality(null))
    }

    @Test
    fun `spoken durations are words, not clock times`() {
        assertEquals("3 minutes 59 seconds", LibraryFormat.spokenDuration(239_000L))
        assertEquals("1 minute 1 second", LibraryFormat.spokenDuration(61_000L))
        assertEquals("0 seconds", LibraryFormat.spokenDuration(0L))
        assertEquals("1 hour 4 minutes 11 seconds", LibraryFormat.spokenDuration(3_851_000L))
    }

    @Test
    fun `the pack's library size renders as 42 GB`() {
        assertEquals("42 GB", LibraryFormat.bytes(42L * 1_073_741_824L))
    }

    @Test
    fun `small sizes keep a decimal and tiny ones stay in bytes`() {
        assertEquals("1.5 GB", LibraryFormat.bytes((1.5 * 1_073_741_824L).toLong()))
        assertEquals("512 KB", LibraryFormat.bytes(524_288L))
        assertEquals("800 B", LibraryFormat.bytes(800L))
        assertEquals("0 B", LibraryFormat.bytes(0L))
    }

    @Test
    fun `relative time is coarse and never counts seconds`() {
        val now = Instant.parse("2026-09-21T18:00:00Z")
        assertEquals("just now", LibraryFormat.relativeTime(now - 20.seconds, now))
        assertEquals("47m ago", LibraryFormat.relativeTime(now - 47.minutes, now))
        assertEquals("3h ago", LibraryFormat.relativeTime(now - 3.hours, now))
        assertEquals("2d ago", LibraryFormat.relativeTime(now - 2.days, now))
        assertEquals("over a month ago", LibraryFormat.relativeTime(now - 90.days, now))
    }

    @Test
    fun `a clock that has run backwards reads as just now, not as a negative`() {
        val now = Instant.parse("2026-09-21T18:00:00Z")
        assertEquals("just now", LibraryFormat.relativeTime(now + 5.minutes, now))
    }

    @Test
    fun `a bitrate is appended only to lossy formats`() {
        assertEquals("FLAC", LibraryFormat.quality(AudioQuality(AudioFormat.FLAC, 891)))
        assertEquals("MP3 320", LibraryFormat.quality(AudioQuality(AudioFormat.MP3, 320)))
        assertEquals("MP3 256", LibraryFormat.quality(AudioQuality(AudioFormat.MP3, 256)))
        assertEquals("MP3", LibraryFormat.quality(AudioQuality(AudioFormat.MP3, null)))
        assertNull(LibraryFormat.quality(AudioQuality(AudioFormat.UNKNOWN, 320)))
    }

    @Test
    fun `the library header is the pack's own line`() {
        assertEquals(
            "176 albums · 42 GB · last scan 47m ago",
            LibraryFormat.libraryHeaderLine(
                albumCount = 176,
                totalSizeBytes = 42L * 1_073_741_824L,
                lastScanAt = SampleLibrary.stats.lastScanAt,
                now = SampleLibrary.renderedAt,
            ),
        )
    }

    @Test
    fun `the header drops parts it does not know rather than faking them`() {
        assertEquals(
            "176 albums",
            LibraryFormat.libraryHeaderLine(
                albumCount = 176,
                totalSizeBytes = null,
                lastScanAt = null,
                now = SampleLibrary.renderedAt,
            ),
        )
    }

    @Test
    fun `an owned album's meta line ends with its format`() {
        assertEquals(
            "2024 · 8 tracks · 28 min · FLAC",
            LibraryFormat.albumMetaLine(SampleLibrary.submarine, includeRunningTime = true),
        )
    }

    @Test
    fun `an un-owned album's meta line says it is not in your library`() {
        assertEquals(
            "2023 · 19 tracks · not in your library",
            LibraryFormat.albumMetaLine(
                SampleLibrary.blackClassicalMusic,
                includeRunningTime = true,
            ),
        )
    }

    @Test
    fun `a track with the missing-file sentinel is not playable`() {
        val delivered = SampleLibrary.submarinePartialTracks.first { it.key.trackNumber == 1 }
        val missing = SampleLibrary.submarinePartialTracks.first { it.key.trackNumber == 4 }
        assertTrue(delivered.hasPlayableFile)
        assertFalse(missing.hasPlayableFile)
    }

    @Test
    fun `only a complete pin wears the on-device check`() {
        assertTrue(SampleLibrary.submarine.showsOnDeviceCheck)
        assertFalse(SampleLibrary.dragon.showsOnDeviceCheck)
        assertFalse(
            SampleLibrary.dragon.copy(
                state = AlbumState.Pinned(
                    download = app.needler.core.domain.model.OfflineDownloadState.Downloading(
                        tracksComplete = 3,
                        tracksTotal = 20,
                    ),
                ),
            ).showsOnDeviceCheck,
        )
    }
}
