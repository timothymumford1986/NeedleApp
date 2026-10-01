package app.needler.feature.search

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.AudioFormat
import app.needler.core.domain.model.AudioQuality
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.FileId
import app.needler.core.domain.model.OfflineDownloadState
import app.needler.core.domain.model.PullProgress
import app.needler.core.domain.model.PullState
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.SearchSuggestion
import app.needler.core.domain.model.ServiceStatus
import app.needler.core.domain.model.SuggestionKind
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackFetchHandle
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.UnifiedSearchResults

/**
 * The design pack's own search results, typed in.
 *
 * `design/html/03-Search.html` searches for *khruangbin* and
 * `design/html/10-TabletSearch.html` for *yussef dayes*, and each draws a
 * specific set of rows in a specific set of states. Using those exact titles,
 * artists, years and badges is what makes a rendered PNG directly comparable
 * with `design/png/03-Search.png` and `10-TabletSearch.png` — a screenshot of
 * invented data can only be checked for "does it look plausible", which is not
 * a test.
 *
 * The four album states on the phone screen are the point of the fixture as much
 * as the titles are. One block holds an album kept on the device, one already in
 * the library, one being acquired and one not owned at all, which is
 * REQUIREMENTS.md's merged list drawn in full: four different trailing
 * treatments, one [Album] type, one [AlbumState] deciding between them.
 *
 * Artwork is an [ArtworkRef] with no resolver behind it in a unit test — Coil's
 * mapper lives in `:app` — so every rendered tile is `:core:design`'s placeholder:
 * the record's initial over a tint derived from its release-group MBID. That is
 * the one deliberate difference from the pack, and it is what a device shows too
 * for an album whose cover the server has none of.
 */
