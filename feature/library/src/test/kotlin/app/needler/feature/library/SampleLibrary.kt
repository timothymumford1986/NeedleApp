package app.needler.feature.library

import app.needler.core.domain.NameKeys
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.StatsSource
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import kotlin.time.Instant

/**
 * The design pack's own records, typed in.
 *
 * Screens 02, 09 and 13 all draw the same ten albums, screen 04 the same eight
 * tracks and screen 05 the same un-owned release. Using those exact titles,
 * artists, years, formats and on-device marks is what makes a rendered PNG
 * directly comparable with `design/png/` — a screenshot of invented data can
 * only be checked for "does it look plausible", which is not a test.
 *
 * Artwork is an [ArtworkRef] with no resolver behind it in a unit test, so
 * every rendered tile is the placeholder tint. That is the one deliberate
 * difference from the pack, and it is a consequence of artwork URLs not being
 * resolvable in any module below `:app`.
 */
internal object SampleLibrary {

    // ---- identifiers --------------------------------------------------------

    fun albumMbid(slug: String): ReleaseGroupMbid = ReleaseGroupMbid("rg-$slug")

    fun artistMbid(slug: String): ArtistMbid = ArtistMbid("ar-$slug")

    // ---- builders -----------------------------------------------------------

    fun album(
        slug: String,
        title: String,
        artistName: String,
        artistSlug: String = slug,
        year: Int? = null,
        trackCount: Int? = null,
        durationMs: Long? = null,
        state: AlbumState = AlbumState.Owned,
        format: AudioFormat? = AudioFormat.FLAC,
        bitrateKbps: Int? = null,
        sizeBytes: Long? = null,
        qualityPolicySummary: String? = null,
    ): Album = Album(
        releaseGroupMbid = albumMbid(slug),
        title = title,
        artistName = artistName,
        artistMbid = artistMbid(artistSlug),
        state = state,
        year = year,
        trackCount = trackCount,
        durationMs = durationMs,
        quality = AudioQuality(format = format, bitrateKbps = bitrateKbps),
        sizeBytes = sizeBytes,
        artwork = ArtworkRef.Owned(albumMbid(slug).subsonicAlbumId),
        qualityPolicySummary = qualityPolicySummary,
    )

    fun track(
        albumSlug: String,
        number: Int,
        title: String,
        artistName: String,
        durationMs: Long,
        albumTitle: String? = null,
        available: Boolean = true,
        format: AudioFormat? = AudioFormat.FLAC,
    ): Track = Track(
        key = TrackKey(albumMbid(albumSlug), discNumber = 1, trackNumber = number),
        title = title,
        artistName = artistName,
        albumTitle = albumTitle,
        durationMs = durationMs,
        fetch = TrackFetchHandle(
            // "unavailable" is the sentinel :core:data writes when a mirror row
            // has no file behind it, which is exactly what a part-delivered
            // pull leaves. See `Track.hasPlayableFile`.
            fileId = FileId(if (available) "f-$albumSlug-$number" else "unavailable"),
            sizeBytes = if (available) 31_000_000L else null,
            durationMs = durationMs,
            format = if (available) format else null,
            bitrateKbps = null,
        ),
        artwork = ArtworkRef.Owned(albumMbid(albumSlug).subsonicAlbumId),
    )

    private fun pinned(): AlbumState = AlbumState.Pinned(download = OfflineDownloadState.Complete)

    // ---- the pack's library (screens 02, 09, 13) ----------------------------

    val submarine: Album = album(
        slug = "submarine",
        title = "Submarine",
        artistName = "The Marías",
        artistSlug = "marias",
        year = 2024,
        trackCount = 8,
        durationMs = 1_681_000L,
        state = pinned(),
        format = AudioFormat.FLAC,
        sizeBytes = 412_000_000L,
    )

    val dragon: Album = album(
        slug = "dragon",
        title = "Dragon New Warm Mountain I Believe in You",
        artistName = "Big Thief",
        artistSlug = "bigthief",
        year = 2022,
        trackCount = 20,
        state = AlbumState.Owned,
        format = AudioFormat.FLAC,
    )

    val mordechai: Album = album(
        slug = "mordechai",
        title = "Mordechai",
        artistName = "Khruangbin",
        artistSlug = "khruangbin",
        year = 2020,
        trackCount = 9,
        state = pinned(),
        format = AudioFormat.MP3,
        bitrateKbps = 320,
    )

