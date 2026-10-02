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

    // ---- names the server did not send --------------------------------------

    /**
     * The device fault this section exists for.
     *
     * The album screen built six accessibility labels by concatenating `album.title`, and
     * `Album.title` can be blank: `ReleaseItemDto.title` is nullable and
     * `CatalogueMappers.album` maps it with `.orEmpty()`. With a blank title those read out as
     * "Cancel the pull of ", "Play ", "Remove  from this device" - a dangling preposition and
     * no identity at all, which is exactly what the audit found by reading the accessibility
     * tree.
     *
     * It shipped because **every existing fixture supplied a title**. These do not.
     */
    @Test
    fun `a title to draw falls back to a name, never to nothing`() {
        assertEquals("Untitled album", LibraryFormat.albumTitle(null))
        assertEquals("Untitled album", LibraryFormat.albumTitle(""))
        assertEquals("Untitled album", LibraryFormat.albumTitle("   "))
        assertEquals("Submarine", LibraryFormat.albumTitle("Submarine"))
    }

    @Test
    fun `a title inside a sentence falls back to a phrase`() {
        assertEquals("this album", LibraryFormat.albumLabel(null))
        assertEquals("this album", LibraryFormat.albumLabel(""))
        assertEquals("this album", LibraryFormat.albumLabel(" \t "))
        assertEquals("Submarine", LibraryFormat.albumLabel("Submarine"))
    }

    /** Trimmed first, so `isEmpty` and `isBlank` cannot disagree about the same string. */
    @Test
    fun `a title is trimmed before it is judged and before it is returned`() {
        assertEquals("Submarine", LibraryFormat.albumTitle("  Submarine  "))
        assertEquals("Submarine", LibraryFormat.albumLabel("  Submarine  "))
        assertEquals("The Marias", LibraryFormat.artistName("  The Marias "))
        assertEquals("Sienna", LibraryFormat.trackTitle("\tSienna\n"))
    }

    @Test
    fun `an artist and a track get the same treatment`() {
        assertEquals("Unknown artist", LibraryFormat.artistName(""))
        assertEquals("this artist", LibraryFormat.artistLabel("  "))
        assertEquals("Untitled track", LibraryFormat.trackTitle(null))
        assertEquals("this track", LibraryFormat.trackLabel(""))
    }

    /**
     * No label built from any nameless fixture is blank, untrimmed, or ends in a preposition.
     *
     * A sweep rather than a list of cases, because the fault was never one label: it was six,
     * and the seventh would have been added without a guard. Every sentence the album screen
     * builds is reproduced here in the shape it is built in.
     */
    @Test
    fun `no label built from a nameless album is blank, untrimmed or dangling`() {
        val danglers: List<String> = listOf("of", "of ", "to", "by", "Play", "Remove", "Pull")
        SampleLibrary.namelessAlbums.forEach { album ->
            val label: String = LibraryFormat.albumLabel(album.title)
            val sentences: List<String> = listOf(
                "Play " + label,
                "Shuffle " + label,
                "Pull " + label,
                "Cancel the pull of " + label,
                "Retry the pull of " + label,
                "On device. Remove " + label + " from this device",
                "Pull local. Download " + label + " to this device",
                "Go to " + LibraryFormat.artistLabel(album.artistName),
            )
            sentences.forEach { sentence ->
                assertTrue("blank: '" + sentence + "'", sentence.isNotBlank())
                assertEquals("untrimmed: '" + sentence + "'", sentence.trim(), sentence)
                danglers.forEach { tail ->
                    assertFalse(
                        "ends in '" + tail + "': '" + sentence + "'",
                        sentence.trimEnd().endsWith(" " + tail) || sentence.trimEnd() == tail,
                    )
                }
            }
        }
    }

    @Test
    fun `a row subtitle names an unknown artist rather than starting with a separator`() {
        SampleLibrary.namelessAlbums.forEach { album ->
            val subtitle: String = LibraryFormat.albumRowSubtitle(album)
            assertTrue(subtitle.isNotBlank())
            assertFalse("starts with a separator: '" + subtitle + "'", subtitle.startsWith(" ·"))
            assertTrue(subtitle.startsWith("Unknown artist"))
        }
    }

    /**
     * A song row is the one place a blank part is dropped rather than replaced.
     *
     * Its subtitle is optional context beside a title that is drawn anyway, so an empty one
     * costs nothing - where "Unknown artist" on every row of a badly-tagged library would be
     * noise on every line.
     */
    @Test
    fun `a song row subtitle drops what it does not know`() {
        assertEquals("", LibraryFormat.songRowSubtitle(SampleLibrary.untitledTracks.first()))
    }

    // ---- the format label, which no longer rests on hue ---------------------

    /**
     * REQUIREMENTS.md's palette table lists the positive green's uses as including "the FLAC
     * badge", and on screen 13 the same green also means on-device - so lossless had no channel
     * but hue, a WCAG 1.4.1 failure. The chip border carries it on screen; these are the words
     * that carry it to a screen reader, because a border is nothing at all to one.
     */
    @Test
    fun `the spoken format label says lossless or lossy in words`() {
        assertEquals(
            "FLAC, lossless",
            albumFormatSpokenLabel(AudioQuality(AudioFormat.FLAC, null), onDevice = false),
        )
        assertEquals(
            "MP3 320, lossy",
            albumFormatSpokenLabel(AudioQuality(AudioFormat.MP3, 320), onDevice = false),
        )
        assertEquals(
            "FLAC, lossless, on device",
            albumFormatSpokenLabel(AudioQuality(AudioFormat.FLAC, null), onDevice = true),
        )
    }

    @Test
    fun `an unknown format has nothing to say and says nothing`() {
        assertNull(albumFormatSpokenLabel(null, onDevice = false))
        assertNull(albumFormatSpokenLabel(AudioQuality.Unknown, onDevice = true))
        assertNull(albumFormatSpokenLabel(AudioQuality(AudioFormat.UNKNOWN, null), onDevice = false))
    }

    // ---- the crate line ------------------------------------------------------

    /**
     * The two figures REQUIREMENTS.md "Queue" gives the crate - "a count and total duration" -
     * said on the screen the user added from, in the product's own vocabulary. "Queue" never
     * appears in anything a user reads.
     */
    @Test
    fun `the crate line carries the count and the total duration`() {
        assertEquals("10 in the crate · 35 min", LibraryFormat.crateLine(10, 2_120_000L))
        // 239 seconds is three whole minutes: the total truncates like every other running
        // time in this object, rather than rounding a crate up to a length it does not have.
        assertEquals("1 in the crate · 3 min", LibraryFormat.crateLine(1, 239_000L))
        assertEquals("40 in the crate · 2 hr 30 min", LibraryFormat.crateLine(40, 9_000_000L))
    }

    /**
     * An empty crate has no line, and a crate of unknown length keeps its count.
     *
     * A total of `0 min` would be a claim about music the mirror has no durations for, which is
     * the one case `runningTime` returns null for.
     */
    @Test
    fun `an empty crate has no line and an unknown total is dropped`() {
        assertNull(LibraryFormat.crateLine(0, 0L))
        assertNull(LibraryFormat.crateLine(-1, 10_000L))
        assertEquals("3 in the crate", LibraryFormat.crateLine(3, 0L))
    }
}