internal object SampleSearch {

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
        state: AlbumState = AlbumState.Owned,
        format: AudioFormat? = AudioFormat.FLAC,
        bitrateKbps: Int? = null,
    ): Album = Album(
        releaseGroupMbid = albumMbid(slug),
        title = title,
        artistName = artistName,
        artistMbid = artistMbid(artistSlug),
        state = state,
        year = year,
        trackCount = trackCount,
        quality = AudioQuality(format = format, bitrateKbps = bitrateKbps),
        // An owned album's cover comes from Subsonic `getCoverArt` and an
        // un-owned one's from `/api/v1/covers/release-group/{mbid}`
        // (REQUIREMENTS.md rule 6). The ref is what records which, and the
        // screen never looks.
        artwork = if (state == AlbumState.NotOwned) {
            ArtworkRef.Catalogue(albumMbid(slug))
        } else {
            ArtworkRef.Owned(albumMbid(slug).subsonicAlbumId)
        },
    )

    fun track(
        albumSlug: String,
        number: Int,
        title: String,
        artistName: String,
        albumTitle: String,
        durationMs: Long,
        available: Boolean = true,
    ): Track = Track(
        key = TrackKey(albumMbid(albumSlug), discNumber = 1, trackNumber = number),
        title = title,
        artistName = artistName,
        albumTitle = albumTitle,
        durationMs = durationMs,
        fetch = TrackFetchHandle(
            // "unavailable" is the sentinel :core:data writes when a mirror row
            // has no file behind it, which is what a part-delivered pull leaves.
            fileId = FileId(if (available) "f-$albumSlug-$number" else "unavailable"),
            sizeBytes = if (available) 31_000_000L else null,
            durationMs = durationMs,
            format = if (available) AudioFormat.FLAC else null,
            bitrateKbps = null,
        ),
        artwork = ArtworkRef.Owned(albumMbid(albumSlug).subsonicAlbumId),
    )

    private fun pinned(): AlbumState = AlbumState.Pinned(download = OfflineDownloadState.Complete)

    private fun pulling(percent: Int?): AlbumState = AlbumState.Acquiring(
        progress = PullProgress(percent = percent),
        stage = PullState.DOWNLOADING,
    )

    // ---- screen 03: "khruangbin" on a phone ---------------------------------

    val khruangbin: Artist = Artist(
        mbid = artistMbid("khruangbin"),
        name = "Khruangbin",
        // The pack's own line: "5 albums · 2 in your library".
        ownedAlbumCount = 2,
        catalogueAlbumCount = 5,
    )

    val mordechai: Album = album(
        slug = "mordechai",
        title = "Mordechai",
        artistName = "Khruangbin",
        artistSlug = "khruangbin",
        year = 2024,
        trackCount = 9,
        state = pinned(),
    )

    val flyte: Album = album(
        slug = "flyte",
        title = "Flyte",
        artistName = "Flyte",
        year = 2024,
        trackCount = 11,
        state = AlbumState.Owned,
        format = AudioFormat.MP3,
        bitrateKbps = 320,
    )

    val blackClassicalMusic: Album = album(
        slug = "blackclassical",
        title = "Black Classical Music",
        artistName = "Yussef Dayes",
        artistSlug = "dayes",
        year = 2024,
        trackCount = 19,
        state = pulling(percent = null),
        format = null,
    )

    val buzz: Album = album(
        slug = "buzz",
        title = "Buzz",
        artistName = "NIKI",
        artistSlug = "niki",
        year = 2024,
        state = AlbumState.NotOwned,
        format = null,
    )

    val timeYouAndI: Track = track(
        albumSlug = "mordechai",
        number = 4,
        title = "Time (You and I)",
        artistName = "Khruangbin",
        albumTitle = "Mordechai",
        durationMs = 227_000L,
    )

    val pelota: Track = track(
        albumSlug = "mordechai",
        number = 6,
        title = "Pelota",
        artistName = "Khruangbin",
        albumTitle = "Mordechai",
        durationMs = 200_000L,
    )

    /** Screen 03 in full: one artist, four albums in four states, two songs. */
    val khruangbinResults: UnifiedSearchResults = UnifiedSearchResults(
        query = "khruangbin",
        artists = listOf(khruangbin),
        albums = listOf(mordechai, flyte, blackClassicalMusic, buzz),
        tracks = listOf(timeYouAndI, pelota),
        catalogue = CatalogueLaneState.Ready(),
    )

    // ---- screen 10: "yussef dayes" on a tablet ------------------------------

    val yussefDayes: Artist = Artist(
        mbid = artistMbid("dayes"),
        name = "Yussef Dayes",
        // The pack's own line, minus the "drummer, London" clause: `Artist`
        // carries no disambiguation field. See SearchFormat.artistRowSubtitle.
        ownedAlbumCount = 1,
        catalogueAlbumCount = 4,
    )

    val blackClassicalMusicPulling: Album = album(
        slug = "blackclassical",
        title = "Black Classical Music",
        artistName = "Yussef Dayes",
        artistSlug = "dayes",
        year = 2023,
        trackCount = 19,
        state = pulling(percent = 62),
        format = null,
    )

    val backStreetCrawler: Album = album(
        slug = "kossoff",
        title = "Back Street Crawler",
        artistName = "Paul Kossoff",
        year = 2020,
        state = AlbumState.NotOwned,
        format = null,
    )

    val flyteTablet: Album = album(
        slug = "flyte",
        title = "Flyte",
        artistName = "Flyte",
        year = 2021,
        trackCount = 11,
        state = AlbumState.Owned,
    )

    val twoStar: Album = album(
        slug = "twostar",
        title = "Two Star & The Dream Police",
        artistName = "Mk.gee",
        artistSlug = "mkgee",
        year = 2024,
        trackCount = 12,
        state = pinned(),
    )

    /** Screen 10 in full: one artist and the four-card album grid. */
    val yussefResults: UnifiedSearchResults = UnifiedSearchResults(
        query = "yussef dayes",
        artists = listOf(yussefDayes),
        albums = listOf(
            blackClassicalMusicPulling,
            backStreetCrawler,
            flyteTablet,
            twoStar,
        ),
        catalogue = CatalogueLaneState.Ready(),
    )

    // ---- "wonder": the search that shipped wrong ----------------------------

    /**
     * The search from the device report, as the screen now orders it.
     *
     * Searching "wonder" returned four catalogue artists — "Jr. Wonder", "Wonder",
     * "wonder", "wonder", two of them the same string twice, all four subtitled
     * "Not in your library yet" — and did not return "Oh Wonder", three of whose
     * albums were on the server. Below them came two owned albums and then twenty
     * catalogue albums, which put the Songs block sixteen swipes down.
     *
     * This fixture is that same query after the merge was fixed: the owned artist
     * leads, the duplicate names are collapsed, the library's own albums and songs
     * come before anything un-owned, and the un-owned tail is capped with a row
     * that reveals the rest.
     */
    val ohWonder: Artist = Artist(
        mbid = artistMbid("ohwonder"),
        name = "Oh Wonder",
        ownedAlbumCount = 3,
    )

    /**
     * Artists in the order [app.needler.core.domain.usecase.UnifiedSearchUseCase]
     * now produces: the owned one first, then the catalogue by how well the name
     * answers what was typed. "Wonder" is an exact match, "Stevie Wonder" and the
     * rest match a word.
     */
    val wonderArtists: List<Artist> = listOf(
        ohWonder,
        catalogueArtist("wonder-mb", "Wonder"),
        catalogueArtist("jr-wonder", "Jr. Wonder"),
        catalogueArtist("wonder-boy", "Wonder Boy"),
        catalogueArtist("stevie-wonder", "Stevie Wonder"),
    )

    private val ownedWonderAlbums: List<Album> = listOf(
        album(
            slug = "ultralife",
            title = "Ultralife",
            artistName = "Oh Wonder",
            artistSlug = "ohwonder",
            year = 2017,
            trackCount = 12,
            state = pinned(),
        ),
        album(
            slug = "no-one-else",
            title = "No One Else Can Wear Your Crown",
            artistName = "Oh Wonder",
            artistSlug = "ohwonder",
            year = 2020,
            trackCount = 11,
            state = AlbumState.Owned,
        ),
        album(
            slug = "22-break",
            title = "22 Break",
            artistName = "Oh Wonder",
            artistSlug = "ohwonder",
            year = 2021,
            trackCount = 12,
            state = AlbumState.Owned,
        ),
    )

    /** Ten un-owned release groups: two more than the block shows before it is expanded. */
    private val catalogueWonderAlbums: List<Album> = listOf(
        "Wonder" to "Hillsong United",
        "Wonderland" to "Taylor Swift",
        "Wonder Where We Land" to "SBTRKT",
        "Wonderful Wonderful" to "The Killers",
        "Wonders of the Younger" to "Plain White T's",
        "Stevie Wonder Live" to "Stevie Wonder",
        "Wonder Cycle" to "Chris Cohen",
        "Wonderful Crazy Night" to "Elton John",
        "Wonderful Rainbow" to "Lightning Bolt",
        "Wonder Years" to "The Wonder Years",
    ).mapIndexed { index, (title, artist) ->
        album(
            slug = "wonder-catalogue-$index",
            title = title,
            artistName = artist,
            year = 2000 + index,
            state = AlbumState.NotOwned,
            format = null,
        )
    }

    private val wonderTracks: List<Track> = listOf(
        track(
            albumSlug = "ultralife",
            number = 1,
            title = "Solo",
            artistName = "Oh Wonder",
            albumTitle = "Ultralife",
            durationMs = 194_000L,
        ),
        track(
            albumSlug = "22-break",
            number = 3,
            title = "Free",
            artistName = "Oh Wonder",
            albumTitle = "22 Break",
            durationMs = 181_000L,
        ),
    )

    /** One artist you own, four you do not, three albums, two songs, ten to pull. */
    val wonderResults: UnifiedSearchResults = UnifiedSearchResults(
        query = "wonder",
        artists = wonderArtists,
        albums = ownedWonderAlbums + catalogueWonderAlbums,
        tracks = wonderTracks,
        catalogue = CatalogueLaneState.Ready(),
    )

    private fun catalogueArtist(slug: String, name: String): Artist =
        Artist(mbid = artistMbid(slug), name = name)

    // ---- the states the pack does not draw ----------------------------------

    /** Previous searches, for the state before anything is typed. */
    val recentQueries: List<String> = listOf(
        "khruangbin",
        "yussef dayes",
        "two star",
        "beach house",
    )

    /** Completions from `GET /api/v1/search/suggest`, for the no-results screen. */
    val suggestions: List<SearchSuggestion> = listOf(
        SearchSuggestion(
            text = "Khruangbin",
            kind = SuggestionKind.ARTIST,
            artistMbid = artistMbid("khruangbin"),
        ),
        SearchSuggestion(
            text = "Mordechai",
            kind = SuggestionKind.ALBUM,
            releaseGroupMbid = albumMbid("mordechai"),
        ),
        SearchSuggestion(text = "khruangbin live"),
    )

    /** What the search response's `service_status` field looks like when upstream is struggling. */
    val degraded: ServiceStatus = ServiceStatus(
        isDegraded = true,
        message = "MusicBrainz is responding slowly, so catalogue results may be short.",
        raw = "degraded",
    )
}