    val blueRev: Album = album(
        slug = "bluerev",
        title = "Blue Rev",
        artistName = "Alvvays",
        artistSlug = "alvvays",
        year = 2022,
        trackCount = 14,
        state = AlbumState.Owned,
        format = AudioFormat.FLAC,
    )

    val onceTwiceMelody: Album = album(
        slug = "oncetwice",
        title = "Once Twice Melody",
        artistName = "Beach House",
        artistSlug = "beachhouse",
        year = 2022,
        trackCount = 18,
        state = AlbumState.Owned,
        format = AudioFormat.MP3,
        bitrateKbps = 320,
    )

    val twoStar: Album = album(
        slug = "twostar",
        title = "Two Star & The Dream Police",
        artistName = "Mk.gee",
        artistSlug = "mkgee",
        year = 2024,
        trackCount = 12,
        state = pinned(),
        format = AudioFormat.FLAC,
    )

    val flyte: Album = album(
        slug = "flyte",
        title = "Flyte",
        artistName = "Flyte",
        artistSlug = "flyte",
        year = 2023,
        trackCount = 11,
        state = AlbumState.Owned,
        format = AudioFormat.MP3,
        bitrateKbps = 256,
    )

    val heaven: Album = album(
        slug = "heaven",
        title = "Heaven",
        artistName = "Cleo Sol",
        artistSlug = "cleosol",
        year = 2023,
        trackCount = 13,
        state = pinned(),
        format = AudioFormat.FLAC,
    )

    val buzz: Album = album(
        slug = "buzz",
        title = "Buzz",
        artistName = "Hannah Jadagu",
        artistSlug = "jadagu",
        year = 2023,
        trackCount = 10,
        state = AlbumState.Owned,
        format = AudioFormat.FLAC,
    )

    val theRecord: Album = album(
        slug = "record",
        title = "The Record",
        artistName = "boygenius",
        artistSlug = "boygenius",
        year = 2023,
        trackCount = 12,
        state = AlbumState.Owned,
        format = AudioFormat.FLAC,
    )

    val albums: List<Album> = listOf(
        submarine,
        dragon,
        mordechai,
        blueRev,
        onceTwiceMelody,
        twoStar,
        flyte,
        heaven,
        buzz,
        theRecord,
    )

    /**
     * The Marías, who most of the artist-screen goldens are about.
     *
     * Named rather than reached through `artists.first()`, which is what every caller used to do.
     * [artists] is in the mirror's own order now, and an index into a sorted list is an identity
     * that moves the next time a name is added to the list in front of it.
     */
    val marias: Artist = Artist(
        mbid = artistMbid("marias"),
        name = "The Marías",
        ownedAlbumCount = 3,
        catalogueAlbumCount = 6,
    )

    /**
     * The pack's seven artists, in the order the mirror serves them.
     *
     * Ordered by [NameKeys.sortKey], which is the fold `ArtistDao.observeArtists` orders
     * `sort_name_normalised` by: the leading article goes, so "The Marías" files under M between
     * Khruangbin and Mk.gee, which is where a device lists it and where the index strip's M chip
     * jumps to.
     *
     * It used to be in the order the albums above are declared in, which is roughly recency, so
     * `library-artists-phone.png` was an A-Z index strip drawn over a list that was not in A-Z
     * order. The screen deliberately does not sort - the mirror sorts in SQL, and
     * `GenresScreenshotTest` records the same split for genres - so an unsorted fixture here did not
     * test the screen against a hostile input. It only made a working feature look broken in the one
     * image anybody looks at.
     *
     * Sorted by the call rather than typed in sorted order, so the two cannot drift and so adding an
     * artist cannot quietly put the fixture back where it was.
     */
    val artists: List<Artist> = listOf(
        marias,
        Artist(mbid = artistMbid("bigthief"), name = "Big Thief", ownedAlbumCount = 5),
        Artist(mbid = artistMbid("khruangbin"), name = "Khruangbin", ownedAlbumCount = 4, catalogueAlbumCount = 9),
        Artist(mbid = artistMbid("alvvays"), name = "Alvvays", ownedAlbumCount = 3),
        Artist(mbid = artistMbid("beachhouse"), name = "Beach House", ownedAlbumCount = 8),
        Artist(mbid = artistMbid("mkgee"), name = "Mk.gee", ownedAlbumCount = 2),
        Artist(mbid = artistMbid("cleosol"), name = "Cleo Sol", ownedAlbumCount = 4),
    ).sortedBy { NameKeys.sortKey(it.sortName) }

    // ---- screen 04: Submarine's track list ----------------------------------

