package app.needler.core.domain.usecase

import app.needler.core.domain.model.Album
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.SessionState
import app.needler.core.domain.model.UnifiedSearchResults
import app.needler.core.domain.repository.SearchRepository
import app.needler.core.domain.repository.SessionRepository
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One search field over both server lanes.
 *
 * The two lanes have very different latencies, so they are run independently and merged as results
 * arrive rather than awaited together:
 *
 * 1. Local FTS over the mirror emits on the first keystroke with no network call at all.
 * 2. After [DefaultCatalogueDebounce], the catalogue lane is queried through the server.
 * 3. Results are merged on release-group MBID. **An album present locally takes the local record** -
 *    it knows the real [app.needler.core.domain.model.AlbumState], track count, size and format - and
 *    the catalogue copy is discarded, so nothing appears twice.
 * 4. Offline, or with an expired session, only library results are shown and
 *    [UnifiedSearchResults.catalogue] says why the other lane is missing.
 *
 * This and `LibraryRepository.observeArtistDiscography` are the only two places in the codebase that
 * know both lanes exist.
 */
public class UnifiedSearchUseCase(
    private val searchRepository: SearchRepository,
    private val sessionRepository: SessionRepository,
) {

    /**
     * Runs a unified search for [query].
     *
     * Emits at least once per lane update: local results first, then a [CatalogueLaneState.Loading]
     * snapshot, then the merged result. Collect this per committed query string; cancelling the
     * collection cancels the in-flight catalogue call.
     */
    public operator fun invoke(
        query: String,
        catalogueDebounce: Duration = DefaultCatalogueDebounce,
        localLimit: Int = DefaultLocalLimit,
        catalogueArtistLimit: Int = DefaultCatalogueArtistLimit,
        catalogueAlbumLimit: Int = DefaultCatalogueAlbumLimit,
    ): Flow<UnifiedSearchResults> {
        val trimmed: String = query.trim()
        if (trimmed.length < MinQueryLength) {
            return flowOf(UnifiedSearchResults(query = trimmed, catalogue = CatalogueLaneState.Idle))
        }

        return channelFlow {
            val guard = Mutex()
            var local = LocalSearchResults(query = trimmed)
            var catalogue: CatalogueSearchResults? = null
            var lane: CatalogueLaneState = CatalogueLaneState.Idle
            val out: SendChannel<UnifiedSearchResults> = channel

            // Fast lane: the mirror. Never fails, so it is collected for as long as the caller listens.
            launch {
                searchRepository.searchLocal(trimmed, localLimit).collect { results ->
                    val snapshot: UnifiedSearchResults = guard.withLock {
                        local = results
                        merge(trimmed, local, catalogue, lane)
                    }
                    out.send(snapshot)
                }
            }

            // Slow lane: the catalogue, debounced, allowed to fail without taking the fast lane down.
            launch {
                delay(catalogueDebounce)

                val blocker: NeedlerError? = catalogueLaneBlocker()
                if (blocker != null) {
                    val snapshot: UnifiedSearchResults = guard.withLock {
                        lane = CatalogueLaneState.Unavailable(blocker)
                        merge(trimmed, local, catalogue, lane)
                    }
                    out.send(snapshot)
                    return@launch
                }

                val loading: UnifiedSearchResults = guard.withLock {
                    lane = CatalogueLaneState.Loading
                    merge(trimmed, local, catalogue, lane)
                }
                out.send(loading)

                val result: Outcome<CatalogueSearchResults> = searchRepository.searchCatalogue(
                    query = trimmed,
                    limitArtists = catalogueArtistLimit,
                    limitAlbums = catalogueAlbumLimit,
                )
                val snapshot: UnifiedSearchResults = guard.withLock {
                    when (result) {
                        is Outcome.Success -> {
                            catalogue = result.value
                            lane = CatalogueLaneState.Ready(result.value.serviceStatus)
                        }
                        is Outcome.Failure -> {
                            lane = CatalogueLaneState.Unavailable(result.error)
                        }
                    }
                    merge(trimmed, local, catalogue, lane)
                }
                out.send(snapshot)
            }
        }
    }

    /**
     * Returns the error that makes the catalogue lane unusable right now, or null when it may be
     * tried.
     *
     * Offline is reported as offline even when the session is also stale, because that is the more
     * actionable message: signing in again would not help without a network.
     */
    private suspend fun catalogueLaneBlocker(): NeedlerError? {
        if (!sessionRepository.currentConnectivity().isOnline) {
            return NeedlerError.Offline()
        }
        val state: SessionState = sessionRepository.currentSession()
        if (state.canUseCatalogueLane) return null
        return when (state) {
            is SessionState.PlayerOnly -> NeedlerError.SessionExpired
            is SessionState.ReonboardingRequired -> NeedlerError.AppPasswordRevoked()
            SessionState.NotConfigured -> NeedlerError.CapabilityUnavailable("no server configured")
            is SessionState.SubsonicDisabled -> NeedlerError.SubsonicProtocolDisabled
            is SessionState.Authenticated -> null
        }
    }

    public companion object {
        /** The debounce before the catalogue lane is queried, per the search requirements. */
        public val DefaultCatalogueDebounce: Duration = 300.milliseconds

        public const val MinQueryLength: Int = 1
        public const val DefaultLocalLimit: Int = 50
        public const val DefaultCatalogueArtistLimit: Int = 10
        public const val DefaultCatalogueAlbumLimit: Int = 20

        /**
         * Merges the two lanes on release-group MBID (albums) and artist MBID (artists).
         *
         * The local record always wins: it is the one that knows whether the album is owned, pinned or
         * being acquired. Catalogue entries survive only when the mirror has never heard of them.
         *
         * Pure and public so it can be tested without coroutines.
         */
        public fun merge(
            query: String,
            local: LocalSearchResults,
            catalogue: CatalogueSearchResults?,
            lane: CatalogueLaneState,
        ): UnifiedSearchResults {
            if (catalogue == null) {
                return UnifiedSearchResults(
                    query = query,
                    artists = local.artists,
                    albums = local.albums,
                    tracks = local.tracks,
                    catalogue = lane,
                )
            }

            val ownedMbids: Set<ReleaseGroupMbid> =
                local.albums.mapTo(LinkedHashSet<ReleaseGroupMbid>()) { album ->
                    album.releaseGroupMbid
                }
            val mergedAlbums: MutableList<Album> =
                ArrayList<Album>(local.albums.size + catalogue.albums.size)
            mergedAlbums.addAll(local.albums)
            for (candidate in catalogue.albums) {
                if (!ownedMbids.contains(candidate.releaseGroupMbid)) {
                    mergedAlbums.add(candidate)
                }
            }

            val knownArtists: Set<ArtistMbid> =
                local.artists.mapTo(LinkedHashSet<ArtistMbid>()) { artist ->
                    artist.mbid
                }
            val mergedArtists: MutableList<Artist> =
                ArrayList<Artist>(local.artists.size + catalogue.artists.size)
            mergedArtists.addAll(local.artists)
            for (candidate in catalogue.artists) {
                if (!knownArtists.contains(candidate.mbid)) {
                    mergedArtists.add(candidate)
                }
            }

            return UnifiedSearchResults(
                query = query,
                artists = mergedArtists,
                albums = mergedAlbums,
                // Catalogue search returns artists and albums only; tracks are library-only.
                tracks = local.tracks,
                catalogue = lane,
            )
        }
    }
}
