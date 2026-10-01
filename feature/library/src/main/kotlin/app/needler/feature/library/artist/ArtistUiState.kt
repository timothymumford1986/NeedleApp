package app.needler.feature.library.artist

import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Track
import app.needler.feature.library.album.AlbumNotice
import app.needler.feature.library.common.LibraryFormat
import app.needler.feature.library.common.RequestSheetState

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

    /**
     * The artist's name as the caller already knew it, for an artist the mirror has
     * no row for.
     *
     * Catalogue search returns artists the library does not own, labels them "Not in
     * your library yet" and lets the user tap one. `refreshArtistDiscography` writes
     * the *albums* it finds into the mirror and never an artist row, so
     * [Artist] stays null on that path and the screen drew "That artist is not here"
     * over a discography it had just fetched — having never once said the name the
     * user tapped.
     *
     * The search result already carried the name, so the screen need not wait for a
     * mirror row before rendering anything. Taking it as a hint is what makes an
     * artist screen possible for an artist nobody owns.
     */
    val knownName: String? = null,

    /** The subtitle the caller already knew, e.g. search's "Not in your library yet". */
    val knownSubtitle: String? = null,

    /** From the mirror. Present with or without a connection. */
    val ownedAlbums: List<Album> = emptyList(),

    /**
     * The rest of the discography, from the catalogue lane, already stripped of
     * anything in [ownedAlbums] by release-group MBID.
     */
    val catalogueAlbums: List<Album> = emptyList(),

    /**
     * Why the catalogue half could not be fetched, or `null` when it could.
     *
     * The owned half is unaffected whatever this says, which is the whole point of splitting
     * the two lists: an offline artist screen still lists everything you own by that artist.
     *
     * This is the error and not a flag because [NeedlerError]'s own KDoc says the distinctions
     * matter to the UI. A 404 for an artist the catalogue has never heard of, a 500, and a read
     * timeout are three different things to be told, and collapsing them to one sentence made a
     * failure that happens on every artist impossible to tell apart from one that happens on one.
     */
    val discographyError: NeedlerError? = null,

    /**
     * True when this artist's id cannot reach the catalogue at all, because
     * DroppedNeedle derived it from their name rather than matching them to
     * MusicBrainz.
     *
     * A separate field from [discographyError] because it is a different fact with
     * a different answer. An error means "not this time" — offline, a 500, a
     * timeout — and the screen invites the user to come back. This means "not
     * ever", for this artist, on this server, and the screen has to say so
     * plainly. Conflating them was the shape of the bug: every name-derived artist
     * logged `Rejected 400: Use the local library artist route for a DroppedNeedle
     * artist ID` and showed a silently short list, which reads on a device as an
     * artist with two albums rather than as an artist whose discography was never
     * available.
     *
     * See `ArtistMbid.isNameDerived`, which is where the identity question is
     * answered.
     */
    val artistNotInCatalogue: Boolean = false,

    val offline: Boolean = false,

    val busy: Boolean = false,

    val notice: AlbumNotice? = null,

    /** The pull sheet, open, or null. Same sheet the album screen opens. */
    val requestSheet: RequestSheetState? = null,

    /**
     * Tracks of every owned album, in the order the owned list is drawn, for Play
     * and Shuffle.
     *
     * Held in state rather than fetched on the tap so that the buttons can be
     * disabled honestly: an artist whose owned albums are all part-delivered has
     * nothing to play, and a Play button that did nothing would be worse than one
     * that is visibly unavailable.
     */
    val playableTracks: List<Track> = emptyList(),
) {

    val discographyUnavailable: Boolean get() = discographyError != null || artistNotInCatalogue

    /**
     * Truly nothing to show: no mirror row, no name from the caller, and no albums of
     * either kind.
     *
     * It used to be `artist == null` alone, which is what made an artist reached from
     * catalogue search a dead end. That artist has no mirror row by definition — the
     * discography refresh writes album rows and never an artist row — so the screen
     * declared them absent while holding their whole discography, and blamed the
     * network for it. The empty screen is now reserved for the case where there is
     * genuinely nothing: not a name, not a record.
     */
    val notFound: Boolean
        get() = !loading && artist == null && knownName == null && hasNothing

    val hasNothing: Boolean
        get() = ownedAlbums.isEmpty() && catalogueAlbums.isEmpty()

    /** Whether this artist is starred. Joined onto [Artist] by the mirror, like an album's. */
    val isFavourite: Boolean get() = artist?.isFavourite == true

    /**
     * The name to draw: the mirror's, the caller's hint, or `Unknown artist`.
     *
     * Never blank and never empty, so the header always names something. A screen you
     * reached by tapping a name and which then does not repeat it gives you no way to
     * confirm you opened the right one.
     */
    val displayName: String get() = LibraryFormat.artistName(artist?.name ?: knownName)

    /** The name to put inside a sentence, e.g. a content description. */
    val spokenName: String get() = LibraryFormat.artistLabel(artist?.name ?: knownName)

    /** True when the mirror has no row for this artist and the screen is drawn from hints. */
    val fromHintsOnly: Boolean get() = artist == null && knownName != null

    /**
     * True when there is something for the server to be asked for.
     *
     * Gate on the artist-wide **Pull** action. It is a real answer rather than a
     * hopeful one: there is no artist request endpoint — REQUIREMENTS.md "Placing a
     * request" lists album, track and batch — so "pull this artist" can only mean
     * "ask for every un-owned release group we know of", and with none known there is
     * nothing to send. The screen then offers a retry of the discography instead,
     * which is an action that can actually succeed.
     */
    val canPullArtist: Boolean get() = catalogueAlbums.any { it.state == AlbumState.NotOwned }

    /** The un-owned release groups an artist-wide pull would ask for. */
    val pullableAlbums: List<Album>
        get() = catalogueAlbums.filter { it.state == AlbumState.NotOwned }

    /**
     * True when the screen has nothing on it but a name, and is therefore only worth
     * looking at if it offers a way forward.
     *
     * Drives the retry. An artist whose id can never reach the catalogue is excluded:
     * retrying that is retrying a `400`.
     */
    val canRetryDiscography: Boolean
        get() = hasNothing && !artistNotInCatalogue && !loading

    /**
     * True when there is something to press Play on.
     *
     * The list is already filtered to tracks with a file behind them, which is the
     * whole check: an artist with owned albums and no playable file in any of them —
     * every record a part-delivered pull — has nothing to play, and REQUIREMENTS.md
     * "Partial content is a normal state" says that is a state to expect rather than an
     * edge case.
     */
    val canPlay: Boolean get() = playableTracks.isNotEmpty()

    /**
     * `12 albums · 9 more to pull`, or just `12 albums` when the catalogue is unknown.
     *
     * With nothing owned and nothing in the catalogue it falls back to whatever the
     * caller knew — search's own "Not in your library yet" — rather than reading
     * `0 albums`, which states a fact about the library as though it were a fact about
     * the artist.
     */
    val subtitle: String
        get() {
            if (ownedAlbums.isEmpty() && catalogueAlbums.isEmpty()) {
                return knownSubtitle ?: NOT_IN_LIBRARY_YET
            }
            val owned: String = LibraryFormat.plural(ownedAlbums.size.toLong(), "album")
            return if (catalogueAlbums.isEmpty()) {
                owned
            } else {
                owned + " · " + catalogueAlbums.size + " more to pull"
            }
        }

    companion object {
        /** The words catalogue search itself uses for an artist the library does not have. */
        const val NOT_IN_LIBRARY_YET: String = "Not in your library yet"
    }
}
