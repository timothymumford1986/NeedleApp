package app.needler.feature.library.artist

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.feature.library.album.AlbumNotice

/**
 * Everything the artist screen renders.
 *
 * REQUIREMENTS.md "Library browse": "Artist detail is where the two lanes meet
 * visibly. It shows owned albums from the mirror and the artist's full
 * discography from `GET /api/v1/artists/{mbid}/releases`, with everything
 * un-owned carrying a request action." So the two lists are separate fields
 * rather than one sorted list: they come from different places, they fail
 * independently, and only one of them needs a connection.
 *
 * The design pack draws no artist screen. The layout is therefore built from
 * the pack's own parts — the section header from screens 03 and 06, the album
 * row from 13, the Pull pill from 03 — rather than invented.
 */
data class ArtistUiState(

    val loading: Boolean = true,

    val artist: Artist? = null,

    /** From the mirror. Present with or without a connection. */
    val ownedAlbums: List<Album> = emptyList(),

    /**
     * The rest of the discography, from the catalogue lane, already stripped of
     * anything in [ownedAlbums] by release-group MBID.
     */
    val catalogueAlbums: List<Album> = emptyList(),

    /**
     * True when the catalogue half could not be fetched — no connection, or an
     * expired companion bearer.
     *
     * The owned half is unaffected, which is the whole point of splitting them:
     * an offline artist screen still lists everything you own by that artist.
     */
    /**
     * Why the catalogue half could not be fetched, or `null` when it could.
     *
     * This is the error and not a flag because [NeedlerError]'s own KDoc says the distinctions
     * matter to the UI. A 404 for an artist the catalogue has never heard of, a 500, and a read
     * timeout are three different things to be told, and collapsing them to one sentence made a
     * failure that happens on every artist impossible to tell apart from one that happens on one.
     */
    val discographyError: NeedlerError? = null,

    val offline: Boolean = false,

    val busy: Boolean = false,

    val notice: AlbumNotice? = null,
) {

    val discographyUnavailable: Boolean get() = discographyError != null

    val notFound: Boolean get() = !loading && artist == null

    val hasNothing: Boolean
        get() = ownedAlbums.isEmpty() && catalogueAlbums.isEmpty()

    /** `12 albums · 9 more to pull`, or just `12 albums` when the catalogue is unknown. */
    val subtitle: String
        get() {
            val owned: String = if (ownedAlbums.size == 1) "1 album" else ownedAlbums.size.toString() + " albums"
            return if (catalogueAlbums.isEmpty()) {
                owned
            } else {
                owned + " · " + catalogueAlbums.size + " more to pull"
            }
        }
}
