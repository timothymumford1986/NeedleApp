// kotlinx.datetime.Instant is a typealias for kotlin.time.Instant from
// kotlinx-datetime 0.7.0 onwards, and the underlying type has carried an
// opt-in marker through several Kotlin releases. Opting in here costs a
// warning if it turns out not to be needed, and avoids a build break if it is.
@file:OptIn(ExperimentalTime::class)

package app.needler.feature.library.common

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.Track
import kotlin.math.roundToInt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Every string the library, album and artist screens render from a number.
 *
 * These are pure functions on purpose. Formatting is where a screen quietly
 * lies — a rounded byte count that never reaches the next unit, a duration that
 * loses its leading zero, a "0m ago" for something that happened four seconds
 * ago — and pure functions are the part of a screen that can be unit-tested
 * without rendering anything at all.
 *
 * Two rules run through all of them:
 *
 *  * **Unknown is not zero.** A null duration, size or year renders as nothing
 *    rather than as `0:00`, `0 B` or `0`. REQUIREMENTS.md makes the same point
 *    about the staleness check ("Unknown is not evidence") and it is just as
 *    true of a metadata line.
 *  * **Spoken text is separate from drawn text.** `3:59` is right on screen and
 *    wrong in TalkBack, which reads it as a time of day. Every duration
 *    therefore has a [spokenDuration] twin.
 */
internal object LibraryFormat {

    // ---- durations ----------------------------------------------------------

    /** `3:59`, or `1:04:11` past an hour. Null for an unknown duration. */
    fun duration(milliseconds: Long?): String? {
        val ms: Long = milliseconds ?: return null
        if (ms < 0L) return null
        val totalSeconds: Long = ms / 1_000L
        val seconds: Long = totalSeconds % 60L
        val minutes: Long = (totalSeconds / 60L) % 60L
        val hours: Long = totalSeconds / 3_600L
        return if (hours > 0L) {
            hours.toString() + ":" + pad(minutes) + ":" + pad(seconds)
        } else {
            minutes.toString() + ":" + pad(seconds)
        }
    }

    /**
     * The same duration as words, for TalkBack.
     *
     * `3:59` read literally is "three fifty-nine", which a screen reader
     * pronounces as a clock time. Announcing "3 minutes 59 seconds" is the
     * whole reason [duration] is not used as its own content description.
     */
    fun spokenDuration(milliseconds: Long?): String? {
        val ms: Long = milliseconds ?: return null
        if (ms < 0L) return null
        val totalSeconds: Long = ms / 1_000L
        val seconds: Long = totalSeconds % 60L
        val minutes: Long = (totalSeconds / 60L) % 60L
        val hours: Long = totalSeconds / 3_600L
        val parts: List<String> = buildList {
            if (hours > 0L) add(plural(hours, "hour"))
            if (minutes > 0L) add(plural(minutes, "minute"))
            if (seconds > 0L || isEmpty()) add(plural(seconds, "second"))
        }
        return parts.joinToString(separator = " ")
    }

    /** `28 min`, the album-length figure screen 11 draws. Null for an unknown total. */
    fun runningTime(milliseconds: Long?): String? {
        val ms: Long = milliseconds ?: return null
        if (ms <= 0L) return null
        val totalMinutes: Long = (ms / 60_000L).coerceAtLeast(1L)
        val hours: Long = totalMinutes / 60L
        val minutes: Long = totalMinutes % 60L
        return when {
            hours == 0L -> totalMinutes.toString() + " min"
            minutes == 0L -> hours.toString() + " hr"
            else -> hours.toString() + " hr " + minutes + " min"
        }
    }

    // ---- sizes --------------------------------------------------------------

