package app.needler.player.service.media

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.DownloadedAlbum
import app.needler.core.domain.model.Favourites
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.Playlist
import app.needler.core.domain.model.PlaylistEntry
import app.needler.core.domain.model.PlaylistId
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackListKind
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.domain.repository.LibraryRepository
import app.needler.core.domain.repository.PinRepository
import app.needler.core.domain.repository.PlaylistRepository
import app.needler.core.domain.repository.SearchRepository
import kotlinx.coroutines.flow.first

/**
 * The Android Auto browse tree, and the voice search behind it.
 *
 * REQUIREMENTS.md "Android Auto": "The browse tree mirrors the app: Library with albums, artists and songs;
 * Recently added; Playlists; Favourites; On device. Voice search maps to the same unified search, restricted
 * to owned music, since pulling while driving makes no sense."
 *
 * ## Everything here reads the mirror
 *
 * Every list comes from a `:core:domain` repository, and every one of those reads the local Room mirror -
 * REQUIREMENTS.md "Library browse": "All library browsing reads the local metadata mirror, so it works
 * identically online and offline." That is not a nicety in a car. A tunnel, an underground car park and a
 * rural A-road are the normal condition, and a browse tree that needed the server would be empty exactly
 * when it was being used. Nothing here can make a network call, which is a property worth keeping: the two
 * functions that would - `LibraryRepository.observeArtistDiscography` and `SearchRepository.searchCatalogue`,
 * the two places the lanes meet - are the two this class never calls.
 *
 * ## Owned music only
 *
 * The mirror holds catalogue-only albums as well, because that is how one `Album` type describes both lanes
 * (REQUIREMENTS.md "Identity model"). None of them belong in a car: an un-owned album has no files behind it,
 * so its row would open an empty list, and the one action it offers - **Pull** - is what the requirement rules
 * out at the wheel. The album lists and track queries the mirror serves already filter to owned content; the
 * filters here restate that guarantee at the boundary which depends on it, for one predicate per page.
 *
 * ## Paging is not optional
 *
 * `onGetChildren` carries a page and a page size because a browser cannot be handed a whole library:
 * REQUIREMENTS.md's performance budget is written against a 5,000-album library, and a head unit has a
 * fraction of a phone's memory. Where the repository takes a limit and an offset the page becomes an SQL
 * window and only that page is read. Where it does not - artists, playlists, genres, favourites, downloads,
 * the tracks of one record - the list is bounded by the library's shape rather than its size and the page is
 * sliced in memory. See [windowFor].
 */
