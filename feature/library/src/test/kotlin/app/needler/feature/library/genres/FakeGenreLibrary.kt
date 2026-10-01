package app.needler.feature.library.genres

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackListKind
import app.needler.core.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A [LibraryRepository] that answers the two genre queries and nothing else.
 *
 * `Fakes.kt`'s `FakeLibraryRepository` throws from both of them — "not used by
 * `:feature:library`", which was true until these screens existed — and this work
 * is not allowed to edit that file. So this is a second fake with the opposite
 * emphasis: the genre queries are real and scriptable, and every other member
 * throws.
 *
 * Throwing rather than returning a plausible empty list is the convention `Fakes.kt`
 * set and the reason it gives is worth repeating: "a fake that silently returns a
 * plausible default for a method under test is a test that passes for the wrong
 * reason." If a genre screen ever calls `observeAlbumList`, a test should fail
 * loudly and a reviewer should ask why.
 *
 * When this and `FakeLibraryRepository` meet, one fake with both halves is the
 * answer; it is in the handover notes.
 */
internal class FakeGenreLibrary(
    genres: List<Genre> = emptyList(),
) : LibraryRepository {

    val genreList = MutableStateFlow(genres)
    val tracksByGenre = MutableStateFlow<Map<String, List<Track>>>(emptyMap())

    /** Every `observeTracksByGenre` subscription, as (genre, limit) pairs. */
    val genreQueries: MutableList<Pair<String, Int>> = mutableListOf()

    override fun observeGenres(limit: Int, offset: Int): Flow<List<Genre>> =
        genreList.map { it.drop(offset).take(limit) }

    override fun observeTracksByGenre(genre: String, limit: Int, offset: Int): Flow<List<Track>> {
        genreQueries += genre to limit
        return tracksByGenre.map { it[genre].orEmpty() }
    }

    // ---- everything these screens must not touch ----------------------------

    override fun observeArtists(limit: Int, offset: Int): Flow<List<Artist>> =
        error("not used by the genre screens")

    override fun observeArtist(mbid: ArtistMbid): Flow<Artist?> =
        error("not used by the genre screens")

    override fun observeOwnedAlbumsByArtist(mbid: ArtistMbid): Flow<List<Album>> =
        error("not used by the genre screens")

    override fun observeArtistDiscography(mbid: ArtistMbid): Flow<List<Album>> =
        error("not used by the genre screens")

    override fun observeAlbum(mbid: ReleaseGroupMbid): Flow<Album?> =
        error("not used by the genre screens")

    override fun observeAlbumTracks(mbid: ReleaseGroupMbid): Flow<List<Track>> =
        error("not used by the genre screens")

    override fun observeAlbumList(
        kind: AlbumListKind,
        limit: Int,
        offset: Int,
    ): Flow<List<Album>> = error("not used by the genre screens")

    override fun observeTracks(
        kind: TrackListKind,
        limit: Int,
        offset: Int,
    ): Flow<List<Track>> = error("not used by the genre screens")

    override fun observeLibraryStats(): Flow<LibraryStats> = error("not used by the genre screens")

    override suspend fun getTrack(key: TrackKey): Track? = error("not used by the genre screens")

    override suspend fun getTracks(keys: List<TrackKey>): List<Track> =
        error("not used by the genre screens")

    override suspend fun getAlbum(mbid: ReleaseGroupMbid): Album? =
        error("not used by the genre screens")

    override suspend fun refreshArtistDiscography(mbid: ArtistMbid): Outcome<Unit> =
        error("not used by the genre screens")

    override suspend fun refreshAlbum(mbid: ReleaseGroupMbid): Outcome<Unit> =
        error("not used by the genre screens")
}

/**
 * The genres these tests use.
 *
 * Deliberately not in alphabetical order: the repository is what sorts, and a test
 * that handed the screen a sorted list could never catch a screen that sorted
 * again — or one that reordered by album count and looked plausible doing it.
 */
internal object SampleGenres {

    val soul: Genre = Genre(name = "Soul", albumCount = 12)
    val dreamPop: Genre = Genre(name = "Dream pop", albumCount = 4)
    val jazz: Genre = Genre(name = "Jazz", albumCount = 1)

    /** One with no counts at all, which the mirror can legitimately produce. */
    val uncounted: Genre = Genre(name = "Shoegaze")

    val all: List<Genre> = listOf(soul, dreamPop, jazz, uncounted)
}