    val submarineTracks: List<Track> = listOf(
        track("submarine", 1, "Last Time", "The Marías", 239_000L, "Submarine"),
        track("submarine", 2, "Sienna", "The Marías", 200_000L, "Submarine"),
        track("submarine", 3, "Hamptons", "The Marías", 242_000L, "Submarine"),
        track("submarine", 4, "Paranoia", "The Marías", 215_000L, "Submarine"),
        track("submarine", 5, "If Only", "The Marías", 172_000L, "Submarine"),
        track("submarine", 6, "Lejos de Ti", "The Marías", 220_000L, "Submarine"),
        track("submarine", 7, "Love You Anyway", "The Marías", 206_000L, "Submarine"),
        track("submarine", 8, "Blur", "The Marías", 187_000L, "Submarine"),
    )

    /** The same record, with tracks 4 and 7 never delivered by the pull. */
    val submarinePartialTracks: List<Track> = submarineTracks.map { existing ->
        if (existing.key.trackNumber == 4 || existing.key.trackNumber == 7) {
            track(
                albumSlug = "submarine",
                number = existing.key.trackNumber,
                title = existing.title,
                artistName = existing.artistName,
                durationMs = existing.durationMs ?: 0L,
                albumTitle = existing.albumTitle,
                available = false,
            )
        } else {
            existing
        }
    }

    // ---- screen 05: an album you do not own ---------------------------------

    /** The album every [blackClassicalTracks] row belongs to, named once. */
    private const val ALBUM: String = "Black Classical Music"

    val blackClassicalMusic: Album = album(
        slug = "blackclassical",
        title = "Black Classical Music",
        artistName = "Yussef Dayes",
        artistSlug = "dayes",
        year = 2023,
        trackCount = 19,
        state = AlbumState.NotOwned,
        format = null,
    )

    /**
     * Screen 05's track list, and the add-tracks picker's search results.
     *
     * The album title is supplied on every row, which it was not. The picker draws
     * `LibraryFormat.songRowSubtitle`, which is artist and album, so a fixture with no album
     * rendered as the artist alone - and `screenshots/playlist-add-tracks-phone.png` was eight
     * rows reading "Yussef Dayes" under eight titles, beside a button counting in tracks and a
     * placeholder offering to search by album. The rows have to be told apart to be chosen
     * between, and the fixture was hiding the one field that does it.
     */
    val blackClassicalTracks: List<Track> = listOf(
        track("blackclassical", 1, "Black Classical Music", "Yussef Dayes", 251_000L, ALBUM, format = null),
        track("blackclassical", 2, "Afro Cubanism", "Yussef Dayes", 302_000L, ALBUM, format = null),
        track("blackclassical", 3, "Raisins Under the Sun", "Yussef Dayes", 288_000L, ALBUM, format = null),
        track("blackclassical", 4, "Rust", "Yussef Dayes", 237_000L, ALBUM, format = null),
        track("blackclassical", 5, "Turquoise Galaxy", "Yussef Dayes", 270_000L, ALBUM, format = null),
        track("blackclassical", 6, "Chasing the Drum", "Yussef Dayes", 315_000L, ALBUM, format = null),
        track("blackclassical", 7, "Gelato", "Yussef Dayes", 226_000L, ALBUM, format = null),
        track("blackclassical", 8, "Marching Band", "Yussef Dayes", 241_000L, ALBUM, format = null),
    )

    // ---- what the server does not guarantee ---------------------------------

    /**
     * An album the catalogue named with nothing at all.
     *
     * **Every other fixture in this file supplies a title, an artist and a cover**, which is
     * precisely why a screen that concatenated `album.title` into six accessibility labels
     * shipped and why no screenshot caught it. This one supplies none of the three, and it is
     * not a hypothetical: `ReleaseItemDto.title` is nullable and `CatalogueMappers.album` maps
     * it with `.orEmpty()`, so a release group MusicBrainz has no title for arrives exactly
     * like this.
     */
    val untitledAlbum: Album = Album(
        releaseGroupMbid = ReleaseGroupMbid("e7a4b1c2-0000-4000-8000-1234567890ab"),
        title = "",
        artistName = "",
        artistMbid = null,
        state = AlbumState.NotOwned,
    )

    /** The same, with a title that is only whitespace, so `isEmpty` and `isBlank` disagree. */
    val whitespaceTitledAlbum: Album = untitledAlbum.copy(
        releaseGroupMbid = ReleaseGroupMbid("e7a4b1c2-0000-4000-8000-1234567890ac"),
        title = "   ",
        artistName = "\t ",
    )

