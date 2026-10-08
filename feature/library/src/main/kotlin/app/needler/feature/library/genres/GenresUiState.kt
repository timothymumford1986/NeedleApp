package app.needler.feature.library.genres

import app.needler.core.domain.model.Genre
import app.needler.feature.library.common.LibraryFormat

/**
 * Everything the genres list renders.
 *
 * REQUIREMENTS.md "Library browse" gives this screen one row in its table:
 * Genres, from `getGenres` and `getSongsByGenre`, ordered **alphabetically**. The
 * ordering is not applied here — `LibraryRepository.observeGenres` sorts
 * case-insensitively on the way out of the mirror, so a screen that sorted again
 * would be a second opinion about the same list, and the two would drift the day
 * one of them learned about diacritics.
 *
 * ## Why there is no error field and no refresh
 *
 * Genres are not fetched. They are derived from the genre column the album mirror
 * already carries, so this screen has nothing to call and nothing to fail:
 * "All library browsing reads the local metadata mirror, so it works identically
 * online and offline." [offline] is therefore a fact the screen states plainly,
 * not a failure, and a genre list with no connection is the same genre list.
 *
 * The consequence worth knowing is that a genre exists here only once the album
 * carrying it has been synced. That is a property of the mirror rather than of
 * this screen, and it is the same for every browse surface in the module.
 */
data class GenresUiState(

    /** True until the mirror has answered once. */
    val loading: Boolean = true,

    /** Alphabetical, as the repository ordered them. */
    val genres: List<Genre> = emptyList(),

    /** The server is unreachable. Browsing is unaffected; the screen says so. */
    val offline: Boolean = false,
) {

    /** The mirror answered and had nothing. */
    val showEmptyState: Boolean get() = !loading && genres.isEmpty()

    /** `24 genres`, the count under the screen title. */
    val countLine: String get() = LibraryFormat.plural(genres.size.toLong(), "genre")
}

/**
 * `12 albums`, the figure beside a genre. Never blank.
 *
 * Albums and not tracks, because that is the number the mirror actually knows: a
 * genre is denormalised onto the album row, so counting albums is exact while
 * counting tracks would mean a second query per genre. `Genre.trackCount` is part
 * of the model and is used when the server has filled it in, which today it does
 * not.
 *
 * ## Zero is a count; unknown is a sentence
 *
 * This lumped the two together under "Unknown is not zero" and returned null for both, which
 * `screenshots/genres-phone.png` shows as a bare gap where Shoegaze's figure should be — against
 * `screenshots/genre-empty-phone.png`, which says `0 tracks` for the very same genre. One
 * emptiness, drawn twice, and one of the two drawings looks like a rendering fault rather than a
 * fact. `LibraryFormat`'s rule is about not *inventing* a zero, and a counted zero is not invented:
 * `DefaultLibraryRepository.observeGenres` builds every count by folding the album mirror's genre
 * column, so a genre it returns has a real number behind it.
 *
 * So a known count is drawn whatever it is, and the unknown case — which that query cannot
 * actually produce, and which a hand-built `Genre` can — says so in words rather than drawing
 * nothing. A row with an empty trailing column is the one outcome this function must not have.
 */
internal fun genreRowValue(genre: Genre): String {
    val albums: Int? = genre.albumCount
    val tracks: Int? = genre.trackCount
    return when {
        albums != null -> LibraryFormat.plural(albums.toLong(), "album")
        tracks != null -> LibraryFormat.plural(tracks.toLong(), "track")
        else -> COUNT_UNKNOWN
    }
}

/** What a genre whose count nothing supplied says instead of nothing at all. */
internal const val COUNT_UNKNOWN: String = "Count unknown"
