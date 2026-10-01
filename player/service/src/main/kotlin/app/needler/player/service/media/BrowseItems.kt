package app.needler.player.service.media

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService.LibraryParams
import app.needler.core.domain.model.ArtworkRef
import app.needler.core.domain.model.Track

/**
 * The one place a [BrowseRow] becomes a Media3 `MediaItem`.
 *
 * Auto draws a row entirely from this metadata. `isBrowsable` and `isPlayable` decide whether tapping it
 * opens a list or starts the music - and Media3 asserts that both are set on anything returned from a browse
 * callback, so a missing flag is a crash in the car rather than a cosmetic fault. The media type decides the
 * placeholder art and, on some head units, whether the level is drawn as a grid or a list.
 *
 * Browse items carry **no URI**, unlike the crate rows [TrackCatalogue.mediaItemFor] builds. A browse row is a
 * thing to look at; it becomes something to play when it is enqueued, which is where the `needler://track/...`
 * URI and the crate row id are minted. Media3 does not bundle a `LocalConfiguration` across the session
 * boundary anyway, so a URI put here would be dropped in transit and misleading in the source.
 */
@OptIn(UnstableApi::class)
public object BrowseItems {

    /** The `MediaItem` for one browse row. */
    public fun mediaItem(row: BrowseRow): MediaItem = MediaItem.Builder()
        .setMediaId(row.mediaId)
        .setMediaMetadata(metadataFor(row))
        .build()

    /**
     * The params returned with the browse root, carrying Auto's content-style hints.
     *
     * Without them a head unit draws every level as a plain list, including the album grids the app shows as
     * artwork. Albums and artists are pictures and read far better as a grid at a glance; songs are text and
     * read better as a list. The hints are set once, on the root, because that is the only place the Auto host
     * reads them from.
     *
     * The keys are the `android.media.browse` extras the Auto host has published for years, not something
     * Media3 invents: they travel as plain bundle keys in the browser root's extras, and a client that does not
     * understand them ignores them. Whatever extras the browser sent in [requested] are carried through rather
     * than replaced, so a hint this build does not know about is not dropped on its way back.
     */
    public fun rootParams(requested: LibraryParams?): LibraryParams {
        val extras = Bundle(requested?.extras ?: Bundle.EMPTY)
        extras.putBoolean(EXTRA_CONTENT_STYLE_SUPPORTED, true)
        extras.putInt(EXTRA_CONTENT_STYLE_BROWSABLE, CONTENT_STYLE_GRID)
        extras.putInt(EXTRA_CONTENT_STYLE_PLAYABLE, CONTENT_STYLE_LIST)
        return LibraryParams.Builder()
            .setExtras(extras)
            .build()
    }

    private fun metadataFor(row: BrowseRow): MediaMetadata {
        val builder: MediaMetadata.Builder = MediaMetadata.Builder()
            .setTitle(row.title)
            .setSubtitle(row.subtitle)
            .setIsBrowsable(row.kind.isBrowsable)
            .setIsPlayable(row.kind.isPlayable)
            .setMediaType(mediaTypeOf(row.kind))
            .setArtworkUri(artworkUriFor(row.artwork))
        val track: Track? = row.track
        if (track == null) {
            // The artist name is the subtitle on an album row, and Auto reads whichever of the two its layout
            // has room for.
            row.subtitle?.let(builder::setArtist)
        } else {
            builder
                .setArtist(track.artistName)
                .setAlbumTitle(track.albumTitle)
                .setTrackNumber(track.key.trackNumber)
                .setDiscNumber(track.key.discNumber)
                .setDurationMs(track.durationMs)
                .setReleaseYear(track.year)
                .setGenre(track.genres.firstOrNull())
        }
        return builder.build()
    }

    /**
     * Media3's media type for a row kind.
     *
     * A folder of songs comes out as `MEDIA_TYPE_FOLDER_MIXED` because Media3 has no folder-of-tracks type;
     * mixed is the honest answer rather than claiming the level holds albums.
     */
    private fun mediaTypeOf(kind: BrowseRowKind): Int = when (kind) {
        BrowseRowKind.FOLDER_MIXED, BrowseRowKind.FOLDER_TRACKS -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        BrowseRowKind.FOLDER_ALBUMS -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
        BrowseRowKind.FOLDER_ARTISTS -> MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS
        BrowseRowKind.FOLDER_GENRES -> MediaMetadata.MEDIA_TYPE_FOLDER_GENRES
        BrowseRowKind.FOLDER_PLAYLISTS -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
        BrowseRowKind.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
        BrowseRowKind.ARTIST -> MediaMetadata.MEDIA_TYPE_ARTIST
        BrowseRowKind.PLAYLIST -> MediaMetadata.MEDIA_TYPE_PLAYLIST
        BrowseRowKind.GENRE -> MediaMetadata.MEDIA_TYPE_GENRE
        BrowseRowKind.TRACK -> MediaMetadata.MEDIA_TYPE_MUSIC
    }

    /**
     * The artwork URI a browse row may carry, which is only ever an absolute one the server handed us.
     *
     * ## Why an owned album's cover is not put here
     *
     * The two server-backed artwork endpoints cannot be addressed by another process. Subsonic `getCoverArt`
     * carries the app-password in its `apiKey` parameter, and a browse item's metadata is bundled to every
     * controller of the session and persisted by the platform - the argument [TrackUri] makes about stream
     * URLs applies word for word, and it is the whole reason the crate holds an opaque URI. The catalogue
     * cover endpoint is authorised by the bearer token as a header, which a URI cannot carry at all, so a
     * head unit fetching it would get a 401.
     *
     * Media3's own notification bitmap loader would fetch such a URI too, on a default HTTP client that knows
     * nothing of the pinned certificate this project has exactly one client to enforce. So the credentialed
     * form is not merely leaky, it would also bypass the pin.
     *
     * Two alternatives were considered and rejected for now. Bundling the bytes as `artworkData` fits in no
     * budget: a page of twenty covers crosses the same one-megabyte Binder transaction the widgets already
     * have to keep under, and this is a list that pages. Serving covers from an exported `ContentProvider`
     * backed by the shared Coil cache does work and is the right end state - it is named in the module report
     * rather than half-built here, because it adds an exported provider and an image dependency to the module
     * that makes the sound.
     *
     * Until then an owned album shows Auto's own placeholder, which is what the app shows offline as well.
     */
    private fun artworkUriFor(artwork: ArtworkRef?): Uri? = when (artwork) {
        is ArtworkRef.Remote -> artwork.url.takeIf { it.isNotBlank() }?.let(Uri::parse)
        is ArtworkRef.Owned, is ArtworkRef.Catalogue, null -> null
    }

    /** `true` tells the Auto host this app sets content-style hints at all. */
    private const val EXTRA_CONTENT_STYLE_SUPPORTED: String = "android.media.browse.CONTENT_STYLE_SUPPORTED"

    /** How browsable rows - albums, artists, playlists - should be drawn. */
    private const val EXTRA_CONTENT_STYLE_BROWSABLE: String =
        "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"

    /** How playable rows - songs - should be drawn. */
    private const val EXTRA_CONTENT_STYLE_PLAYABLE: String = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

    /** A list of text rows. */
    private const val CONTENT_STYLE_LIST: Int = 1

    /** A grid of artwork tiles. */
    private const val CONTENT_STYLE_GRID: Int = 2
}
