package app.needler.feature.library.common

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.needler.core.design.component.NeedlerRequestSheetOverlay
import app.needler.core.design.theme.NeedlerTheme
import app.needler.core.domain.model.Album

/**
 * The pull sheet while it is open, and what it is about to request.
 *
 * REQUIREMENTS.md "Placing a request": "A `monitor_artist` flag on the request body subscribes the
 * user to that artist's future releases. Expose it as a secondary toggle on the request sheet, since
 * it is cheap here and would otherwise need the deferred following screens." `RequestAlbumUseCase`
 * has taken that flag from the beginning and no caller passed it, because there was no sheet to put
 * the toggle on. This type is the sheet's whole state.
 *
 * ## One type for one album and for a whole artist
 *
 * [albums] is a list rather than a single [Album] because the artist screen needs the second case.
 * An artist reached from catalogue search is a screen with nothing owned on it, and before this it
 * offered nothing but Back — REQUIREMENTS.md "Scope" lists requesting missing albums as one of v1's
 * four jobs, and that route was a cul-de-sac. Asking for every un-owned release group by the artist
 * is the only thing the server understands as "get me this artist": there is no artist request
 * endpoint, only `POST /api/v1/requests/new` and `POST /api/v1/requests/batch`.
 *
 * A list of one and a list of many therefore take different routes at confirm time, and
 * `RequestAlbumUseCase` already has both — `invoke` and `requestAll`, each taking `monitorArtist`.
 *
 * ## Why the albums are held here rather than looked up on confirm
 *
 * The request body carries the title, the artist and the year as hints the server uses to
 * disambiguate a thin MusicBrainz entry, and both screens already have the [Album] in hand when the
 * row is tapped. Holding it means confirming does not re-read the mirror — and, more to the point,
 * it means the sheet cannot end up requesting a different record from the one whose title it is
 * showing, which a lookup by id at confirm time would allow after a sync landed underneath.
 *
 * @property title what the sheet names: a record, or "Everything by <artist>".
 * @property subtitle the line under it: the artist for one album, the count for a batch.
 * @property artistName the artist the `monitor_artist` toggle names, when known.
 * @property monitorArtist the current value of the toggle. Hoisted to the ViewModel rather than
 *   remembered inside the sheet so that it survives a rotation mid-decision and so that a test can
 *   assert the flag actually reached the request.
 */
data class RequestSheetState(
    val title: String,
    val subtitle: String?,
    val artistName: String?,
    val albums: List<Album>,
    val monitorArtist: Boolean = false,
    /** The server's own quality policy for this request, when it sent one. */
    val qualityNote: String? = null,
    /** Artwork for the sheet, when the request is about one record. */
    val artwork: Album? = null,
) {

    /** True when this sheet asks for a whole artist rather than one record. */
    val isArtistWide: Boolean get() = albums.size != 1

    /** The primary button's label. A batch says how many, because 40 albums is not one tap's worth. */
    val confirmLabel: String
        get() = if (isArtistWide) {
            "Pull " + albums.size
        } else {
            "Pull"
        }

    companion object {

        /** One record, from the album screen or from a discography row. */
        fun forAlbum(album: Album): RequestSheetState = RequestSheetState(
            title = LibraryFormat.albumTitle(album.title),
            subtitle = album.artistName.trim().takeIf { it.isNotEmpty() },
            artistName = album.artistName.trim().takeIf { it.isNotEmpty() },
            albums = listOf(album),
            qualityNote = album.qualityPolicySummary,
            artwork = album,
        )

        /**
         * Everything the catalogue knows of an artist that the library does not have.
         *
         * No artwork slot: a batch is not one record and picking one of its covers to stand for the
         * rest would suggest the sheet was about that record.
         */
        fun forArtist(artistName: String?, albums: List<Album>): RequestSheetState {
            val name: String = LibraryFormat.artistLabel(artistName)
            return RequestSheetState(
                title = "Everything by " + name,
                subtitle = LibraryFormat.plural(albums.size.toLong(), "album") +
                    " Dropped Needle can look for",
                artistName = artistName?.trim()?.takeIf { it.isNotEmpty() },
                albums = albums,
            )
        }
    }
}

/**
 * Draws [RequestSheetState] over whatever it is placed in.
 *
 * One renderer for both screens. The album screen and the artist screen open the same sheet for the
 * same reason — REQUIREMENTS.md "Placing a request" puts the `monitor_artist` toggle on it — and two
 * call sites assembling the same eight arguments is how one of them ends up not passing the flag,
 * which is the exact fault this whole change exists to fix.
 *
 * `:feature:search` will call [NeedlerRequestSheetOverlay] directly rather than this, because it has
 * no [RequestSheetState] of its own; that is why the mapping from a domain type to strings lives here
 * and not in `:core:design`.
 */
@Composable
internal fun RequestSheet(
    sheet: RequestSheetState,
    busy: Boolean,
    onMonitorArtistChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Declared with its type rather than built with `?.let`: the slot is a
    // `(@Composable () -> Unit)?`, and a lambda produced inside `let` loses the annotation and stops
    // type-checking against it.
    val cover: Album? = sheet.artwork
    val artwork: (@Composable () -> Unit)? = if (cover == null) {
        null
    } else {
        {
            AlbumArtwork(
                album = cover,
                modifier = Modifier.size(NeedlerTheme.sizes.artworkRow),
                shape = NeedlerTheme.shapes.artworkThumb,
                decorative = true,
            )
        }
    }
    NeedlerRequestSheetOverlay(
        title = sheet.title,
        subtitle = sheet.subtitle,
        monitorArtist = sheet.monitorArtist,
        onMonitorArtistChange = onMonitorArtistChange,
        onConfirm = onConfirm,
        onCancel = onCancel,
        modifier = modifier,
        // The toggle names the artist, which for an artist-wide pull is in the title rather than the
        // subtitle - the subtitle there is a count.
        monitorArtistSubject = sheet.artistName,
        confirmLabel = sheet.confirmLabel,
        qualityNote = sheet.qualityNote,
        busy = busy,
        artwork = artwork,
    )
}
