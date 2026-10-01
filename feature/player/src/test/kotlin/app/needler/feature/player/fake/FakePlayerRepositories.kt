package app.needler.feature.player.fake

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumListKind
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.FavouriteTarget
import app.needler.core.domain.model.Favourites
import app.needler.core.domain.model.Genre
import app.needler.core.domain.model.LibraryStats
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.Track
import app.needler.core.domain.model.TrackKey
import app.needler.core.domain.model.TrackListKind
import app.needler.core.domain.repository.FavouriteRepository
import app.needler.core.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// The two repositories the player reads besides the session. Both are faked here rather than beside the
// screens they serve because the player needs exactly two narrow things from them - is this track
// starred, and who is this album's artist - and a test of the transport should not have to stand up a
// Room database to ask either.

/**
 * An in-memory [FavouriteRepository].
 *
 * It behaves the way the real one does in the respect that matters to the player: [setFavourite] writes
 * the local answer **before** anything else, so the heart fills whatever the network is doing, which is
 * the mirror-first write REQUIREMENTS.md's offline story depends on. [result] is the seam for the one
 * failure worth drawing - a permanent rejection, which leaves the mirror starred and the server not.
 */
class FakeFavouriteRepository(
    starred: Set<String> = emptySet(),
) : FavouriteRepository {

    private val starredKeys: MutableStateFlow<Set<String>> = MutableStateFlow(starred)

    /** Every star and unstar, in order, as `key=starred`. */
    val calls: MutableList<String> = mutableListOf()

    /** What [setFavourite] returns. A failure is a permanent server rejection. */
    var result: Outcome<Unit> = Outcome.Ok

    override fun observeFavourites(): Flow<Favourites> = MutableStateFlow(Favourites())

    override fun observeIsFavourite(target: FavouriteTarget): Flow<Boolean> =
        starredKeys.map { keys -> keyOf(target) in keys }

    override suspend fun setFavourite(target: FavouriteTarget, starred: Boolean): Outcome<Unit> {
        val key: String = keyOf(target)
        calls += key + "=" + starred
        // Local first, unconditionally, exactly as DefaultFavouriteRepository does: the outcome below
        // describes the server, not the mirror.
        starredKeys.value = if (starred) starredKeys.value + key else starredKeys.value - key
        return result
    }

    override suspend fun refreshFavourites(): Outcome<Unit> = Outcome.Ok

    private fun keyOf(target: FavouriteTarget): String = when (target) {
        is FavouriteTarget.OfAlbum -> "album/" + target.releaseGroupMbid.value
        is FavouriteTarget.OfArtist -> "artist/" + target.mbid.value
        is FavouriteTarget.OfTrack -> "track/" + target.key.releaseGroupMbid.value +
            "/" + target.key.discNumber + "/" + target.key.trackNumber
    }

    companion object {
        /** The key [FakeFavouriteRepository] stores a track under, for seeding a starred track. */
        fun trackKey(key: TrackKey): String =
            "track/" + key.releaseGroupMbid.value + "/" + key.discNumber + "/" + key.trackNumber

        /** A refusal the player has to say out loud rather than swallow. */
        val Refused: Outcome<Unit> = Outcome.Failure(NeedlerError.PermissionDenied())
    }
}

/**
 * A [LibraryRepository] that answers one question: which artist made this album.
 *
 * That is all the player asks it, and the members it does not ask for throw rather than returning empty -
 * the same convention `:feature:library`'s own fake uses, so a test that starts depending on one of them
 * fails loudly instead of quietly asserting against an empty list.
 */
class FakeLibraryRepository(
    albums: Map<String, Album> = emptyMap(),
) : LibraryRepository {

    private val albumsByMbid: MutableStateFlow<Map<String, Album>> = MutableStateFlow(albums)

    /** Every album the player looked up, so a test can assert it resolves once per track. */
    val lookups: MutableList<String> = mutableListOf()

    override fun observeAlbum(mbid: ReleaseGroupMbid): Flow<Album?> {
        lookups += mbid.value
        return albumsByMbid.map { it[mbid.value] }
    }

    /** Publishes an album the player will then resolve an artist from. */
    fun emit(album: Album) {
        albumsByMbid.value = albumsByMbid.value + (album.releaseGroupMbid.value to album)
    }

    override fun observeArtists(): Flow<List<Artist>> = notUsed()

    override fun observeArtist(mbid: ArtistMbid): Flow<Artist?> = notUsed()

    override fun observeOwnedAlbumsByArtist(mbid: ArtistMbid): Flow<List<Album>> = notUsed()

    override fun observeArtistDiscography(mbid: ArtistMbid): Flow<List<Album>> = notUsed()

    override fun observeAlbumTracks(mbid: ReleaseGroupMbid): Flow<List<Track>> = notUsed()

    override fun observeAlbumList(kind: AlbumListKind, limit: Int, offset: Int): Flow<List<Album>> =
        notUsed()

    override fun observeTracks(kind: TrackListKind, limit: Int, offset: Int): Flow<List<Track>> =
        notUsed()

    override fun observeGenres(): Flow<List<Genre>> = notUsed()

    override fun observeTracksByGenre(genre: String, limit: Int, offset: Int): Flow<List<Track>> =
        notUsed()

    override fun observeLibraryStats(): Flow<LibraryStats> = notUsed()

    override suspend fun getTrack(key: TrackKey): Track? = notUsed()

    override suspend fun getTracks(keys: List<TrackKey>): List<Track> = notUsed()

    override suspend fun getAlbum(mbid: ReleaseGroupMbid): Album? = albumsByMbid.value[mbid.value]

    override suspend fun refreshArtistDiscography(mbid: ArtistMbid): Outcome<Unit> = Outcome.Ok

    override suspend fun refreshAlbum(mbid: ReleaseGroupMbid): Outcome<Unit> = Outcome.Ok

    private fun notUsed(): Nothing = error("not used by :feature:player")
}