    /**
     * `42 GB`, the library-size half of the header on screens 02, 09 and 13.
     *
     * Binary units under decimal names, which is what every Android storage
     * screen does and therefore what a user comparing this figure with
     * Settings will expect. A value under a kilobyte reads in bytes rather
     * than rounding to `0 KB`, because "removing this frees 0 KB" is precisely
     * the kind of line REQUIREMENTS.md says makes the storage screen
     * untrustworthy.
     */
    fun bytes(byteCount: Long?): String? {
        val value: Long = byteCount ?: return null
        if (value < 0L) return null
        if (value < UNIT) return value.toString() + " B"
        var scaled: Double = value.toDouble()
        var unitIndex = -1
        while (scaled >= UNIT && unitIndex < UNITS.lastIndex) {
            scaled /= UNIT
            unitIndex++
        }
        val unit: String = UNITS[unitIndex]
        // One decimal below 10 of a unit, none above: 1.4 GB is a useful
        // distinction, 42.3 GB is noise on a header line.
        return if (scaled < 10.0) {
            val tenths: Int = (scaled * 10.0).roundToInt()
            (tenths / 10).toString() + "." + (tenths % 10) + " " + unit
        } else {
            scaled.roundToInt().toString() + " " + unit
        }
    }

    // ---- relative time ------------------------------------------------------

    /**
     * `47m ago`, the "last scan" figure screen 09 draws in the header.
     *
     * Deliberately coarse. A scan time is a reassurance that the mirror is
     * fresh, not a measurement, and a ticking "47m 12s" would redraw the header
     * every second for no one's benefit.
     */
    fun relativeTime(then: Instant?, now: Instant): String? {
        val at: Instant = then ?: return null
        val seconds: Long = (now - at).inWholeSeconds
        return when {
            seconds < 0L -> "just now"
            seconds < 60L -> "just now"
            seconds < 3_600L -> (seconds / 60L).toString() + "m ago"
            seconds < 86_400L -> (seconds / 3_600L).toString() + "h ago"
            seconds < 2_592_000L -> (seconds / 86_400L).toString() + "d ago"
            else -> "over a month ago"
        }
    }

    // ---- quality ------------------------------------------------------------

    /**
     * `FLAC`, `MP3 320`, `MP3 256` — the badge on every row of screen 13.
     *
     * The bitrate is appended only for lossy formats. "FLAC 891" would be a
     * true number and a useless one: the point of the badge is whether this
     * copy is lossless, and for a lossy one, how lossy.
     */
    fun quality(quality: AudioQuality?): String? {
        val format: AudioFormat = quality?.format ?: return null
        if (format == AudioFormat.UNKNOWN) return null
        val name: String = formatName(format)
        val bitrate: Int? = quality.bitrateKbps
        return if (quality.isLossless || bitrate == null || bitrate <= 0) name else "$name $bitrate"
    }

    private fun formatName(format: AudioFormat): String = when (format) {
        AudioFormat.FLAC -> "FLAC"
        AudioFormat.MP3 -> "MP3"
        AudioFormat.AAC -> "AAC"
        AudioFormat.M4A -> "M4A"
        AudioFormat.OGG_VORBIS -> "Vorbis"
        AudioFormat.OPUS -> "Opus"
        AudioFormat.WAV -> "WAV"
        AudioFormat.ALAC -> "ALAC"
        AudioFormat.WMA -> "WMA"
        AudioFormat.UNKNOWN -> "Unknown"
    }

    // ---- composed lines -----------------------------------------------------

    /**
     * The library header's second line: `176 albums · 42 GB · last scan 47m ago`.
     *
     * Screens 02 and 13 draw the first two parts, screen 09 all three. Any part
     * whose figure is unknown is dropped rather than rendered as a placeholder,
     * so an offline first run says `176 albums` and nothing more.
     */
    fun libraryHeaderLine(
        albumCount: Int?,
        totalSizeBytes: Long?,
        lastScanAt: Instant?,
        now: Instant,
    ): String {
        val parts: List<String> = buildList {
            if (albumCount != null && albumCount >= 0) add(plural(albumCount.toLong(), "album"))
            bytes(totalSizeBytes)?.let { add(it) }
            relativeTime(lastScanAt, now)?.let { add("last scan $it") }
        }
        return parts.joinToString(separator = " · ")
    }