public class BrowseTree(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val favourites: FavouriteRepository,
    private val pins: PinRepository,
    private val search: SearchRepository,
) {

    /**
     * The newest search and its rows.
     *
     * A browser asks the same question twice: `onSearch` to learn how many results there are, then
     * `onGetSearchResult` for each page of them. Repeating the FTS query for each would be two to four
     * queries for one utterance, and the later ones could legitimately answer differently from the first - a
     * sync landing in between - which the car shows as a list whose length contradicts the count it was just
     * given. One entry is enough: a car has one search box, and the user is driving.
     */
    @Volatile
    private var lastSearch: Pair<String, List<BrowseRow>>? = null

    /** The root Auto connects to. */
    public fun rootRow(): BrowseRow = BrowseRow(
        mediaId = MediaId.BROWSE_ROOT,
        title = LABEL_ROOT,
        kind = BrowseRowKind.FOLDER_MIXED,
    )

    /**
     * One page of [parentId]'s children.
     *
     * An unknown or playable [parentId] answers with an empty list rather than an error: Auto keeps ids
     * across app updates, and "that node is gone" is not a failure a driver can act on.
     */
    public suspend fun children(parentId: String, page: Int, pageSize: Int): List<BrowseRow> {
        val window: Window = windowFor(page, pageSize)
        return when (val node: BrowseNode? = MediaId.toBrowseNode(parentId)) {
            BrowseNode.Root -> window.slice(rootChildren())
            BrowseNode.Library -> window.slice(libraryChildren())
            BrowseNode.Albums -> albumRows(
                library.observeAlbumList(
                    kind = AlbumListKind.ALPHABETICAL_BY_NAME,
                    limit = window.limit,
                    offset = window.offset,
                ).first(),
            )
            BrowseNode.RecentlyAdded -> albumRows(
                library.observeAlbumList(
                    kind = AlbumListKind.NEWEST,
                    limit = window.limit,
                    offset = window.offset,
                ).first(),
            )
            BrowseNode.Songs -> trackRows(
                library.observeTracks(
                    kind = TrackListKind.ALPHABETICAL_BY_TITLE,
                    limit = window.limit,
                    offset = window.offset,
                ).first(),
            )
            BrowseNode.Artists -> window.slice(artistRows(library.observeArtists().first()))
            BrowseNode.Genres -> window.slice(genreRows(library.observeGenres().first()))
            BrowseNode.Playlists -> window.slice(playlistRows(playlists.observePlaylists().first()))
            BrowseNode.Favourites -> window.slice(favouriteRows(favourites.observeFavourites().first()))
            BrowseNode.OnDevice -> window.slice(onDeviceRows(pins.observeDownloadedAlbums().first()))
            is BrowseNode.OneAlbum -> window.slice(trackRows(albumTracks(node.mbid)))
            is BrowseNode.OneArtist -> window.slice(
                albumRows(library.observeOwnedAlbumsByArtist(node.mbid).first()),
            )
            is BrowseNode.OnePlaylist -> window.slice(trackRows(playlistTracks(node.id)))
            is BrowseNode.OneGenre -> trackRows(
                library.observeTracksByGenre(
                    genre = node.name,
                    limit = window.limit,
                    offset = window.offset,
                ).first(),
            )
            null -> emptyList()
        }
    }

    /**
     * The row for one id, which a browser asks for when it holds an id and no list - a tab it resumed, a
     * shortcut a user pinned, the item behind a voice result.
     */
    public suspend fun row(mediaId: String): BrowseRow? {
        val node: BrowseNode = MediaId.toBrowseNode(mediaId)
            ?: return MediaId.toTrackKey(mediaId)?.let { library.getTrack(it) }?.let(::trackRow)
        return when (node) {
            BrowseNode.Root -> rootRow()
            is BrowseNode.OneAlbum -> library.getAlbum(node.mbid)?.takeIf(Album::isOwned)?.let(::albumRow)
            is BrowseNode.OneArtist -> library.observeArtist(node.mbid).first()?.let(::artistRow)
            is BrowseNode.OnePlaylist -> playlists.observePlaylist(node.id).first()?.let(::playlistRow)
            is BrowseNode.OneGenre -> genreRow(Genre(name = node.name))
            else -> (rootChildren() + libraryChildren()).firstOrNull { it.mediaId == mediaId }
        }
    }

    /**
     * One page of voice- or keyboard-search results, owned music only.
     *
     * [SearchRepository.searchLocal] and never `searchCatalogue`: the catalogue lane reaches MusicBrainz
     * through the server, takes seconds when it works at all, and returns albums whose only action is
     * **Pull** - which REQUIREMENTS.md "Android Auto" rules out while driving. Local FTS answers from the
     * mirror in under 50 ms with no network, which is the only latency a car should be asked to accept.
     */
    public suspend fun searchRows(query: String, page: Int, pageSize: Int): List<BrowseRow> =
        windowFor(page, pageSize).slice(searchResults(query))

    /** How many rows [query] found, which is what a browser is told before it asks for a page. */
    public suspend fun searchRowCount(query: String): Int = searchResults(query).size

    /**
     * The tracks that playing [mediaId] means, in order.
     *
     * This is what makes a browsable node playable. An item arriving from Auto is a media id with no URI and
     * no track list, so "play this album" reaches the session as one unplayable row; expanding it here is the
     * difference between a record playing and a folder sitting silently in the crate.
     *
     * A folder of folders expands to nothing on purpose. The playable kinds are exactly the ones
     * [BrowseRowKind] marks playable, and "Songs" is not among them: enqueueing an entire library from one
     * mistaken tap at a junction is not a feature.
     */
    public suspend fun tracksFor(mediaId: String): List<Track> {
        val node: BrowseNode = MediaId.toBrowseNode(mediaId)
            ?: return listOfNotNull(MediaId.toTrackKey(mediaId)?.let { library.getTrack(it) })
        return when (node) {
            is BrowseNode.OneAlbum -> albumTracks(node.mbid)
            is BrowseNode.OneArtist -> artistTracks(node.mbid)
            is BrowseNode.OnePlaylist -> playlistTracks(node.id)
            is BrowseNode.OneGenre -> library
                .observeTracksByGenre(genre = node.name, limit = MAX_ENQUEUED_TRACKS, offset = 0)
                .first()
            else -> emptyList()
        }
    }

    /**
     * The tracks a spoken "play ..." means: the first playable row the same search would have shown.
     *
     * Media3 delivers a voice play command as a set-media-items call carrying a search query and no media id,
     * so this and [searchRows] answer one utterance in two shapes. They are ranked by one list in one order -
     * albums, then artists, then songs - because a car that plays something other than the row it just read
     * out is a car the driver stops trusting.
     */
    public suspend fun tracksForQuery(query: String): List<Track> {
        val first: BrowseRow = searchResults(query).firstOrNull { it.kind.isPlayable } ?: return emptyList()
        return tracksFor(first.mediaId)
    }

    private suspend fun searchResults(query: String): List<BrowseRow> {
        val trimmed: String = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        lastSearch?.takeIf { it.first == trimmed }?.let { return it.second }
        val results: LocalSearchResults = search.searchLocal(trimmed, limit = SEARCH_LIMIT).first()
        val rows: List<BrowseRow> =
            albumRows(results.albums) + artistRows(results.artists) + trackRows(results.tracks)
        lastSearch = trimmed to rows
        return rows
    }

    /** The five nodes REQUIREMENTS.md "Android Auto" names, in the order it names them. */
    private fun rootChildren(): List<BrowseRow> = listOf(
        folderRow(MediaId.BROWSE_LIBRARY, LABEL_LIBRARY, BrowseRowKind.FOLDER_MIXED),
        folderRow(MediaId.BROWSE_RECENTLY_ADDED, LABEL_RECENTLY_ADDED, BrowseRowKind.FOLDER_ALBUMS),
        folderRow(MediaId.BROWSE_PLAYLISTS, LABEL_PLAYLISTS, BrowseRowKind.FOLDER_PLAYLISTS),
        folderRow(MediaId.BROWSE_FAVOURITES, LABEL_FAVOURITES, BrowseRowKind.FOLDER_MIXED),
        folderRow(MediaId.BROWSE_ON_DEVICE, LABEL_ON_DEVICE, BrowseRowKind.FOLDER_ALBUMS),
    )

    /**
     * Albums, artists and songs, as the requirement lists them, plus the genres the mirror already serves.
     *
     * Genres are here rather than at the root because that is where the app's own library tab keeps them, and
     * the tree mirrors the app. REQUIREMENTS.md "Library browse" lists Genres among the library screens;
     * REQUIREMENTS.md "Android Auto" names the three it cares about and does not forbid the fourth.
     */
    private fun libraryChildren(): List<BrowseRow> = listOf(
        folderRow(MediaId.BROWSE_ALBUMS, LABEL_ALBUMS, BrowseRowKind.FOLDER_ALBUMS),
        folderRow(MediaId.BROWSE_ARTISTS, LABEL_ARTISTS, BrowseRowKind.FOLDER_ARTISTS),
        folderRow(MediaId.BROWSE_SONGS, LABEL_SONGS, BrowseRowKind.FOLDER_TRACKS),
        folderRow(MediaId.BROWSE_GENRES, LABEL_GENRES, BrowseRowKind.FOLDER_GENRES),
    )

    private fun folderRow(mediaId: String, title: String, kind: BrowseRowKind): BrowseRow =
        BrowseRow(mediaId = mediaId, title = title, kind = kind)

    private suspend fun albumTracks(mbid: ReleaseGroupMbid): List<Track> =
        library.observeAlbumTracks(mbid).first()

    private suspend fun playlistTracks(id: PlaylistId): List<Track> =
        playlists.observePlaylistEntries(id).first().map(PlaylistEntry::track)

    /**
     * Every owned album of one artist, in the order that artist's own node lists them, capped.
     *
     * The cap is about the crate rather than the query: the crate is persisted on every change and bundled to
     * every controller, and a prolific artist in a large library is thousands of tracks. Truncating is the
     * lesser fault - the driver asked for music, not for a complete inventory.
     */
    private suspend fun artistTracks(mbid: ArtistMbid): List<Track> {
        val albums: List<Album> = library.observeOwnedAlbumsByArtist(mbid).first()
        val tracks: MutableList<Track> = mutableListOf()
        for (album in albums) {
            if (tracks.size >= MAX_ENQUEUED_TRACKS) break
            tracks += albumTracks(album.releaseGroupMbid)
        }
        return if (tracks.size <= MAX_ENQUEUED_TRACKS) tracks else tracks.take(MAX_ENQUEUED_TRACKS)
    }

    private fun albumRows(albums: List<Album>): List<BrowseRow> =
        albums.filter(Album::isOwned).map(::albumRow)

    private fun albumRow(album: Album): BrowseRow = BrowseRow(
        mediaId = MediaId.forAlbum(album.releaseGroupMbid),
        title = album.title,
        subtitle = album.artistName,
        kind = BrowseRowKind.ALBUM,
        artwork = album.artwork,
    )

    /** Only artists with owned music: an artist known from a discography lookup is an empty folder in a car. */
    private fun artistRows(artists: List<Artist>): List<BrowseRow> =
        artists.filter { it.ownedAlbumCount > 0 }.map(::artistRow)

    private fun artistRow(artist: Artist): BrowseRow = BrowseRow(
        mediaId = MediaId.forArtist(artist.mbid),
        title = artist.name,
        kind = BrowseRowKind.ARTIST,
        artwork = artist.artwork,
    )

    private fun playlistRows(all: List<Playlist>): List<BrowseRow> = all.map(::playlistRow)

    private fun playlistRow(playlist: Playlist): BrowseRow = BrowseRow(
        mediaId = MediaId.forPlaylist(playlist.id),
        title = playlist.name,
        kind = BrowseRowKind.PLAYLIST,
        artwork = playlist.artwork,
    )

    private fun genreRows(all: List<Genre>): List<BrowseRow> = all.map(::genreRow)

    private fun genreRow(genre: Genre): BrowseRow = BrowseRow(
        mediaId = MediaId.forGenre(genre.name),
        title = genre.name,
        kind = BrowseRowKind.GENRE,
    )

    private fun trackRows(tracks: List<Track>): List<BrowseRow> = tracks.map(::trackRow)

    private fun trackRow(track: Track): BrowseRow = BrowseRow(
        mediaId = MediaId.forTrack(track.key),
        title = track.title,
        subtitle = track.artistName,
        kind = BrowseRowKind.TRACK,
        artwork = track.artwork,
        track = track,
    )

    /**
     * Starred albums, artists and songs in one flat list.
     *
     * Flat rather than three sub-folders: the app's Favourites screen has room for three sections, a car has
     * one list and a driver. Every extra level is another tap taken at speed, and `getStarred2` already
     * orders each group recently-starred first, so the flat list reads as "what I starred lately".
     */
    private fun favouriteRows(starred: Favourites): List<BrowseRow> =
        albumRows(starred.albums) + artistRows(starred.artists) + trackRows(starred.tracks)

    /**
     * Downloaded albums, alphabetical.
     *
     * `PinRepository.observeDownloadedAlbums` orders by size, because the screen it exists for is Storage and
     * the question there is "what is taking the space". Browsing asks a different question, so the rows are
     * re-ordered by title: a car list that rearranges itself whenever a download grows is a list nobody can
     * learn.
     *
     * The artwork reference is built from the release group rather than carried on the row, which is the same
     * fallback `:core:data` uses for an owned album with no cover-art id of its own: an owned album's cover is
     * always addressable as the Subsonic album id.
     */
    private fun onDeviceRows(downloads: List<DownloadedAlbum>): List<BrowseRow> = downloads
        .sortedBy { it.title.lowercase() }
        .map { download ->
            BrowseRow(
                mediaId = MediaId.forAlbum(download.releaseGroupMbid),
                title = download.title,
                subtitle = download.artistName,
                kind = BrowseRowKind.ALBUM,
                artwork = ArtworkRef.Owned(download.releaseGroupMbid.subsonicAlbumId),
            )
        }

    /**
     * The window one page names.
     *
     * The arithmetic is done in `Long` deliberately. A browser that does not page sends page 0 with a page
     * size of `Int.MAX_VALUE`, and `page * pageSize` in `Int` overflows to a negative offset - which is an
     * index out of bounds thrown at a head unit instead of a list.
     */
    private fun windowFor(page: Int, pageSize: Int): Window {
        val limit: Int = pageSize.coerceAtLeast(1)
        val offset: Long = page.coerceAtLeast(0).toLong() * limit.toLong()
        return Window(offset = offset.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), limit = limit)
    }

    /** One page, as the offset and limit pair the repositories already take. */
    private data class Window(val offset: Int, val limit: Int) {

        fun <T> slice(all: List<T>): List<T> {
            if (offset >= all.size) return emptyList()
            val end: Long = offset.toLong() + limit.toLong()
            return all.subList(offset, end.coerceAtMost(all.size.toLong()).toInt())
        }
    }

    public companion object {

        /**
         * The node titles.
         *
         * REQUIREMENTS.md "Vocabulary" fixes "On device"; the rest are the names the app's own screens carry,
         * because a tree that "mirrors the app" has to use the app's words or it is a second product.
         *
         * Constants rather than string resources because `:player:service` has none of its own, and adding a
         * `strings.xml` here would put a piece of the product's vocabulary in a module nobody translating the
         * app would think to open. When the module does gain one, these move into it unchanged.
         */
        public const val LABEL_ROOT: String = "Needler"

        /** "Library". */
        public const val LABEL_LIBRARY: String = "Library"

        /** "Albums". */
        public const val LABEL_ALBUMS: String = "Albums"

        /** "Artists". */
        public const val LABEL_ARTISTS: String = "Artists"

        /** "Songs". */
        public const val LABEL_SONGS: String = "Songs"

        /** "Genres". */
        public const val LABEL_GENRES: String = "Genres"

        /** "Recently added". */
        public const val LABEL_RECENTLY_ADDED: String = "Recently added"

        /** "Playlists". */
        public const val LABEL_PLAYLISTS: String = "Playlists"

        /** "Favourites". */
        public const val LABEL_FAVOURITES: String = "Favourites"

        /** "On device", which REQUIREMENTS.md "Vocabulary" fixes as the word for the downloaded tier. */
        public const val LABEL_ON_DEVICE: String = "On device"

        /**
         * The most tracks one browse selection may enqueue.
         *
         * A genre, or a prolific artist in a 5,000-album library, is tens of thousands of tracks, and the
         * crate is persisted on every change and read by every controller. Five hundred is about a day of
         * driving.
         */
        public const val MAX_ENQUEUED_TRACKS: Int = 500

        /**
         * How many results per bucket a search asks the mirror for.
         *
         * Matches `SearchRepository.searchLocal`'s own default. A car list is read at a glance and scrolled
         * with a rotary controller: the fiftieth result is not one anybody reaches while driving.
         */
        public const val SEARCH_LIMIT: Int = 50
    }
}
