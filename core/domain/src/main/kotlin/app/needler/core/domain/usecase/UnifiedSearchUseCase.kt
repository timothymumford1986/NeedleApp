package app.needler.core.domain.usecase

import app.needler.core.domain.NameKeys
import app.needler.core.domain.model.Album
import app.needler.core.domain.model.AlbumState
import app.needler.core.domain.model.Artist
import app.needler.core.domain.model.CatalogueLaneState
import app.needler.core.domain.model.CatalogueSearchPage
import app.needler.core.domain.model.CatalogueSearchResults
import app.needler.core.domain.model.LocalSearchResults
import app.needler.core.domain.model.NeedlerError
import app.needler.core.domain.model.Outcome
import app.needler.core.domain.model.ReleaseGroupMbid
import app.needler.core.domain.model.ArtistMbid
import app.needler.core.domain.model.SearchBucket
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
 *    the catalogue copy is discarded, so nothing appears twice. Where the two lanes disagree about
 *    the id of one record - which a device proved they do - [mergeAlbums] has a second rule for it.
 *    Artists get the same treatment with the artist MBID as the join key, plus the name-collapse
 *    [mergeArtists] explains.
 * 4. Offline, or with an expired session, only library results are shown and
 *    [UnifiedSearchResults.catalogue] says why the other lane is missing.
 *
 * What the mirror returned always sorts above what only the catalogue knows, in both lists. The
 * library is the music the user owns and can play right now; the catalogue is a shopping list, and a
 * result order that buries the first under the second has the product backwards.
 *
 * ## Why the catalogue lane is one call and not two
 *
 * REQUIREMENTS.md requires that "results must stream in progressively rather than blocking on the
 * slowest bucket", and rule 2 names a single endpoint that answers with both buckets at once. Both
 * hold here because the streaming that matters is between the *lanes*: the mirror's answer is on
 * screen while the catalogue call is still open, and it is never withheld waiting for it. Splitting
 * the catalogue call into a per-bucket artists call and a per-bucket albums call would stream the two
 * buckets independently but abandon rule 2's endpoint and double the upstream MusicBrainz traffic for
 * every keystroke that survives the debounce, so rule 2 wins. The per-bucket endpoint is used for
 * what rule 5 asks of it, paging - see [expand].
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

            // Unreachable: an app-password repair leaves the catalogue lane usable, so the guard
            // above has already returned. Listed rather than folded into an `else` so that a new
            // state cannot slip through this branch unexamined.
            is SessionState.RepairingAppPassword -> null
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
        ): UnifiedSearchResults = UnifiedSearchResults(
            query = query,
            artists = mergeArtists(query, local.artists, catalogue?.artists.orEmpty()),
            albums = mergeAlbums(local.albums, catalogue?.albums.orEmpty()),
            // Catalogue search returns artists and albums only; tracks are library-only.
            tracks = local.tracks,
            catalogue = lane,
        )

        /**
         * Artists from both lanes in the order the screen shows them: what you own, then what you
         * could pull.
         *
         * Three rules, each of which shipped broken and is the reason this is its own function.
         *
         * 1. **The mirror's artists lead, whatever MusicBrainz thinks of them.** REQUIREMENTS.md
         *    "Browse and search" orders artist detail "Owned albums first, then un-owned", and the same
         *    logic decides this list: the library is the music the user has and can play now, the
         *    catalogue is a shopping list. A search for "wonder" that puts three catalogue strangers
         *    above the artist whose records are on the device has ordered the two lanes backwards.
         * 2. **The artist MBID is the join key**, exactly as the release-group MBID is for albums
         *    (rule 3). A catalogue hit for an artist the mirror already holds is dropped, because the
         *    local row is the one that knows the owned album count and has artwork behind it.
         * 3. **Byte-identical names collapse.** MusicBrainz holds several distinct artists called
         *    "Wonder", and a search for "wonder" returned four rows, two of them the same string
         *    twice, all subtitled "Not in your library yet". The MBID join cannot collapse those -
         *    they are genuinely different MBIDs - so the catalogue half is additionally deduplicated
         *    on the case-folded name. The alternative, telling them apart by MusicBrainz's
         *    disambiguation comment, was rejected because [Artist] carries no such field and adding
         *    one is a server-shape change well outside search; until it exists, two rows reading
         *    exactly the same are indistinguishable to the user, and showing four of them is strictly
         *    worse than showing one.
         *
         * Within each half the order is [artistRelevance] descending, and the sort is stable, so
         * anything the two scores cannot separate keeps the order it arrived in - alphabetical for the
         * mirror, MusicBrainz's own relevance for the catalogue.
         */
        public fun mergeArtists(
            query: String,
            local: List<Artist>,
            catalogue: List<Artist>,
        ): List<Artist> {
            val owned: LinkedHashMap<ArtistMbid, Artist> = LinkedHashMap(local.size)
            for (artist in local) {
                // Only the MBID deduplicates the mirror's own half. Two owned artists that share a
                // name are two artists the user has music by, and dropping either would hide music
                // that is on the device.
                if (!owned.containsKey(artist.mbid)) owned[artist.mbid] = artist
            }
            val ranked: List<Artist> = owned.values.sortedByDescending { artist ->
                artistRelevance(query, artist.name)
            }

            val seenNames: MutableSet<String> = ranked.mapTo(HashSet(ranked.size)) { artist ->
                nameKey(artist.name)
            }
            val fromCatalogue: MutableList<Artist> = ArrayList(catalogue.size)
            for (candidate in catalogue) {
                if (owned.containsKey(candidate.mbid)) continue
                if (!seenNames.add(nameKey(candidate.name))) continue
                fromCatalogue.add(candidate)
            }

            return ranked + fromCatalogue.sortedByDescending { artist ->
                artistRelevance(query, artist.name)
            }
        }

        /**
         * Albums from both lanes, merged on release-group MBID per REQUIREMENTS.md rule 3, owned
         * first.
         *
         * The MBID is the join key and it is tried first, exactly as rule 3 says. What follows is the
         * net under it, and the reason there has to be one.
         *
         * ## The MBID is not always the same MBID
         *
         * Observed on a device at v0.0.12. A library holding four Dido albums, searched for `dido`,
         * drew all four under "Albums - in your library" and then **all four again** under "Albums to
         * pull - from MusicBrainz", each with a Pull button offering to acquire a record the server
         * already had. The merge had not failed: the two keys were genuinely different UUIDs, so
         * nothing a client can normalise - case, the `al-` prefix, whitespace - would have brought
         * them together. The corroborating evidence is on the same screen: every one of those four
         * catalogue rows carried `in_library: false`, which is the *server's* own answer about its own
         * library, computed from the id it was returning. A server that holds an album and says it
         * does not hold the id it just sent is a server sending a different id - a release MBID where
         * the mirror holds a release group, or a release group MusicBrainz search picked over the one
         * the import matched. Either way the key cannot be repaired here.
         *
         * ## So a record the server already has is also matched on title and artist
         *
         * A catalogue row is dropped when a row the server **already holds** - owned, pinned,
         * acquiring or awaiting approval - has the same case-folded title and the same case-folded
         * artist. Title alone would be wrong and is not used: two release groups with the same title
         * are the normal case in MusicBrainz, a live record beside a studio one, a reissue, a
         * soundtrack named after its film, and collapsing those would hide albums that really are
         * different records. Adding the artist is what makes the pair specific enough to be one
         * record, and it is still only applied against music the server has: two *catalogue* rows
         * that share a title and an artist are both kept, because neither is a record the user owns
         * and either might be the one they want.
         *
         * ## The second copy is not always the catalogue's, and that is how this shipped broken
         *
         * The rule above was applied to the catalogue half alone, and the device went on drawing all
         * four Dido albums twice at v0.0.13 with the fix in the build. The second copy was coming from
         * the **mirror**, so nothing the catalogue half was tested against could have caught it: with
         * the network off entirely, the local lane on its own still returned the library block and the
         * to-pull block together.
         *
         * The mirror holds two rows for one record because it is asked to. `LibraryRepository`'s
         * `refreshArtistDiscographyPage` writes every release group of an artist's MusicBrainz
         * discography that the mirror does not already hold as an un-owned row, so artist detail still
         * renders offline - and "does not already hold" is decided on the MBID, which is the one thing
         * the two sides disagree about. Opening the artist screen for an artist you own therefore
         * writes a second, un-owned row for every record of theirs you own. `album_fts` indexes the
         * artist name on those rows like any other, and `AlbumDao.observeAlbumSearch` filters on
         * nothing, so the local lane returns both.
         *
         * So the key set is built from the whole local half **before** any row is accepted, and an
         * un-owned local row a held row already covers is dropped exactly as a catalogue row would be.
         * REQUIREMENTS.md "Search behaviour" rule 3 is a statement about one record appearing once in
         * the merged list; which lane the surplus copy arrived on was never part of it.
         *
         * Rejected: filtering those rows out of `SearchRepository.searchLocal`, or refusing to cache
         * them in the first place. The first puts a second copy of this rule in the data layer, where
         * it would answer for the Auto browse tree and the playlist picker as well; the second is the
         * offline discography, which is the whole reason the rows exist. Neither can see the merged
         * list, which is where the guarantee is made.
         *
         * **The year is deliberately not part of the key.** It was the obvious third term and the
         * device ruled it out: the mirror's row for *Safe Trip Home* carried no year at all, so its
         * row read as a bare title while the catalogue copy beside it read "Dido - 2008". A key that
         * required them to agree would have failed on exactly the row that proves the bug.
         *
         * The one weak rule, stated rather than hidden: an owned row with a **blank artist name** is
         * matched on its title alone, because there is nothing else on it to compare. *Safe Trip
         * Home* was that row too. The risk is real - an unrelated record with the same title would be
         * dropped from the catalogue block - and it is the better of two bad outcomes, since the
         * alternative is an invitation to re-acquire music the server already holds, which costs the
         * user a download and the server a search. A row with no artist name is also already
         * unrenderable: `SearchFormat.albumRowSubtitle` drops blanks, which is why that row drew no
         * subtitle at all.
         *
         * ## Rejected
         *
         * Filtering the catalogue block by title against the owned block in the UI. It would have
         * made the screen look right while leaving two records for one album in
         * [UnifiedSearchResults.albums], where paging, the pull sheet and every future reader of the
         * merged list would still see both. Rule 3 is a statement about the merged list, not about
         * one screen's filter.
         */
        public fun mergeAlbums(local: List<Album>, catalogue: List<Album>): List<Album> {
            val seen: MutableSet<ReleaseGroupMbid> = HashSet(local.size + catalogue.size)
            val merged: MutableList<Album> = ArrayList(local.size + catalogue.size)
            // Built from the whole local half up front, because the rule has to apply *within* that
            // half: a set grown from the rows already accepted could not answer for the row being
            // considered. Taking it from `local` rather than from the accepted rows loses nothing -
            // the loop below only ever drops a row that is un-owned, which contributes no key, or a
            // repeated MBID, which contributes the same one.
            val held: Set<String> = heldRecordKeys(local)
            for (album in local) {
                if (album.state == AlbumState.NotOwned && isHeldAlready(album, held)) continue
                if (seen.add(album.releaseGroupMbid)) merged.add(album)
            }
            for (album in catalogue) {
                if (isHeldAlready(album, held)) continue
                // Also catches a release group the catalogue half returned twice, which one upstream
                // response genuinely can.
                if (seen.add(album.releaseGroupMbid)) merged.add(album)
            }
            return merged
        }

        /**
         * The title-and-artist keys of every album in [albums] the server already holds.
         *
         * Only the states that mean the server has it or is getting it - which is the same set
         * `SearchUiState.libraryAlbums` draws, and the same question: offering to pull an album that
         * is mid-acquisition or waiting for an administrator is as wrong as offering to pull one that
         * has already landed.
         *
         * An album with a blank title contributes nothing. A key built from an empty string would
         * match every other untitled record the catalogue returned, and `SearchFormat.albumTitle`
         * exists precisely because a blank title is a thing this server sends.
         */
        private fun heldRecordKeys(albums: List<Album>): Set<String> {
            val keys: MutableSet<String> = HashSet(albums.size)
            for (album in albums) {
                if (album.state == AlbumState.NotOwned) continue
                val title: String = nameKey(album.title)
                if (title.isEmpty()) continue
                val artist: String = nameKey(album.artistName)
                keys.add(if (artist.isEmpty()) title else title + RecordKeySeparator + artist)
            }
            return keys
        }

        /**
         * True when [candidate] is a record [heldKeys] says the server already has.
         *
         * The title-only lookup comes first and answers the blank-artist row [mergeAlbums]
         * documents; the title-and-artist lookup is the ordinary case. Both are plain set hits, so
         * this stays linear in the catalogue half however long the owned half is.
         */
        private fun isHeldAlready(candidate: Album, heldKeys: Set<String>): Boolean {
            if (heldKeys.isEmpty()) return false
            val title: String = nameKey(candidate.title)
            if (title.isEmpty()) return false
            if (heldKeys.contains(title)) return true
            val artist: String = nameKey(candidate.artistName)
            return artist.isNotEmpty() && heldKeys.contains(title + RecordKeySeparator + artist)
        }

        /**
         * What separates a title from an artist inside one key.
         *
         * A NUL, because it cannot occur in either half: any printable separator is a string a real
         * title or artist could contain, and "A - B" by "C" would then key the same as "A" by "B - C".
         */
        private const val RecordKeySeparator: String = "\u0000"

        /**
         * Folds one more page of a single catalogue bucket into an already merged result.
         *
         * REQUIREMENTS.md rule 5: "Paginate a single bucket through
         * `GET /api/v1/search/{artists|albums}` with `limit` and `offset`." A page arrives after the
         * user has already read the list, so this **appends and never reorders**: re-ranking the rows
         * already on screen would shuffle them under the reader's finger, and everything a later page
         * can contain is catalogue content, which sorts below everything already there anyway.
         *
         * A page that reports upstream degradation upgrades [CatalogueLaneState.Ready] so the quiet
         * inline note appears; a page that reports nothing leaves a previously reported degradation
         * alone, because one healthy page is not evidence that MusicBrainz has recovered.
         */
        public fun expand(base: UnifiedSearchResults, page: CatalogueSearchPage): UnifiedSearchResults {
            val lane: CatalogueLaneState = when {
                page.serviceStatus?.isDegraded != true -> base.catalogue
                base.catalogue is CatalogueLaneState.Ready ->
                    CatalogueLaneState.Ready(page.serviceStatus)
                else -> base.catalogue
            }
            return when (page.bucket) {
                SearchBucket.ARTISTS -> {
                    val known: Set<ArtistMbid> = base.artists.mapTo(HashSet()) { it.mbid }
                    val names: MutableSet<String> = base.artists.mapTo(HashSet()) { nameKey(it.name) }
                    val added: List<Artist> = page.artists
                        .filter { candidate ->
                            !known.contains(candidate.mbid) && names.add(nameKey(candidate.name))
                        }
                        .sortedByDescending { artist -> artistRelevance(base.query, artist.name) }
                    base.copy(artists = base.artists + added, catalogue = lane)
                }
                SearchBucket.ALBUMS -> {
                    val known: MutableSet<ReleaseGroupMbid> =
                        base.albums.mapTo(HashSet()) { it.releaseGroupMbid }
                    // The same two rules [mergeAlbums] applies, and for the same reason: a later
                    // page is the same catalogue answering the same query, so it carries the same
                    // mismatched ids for records the server already holds.
                    val held: Set<String> = heldRecordKeys(base.albums)
                    val added: List<Album> = page.albums.filter { candidate ->
                        !isHeldAlready(candidate, held) && known.add(candidate.releaseGroupMbid)
                    }
                    base.copy(albums = base.albums + added, catalogue = lane)
                }
            }
        }

        /**
         * How well an artist's name answers what the user typed. Higher is better, [NoRelevance] is
         * "the server thought so and this app cannot see why".
         *
         * The catalogue lane ranks by MusicBrainz's own scoring, which weighs aliases, recording
         * credits and popularity, and that is how a search for "wonder" put "Jr. Wonder" at the top of
         * the list. What a person typing into a search field means is much narrower, so the name they
         * typed is scored against the name on the row and nothing else:
         *
         * | Score | Match |
         * | --- | --- |
         * | [ExactName] | the whole name, case-folded |
         * | [NamePrefix] | the name starts with what was typed |
         * | [WordPrefix] | every word typed begins a word of the name, in any position |
         * | [Substring] | what was typed appears somewhere inside the name |
         * | [NoRelevance] | none of the above; the upstream match is on something not shown |
         *
         * [WordPrefix] is the row that matters: it is what makes "wonder" find "Oh Wonder", which a
         * prefix test on the sort name cannot do and which was the whole of the missing-artist bug.
         */
        public fun artistRelevance(query: String, name: String): Int {
            val typed: String = nameKey(query)
            val actual: String = nameKey(name)
            if (typed.isEmpty() || actual.isEmpty()) return NoRelevance
            return when {
                actual == typed -> ExactName
                actual.startsWith(typed) -> NamePrefix
                wordsMatch(actual, typed) -> WordPrefix
                actual.contains(typed) -> Substring
                else -> NoRelevance
            }
        }

        /** The name matched what was typed well enough to be shown as an artist in its own right. */
        public fun artistMatches(query: String, name: String): Boolean =
            artistRelevance(query, name) > NoRelevance

        /** The whole name, case-folded: "Oh Wonder" for "oh  wonder". */
        public const val ExactName: Int = 100

        /** The name begins with what was typed: "Wonderland" for "wonder". */
        public const val NamePrefix: Int = 80

        /** Every word typed begins a word of the name: "Oh Wonder" for "wonder". */
        public const val WordPrefix: Int = 60

        /** What was typed is in there somewhere: "Stevie Wonderful" for "onder". */
        public const val Substring: Int = 40

        /** Nothing this app can see matched. Kept, and shown last, because upstream matched something. */
        public const val NoRelevance: Int = 0

        /**
         * True when every word of [typed] begins a word of [actual].
         *
         * Word-start rather than whole-word, so the score still rises while the user is halfway
         * through typing - the same reason the FTS expression behind the local lane makes its last
         * term a prefix.
         */
        private fun wordsMatch(actual: String, typed: String): Boolean {
            val words: List<String> = actual.split(' ')
            return typed.split(' ').all { part ->
                part.isNotEmpty() && words.any { word -> word.startsWith(part) }
            }
        }

        /**
         * The key two names are compared on: [NameKeys.fold], which is also what the mirror's sort
         * columns and genre identity are built from, so a name that matches here is the same name
         * that sorted there.
         */
        private fun nameKey(value: String): String = NameKeys.fold(value)
    }
}