    /** An owned album with no title, so the owned path is covered as well as the catalogue one. */
    val untitledOwnedAlbum: Album = untitledAlbum.copy(
        releaseGroupMbid = ReleaseGroupMbid("e7a4b1c2-0000-4000-8000-1234567890ad"),
        state = AlbumState.Owned,
        trackCount = 2,
        quality = AudioQuality(format = AudioFormat.FLAC, bitrateKbps = null),
    )

    /** Every album whose title or artist is missing, for a test that sweeps all of them. */
    val namelessAlbums: List<Album> = listOf(untitledAlbum, whitespaceTitledAlbum, untitledOwnedAlbum)

    /** Tracks with no titles either - `CatalogueTrackDto.title` defaults to the empty string. */
    val untitledTracks: List<Track> = listOf(
        track(
            albumSlug = "untitled",
            number = 1,
            title = "",
            artistName = "",
            durationMs = 0L,
            format = null,
        ),
        track(
            albumSlug = "untitled",
            number = 2,
            title = "  ",
            artistName = "",
            durationMs = 181_000L,
            format = null,
        ),
    )

    /**
     * An artist whose id DroppedNeedle derived from their name: a UUID v5.
     *
     * The id is the one from the device log. Third group `5e80`, so version 5, which means the
     * catalogue discography route answers
     * `400 Use the local library artist route for a DroppedNeedle artist ID` for it - for ever.
     */
    val nameDerivedArtist: Artist = Artist(
        mbid = ArtistMbid("8cfce742-445e-5e80-93a8-d8f924d56984"),
        name = "Some Local Band",
        ownedAlbumCount = 1,
    )

    /**
     * The one album the mirror holds for [nameDerivedArtist], and the tracks on it.
     *
     * `artist-not-in-catalogue-phone.png` used to file "Submarine / The Marías · 2024" under the
     * artist "Some Local Band", because the state borrowed the nearest owned album to hand. A golden
     * whose header and whose only row name two different artists cannot be read for what it is
     * about: the question is whether the screen says plainly that there is no discography to look
     * up, and a reader has to get past the wrong album before reaching it.
     *
     * No year, because a folder DroppedNeedle scanned off a disk often carries none - and that scan
     * is the whole reason this artist's id was derived from their name and the catalogue lookup can
     * never succeed for them. The artist MBID is copied from [nameDerivedArtist] rather than derived
     * from the slug, so the album and the header agree about who this is.
     */
    val localBandAlbum: Album = album(
        slug = "somelocalband-demos",
        title = "Basement Demos",
        artistName = "Some Local Band",
        artistSlug = "somelocalband",
        trackCount = 4,
        durationMs = 712_000L,
        state = AlbumState.Owned,
        format = AudioFormat.MP3,
        bitrateKbps = 192,
    ).copy(artistMbid = nameDerivedArtist.mbid)

    val localBandTracks: List<Track> = listOf(
        track("somelocalband-demos", 1, "Kitchen Light", "Some Local Band", 194_000L, "Basement Demos"),
        track("somelocalband-demos", 2, "Four Streets Over", "Some Local Band", 171_000L, "Basement Demos"),
        track("somelocalband-demos", 3, "Nothing on the Radio", "Some Local Band", 208_000L, "Basement Demos"),
        track("somelocalband-demos", 4, "Last Bus", "Some Local Band", 139_000L, "Basement Demos"),
    )

    /**
     * An artist known only from catalogue search: a real MBID, and no mirror row anywhere.
     *
     * The device case. `refreshArtistDiscography` writes album rows and never an artist row, so
     * this artist is what the screen has to render from route hints alone. There is deliberately
     * no `Artist` object for them - having one would defeat the fixture.
     */
    const val CATALOGUE_ONLY_ARTIST_MBID: String = "1b2d8b3a-5c41-4f22-9a10-88d0c6f22e9a"

    const val CATALOGUE_ONLY_ARTIST_NAME: String = "My Friend the Chocolate Cake"

    // ---- the header figures on screens 02, 09 and 13 ------------------------

    /** The pack's own instant, so "last scan 47m ago" renders deterministically. */
    val renderedAt: Instant = Instant.parse("2026-09-21T18:00:00Z")

    val stats: LibraryStats = LibraryStats(
        albumCount = 176,
        artistCount = 63,
        trackCount = 2_184,
        // 42 GB as the pack draws it.
        totalSizeBytes = 42L * 1_073_741_824L,
        lastScanAt = renderedAt - kotlin.time.Duration.parse("47m"),
        source = StatsSource.SERVER,
    )
}