    /**
     * The album detail meta line: `2024 · 8 tracks · 28 min · FLAC`, or
     * `2023 · 19 tracks · not in your library` for something you do not own.
     *
     * Screen 05 ends the line with "not in your library" rather than a format,
     * and that is the honest thing to say: an un-owned album has no format,
     * because no file exists anywhere yet.
     */
    fun albumMetaLine(album: Album, includeRunningTime: Boolean): String {
        val parts: List<String> = buildList {
            album.year?.let { add(it.toString()) }
            album.trackCount?.let { add(plural(it.toLong(), "track")) }
            if (includeRunningTime) runningTime(album.durationMs)?.let { add(it) }
            if (album.isOwned) {
                quality(album.quality)?.let { add(it) }
            } else {
                add(NOT_IN_LIBRARY)
            }
        }
        return parts.joinToString(separator = " · ")
    }

    /** `The Marías · Submarine`, the subtitle on an album row in a list. */
    fun albumRowSubtitle(album: Album): String {
        val parts: List<String> = buildList {
            add(album.artistName)
            album.year?.let { add(it.toString()) }
        }
        return parts.joinToString(separator = " · ")
    }

    /** `Khruangbin · 4:02`, the subtitle on a song row in the Songs tab. */
    fun songRowSubtitle(track: Track): String {
        val parts: List<String> = buildList {
            if (track.artistName.isNotBlank()) add(track.artistName)
            track.albumTitle?.let { add(it) }
        }
        return parts.joinToString(separator = " · ")
    }

    /** `12 albums`, the subtitle on an artist row. */
    fun artistRowSubtitle(ownedAlbumCount: Int, catalogueAlbumCount: Int?): String {
        val owned: String = plural(ownedAlbumCount.toLong(), "album")
        val catalogue: Int = catalogueAlbumCount ?: return owned
        val unowned: Int = catalogue - ownedAlbumCount
        return if (unowned > 0) "$owned · $unowned more to pull" else owned
    }

    /** `album`/`albums`, `1 track`/`8 tracks`. */
    fun plural(count: Long, noun: String): String =
        if (count == 1L) "$count $noun" else "$count ${noun}s"

    private fun pad(value: Long): String = if (value < 10L) "0$value" else value.toString()

    /** The phrase screen 05 ends its meta line with. */
    const val NOT_IN_LIBRARY: String = "not in your library"

    private const val UNIT: Long = 1_024L
    private val UNITS: List<String> = listOf("KB", "MB", "GB", "TB", "PB")
}

/**
 * Whether this track has a file behind it on the server.
 *
 * REQUIREMENTS.md "Partial content is a normal state": a part-delivered pull
 * leaves an album that is *in library* with some of its tracks missing, and
 * those tracks are listed in their right positions and greyed rather than
 * hidden. There is nothing to stream for them, so the screen must be able to
 * tell them apart before it offers a tap that cannot work.
 *
 * **The domain model carries no flag for this**, so the check is made against
 * the sentinel the data layer writes into [app.needler.core.domain.model.FileId]
 * when a mirror row has no `file_id` — `FileId` refuses to be blank, so the
 * mapper substitutes a placeholder. Matching a magic string across a module
 * boundary is not a good way to model availability, and it is written down in
 * the handover notes: `Track` wants an explicit `isPlayable`, or a nullable
 * `fetch`, in `:core:domain`. Until it has one, this is the only signal a
 * feature module can see.
 */
internal val Track.hasPlayableFile: Boolean
    get() = fetch.fileId.value != MISSING_FILE_ID

/** The placeholder `:core:data` writes when a mirror row has no `file_id`. */
internal const val MISSING_FILE_ID: String = "unavailable"

/** True when this album is complete on this device, so its artwork carries the green check. */
internal val Album.showsOnDeviceCheck: Boolean
    get() = state is AlbumState.Pinned && isFullyOnDevice
