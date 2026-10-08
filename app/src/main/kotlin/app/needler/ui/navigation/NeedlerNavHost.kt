package app.needler.ui.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.needler.connect.ConnectRoute
import app.needler.licences.LicencesRoute
import app.needler.feature.library.playlists.PlaylistsRoute
import app.needler.feature.library.playlists.PlaylistRoute
import app.needler.feature.library.genres.GenresRoute
import app.needler.feature.library.genres.GenreRoute
import app.needler.diagnostics.DiagnosticsRoute
import android.net.Uri
import app.needler.core.data.background.NotificationDestination
import app.needler.core.design.theme.NeedlerTheme
import app.needler.feature.library.album.AlbumRoute
import app.needler.feature.library.artist.ArtistRoute
import app.needler.feature.library.library.LibraryRoute
import app.needler.feature.player.crate.CrateRoute
import app.needler.feature.player.nowplaying.MiniPlayerRoute
import app.needler.feature.player.nowplaying.NowPlayingRoute
import app.needler.feature.player.output.OutputSheet
import app.needler.feature.player.output.OutputUiState
import app.needler.feature.player.output.OutputViewModel
import app.needler.feature.player.settings.CrossfadeRoute
import app.needler.feature.player.settings.EqualiserRoute
import app.needler.feature.pulls.pulls.PullsRoute
import app.needler.feature.search.search.SearchRoute
import app.needler.feature.player.sidebar.PlayerSidebarRoute
import app.needler.settings.SettingsRoute
import app.needler.update.UpdateBannerRoute

/** Onboarding. Screens 01 and 16. Owned by `:app`. */
private const val ROUTE_CONNECT = "connect"

/** Everything after onboarding: the four destinations inside the navigation chrome. */
private const val ROUTE_HOME = "home"

/**
 * The two player destinations, which sit *outside* the navigation chrome.
 *
 * The pack settles this one. Screen 07 draws Now Playing with a chevron-down
 * close, the crate button and the output chip, and no bottom bar and no mini
 * player; screen 08 draws the crate with a single Back that returns to Now
 * Playing, and no bottom bar either. Both are a layer over Home rather than a
 * destination within it - and a full player that still drew a mini player of the
 * same track underneath itself would be the clearest possible sign it had been
 * put in the wrong graph.
 *
 * Being out here is also what makes [ROUTE_CRATE] reachable at all. On a phone
 * Now Playing is the only way into the crate, so the two have to sit on one
 * controller for that to be a single navigate.
 */
private const val ROUTE_NOW_PLAYING = "nowplaying"
private const val ROUTE_CRATE = "crate"

/**
 * The outer destinations that are a layer over the app rather than a place in
 * it, and the one thing [NeedlerNavTransitions] needs to know about this graph.
 *
 * Both of these animate as a surface crossing the bottom edge, so the bar and
 * the mini-player that Home draws are revealed and covered rather than faded.
 * Connect is deliberately **not** in here: it is not a layer over Home, it
 * replaces it - both navigations between the two carry
 * `popUpTo { inclusive = true }` - so it cross-fades like any other change of
 * place.
 *
 * Built from the route constants the graph below registers, so there is one
 * spelling of each and not two. `internal` so `NeedlerNavTransitionsTest` can
 * hold the membership: a third chrome-free destination has to be added here as
 * well as declared, and a destination that keeps the bottom bar must not be.
 */
internal val PLAYER_LAYER_ROUTES: Set<String> = setOf(ROUTE_NOW_PLAYING, ROUTE_CRATE)

/**
 * Detail destinations, which live inside the scaffold alongside the four tabs.
 *
 * The argument names match what :feature:library reads from its SavedStateHandle
 * (AlbumViewModel.ALBUM_ID_ARG and ArtistViewModel.ARTIST_ID_ARG). Values are bare
 * MBIDs; the `al-` and `ar-` Subsonic prefixes never appear in a route.
 */
private const val ARG_ALBUM_ID = "albumId"
private const val ARG_ARTIST_ID = "artistId"
private const val ROUTE_ALBUM = "album/{$ARG_ALBUM_ID}"

/**
 * The artist name and subtitle the caller already knew, carried as optional
 * query arguments.
 *
 * `ArtistViewModel` has read both from its `SavedStateHandle` since the screen
 * was written - `ARTIST_NAME_ARG` and `ARTIST_SUBTITLE_ARG` - and `ArtistRoute`'s
 * KDoc says this file is supposed to supply them. It never did, and the cost was
 * a real screen: an artist reached from the artist screen's own "Search the
 * catalogue" rows has no row in the mirror, so the header read "Unknown artist"
 * and the frames before the discography landed read "That artist is not here".
 * The names are spelt here to match the view model's constants; `ArtistRouteTest`
 * asserts the two spellings against those constants, so they cannot drift apart
 * silently.
 *
 * Query arguments and not path segments. A path segment would have to be present
 * in every artist URL, so `artist/{artistId}` would stop matching and every
 * caller that has only an MBID - the library, search, an album's artist link, a
 * notification tap through [NotificationDestination.Artist] - would navigate to a
 * destination the graph no longer holds. As query arguments with
 * `nullable = true` and a declared default they are genuinely optional, which is
 * what [artistArguments] exists to keep in one place.
 */
internal const val ARG_ARTIST_NAME = "artistName"
internal const val ARG_ARTIST_SUBTITLE = "artistSubtitle"
internal const val ROUTE_ARTIST =
    "artist/{$ARG_ARTIST_ID}?$ARG_ARTIST_NAME={$ARG_ARTIST_NAME}" +
        "&$ARG_ARTIST_SUBTITLE={$ARG_ARTIST_SUBTITLE}"

/**
 * Screens 19 and 20, the two sub-screens Settings links to.
 *
 * Inside the scaffold, unlike Now Playing and the crate: the pack draws the bottom bar on both
 * of them, exactly as it does on album and artist detail, so they are a change of content and
 * not a layer over the app.
 */
private const val ROUTE_EQUALISER = "equaliser"
private const val ROUTE_CROSSFADE = "crossfade"

/**
 * Playlists, genres, licences and the diagnostics log.
 *
 * All four sit inside the scaffold rather than above it, for the reason album
 * and artist detail do: the pack keeps the bottom bar and the nav rail visible,
 * so opening one is a change of content and not a change of context.
 *
 * The playlist id is carried **bare**, never `pl-` prefixed - the prefix is a
 * Subsonic wire detail owned by :core:data - and a provisional `local-...` id is
 * a legal value here. REQUIREMENTS.md "Local persistence" mints one for a
 * playlist created offline and re-keys the row when the write queue replays
 * `createPlaylist`, so a route that filtered those out would make a user's own
 * offline playlist unopenable until the server had seen it.
 */
private const val ARG_PLAYLIST_ID = "playlistId"
private const val ARG_GENRE = "genre"
private const val ROUTE_PLAYLISTS = "playlists"
private const val ROUTE_PLAYLIST = "playlist/{$ARG_PLAYLIST_ID}"
private const val ROUTE_GENRES = "genres"
private const val ROUTE_GENRE = "genre/{$ARG_GENRE}"
private const val ROUTE_LICENCES = "licences"
private const val ROUTE_DIAGNOSTICS = "diagnostics"

/**
 * Back to Connect, with Home taken off the stack behind it.
 *
 * Settings offers two ways here and both mean the same thing: whatever the tabs are showing is
 * about to stop being true. Leaving Home on the stack would let the back gesture return to a
 * library read from a server the app is no longer signed in to.
 */
private fun returnToConnect(navController: NavHostController) {
    navController.navigate(ROUTE_CONNECT) {
        popUpTo(ROUTE_HOME) { inclusive = true }
        launchSingleTop = true
    }
}

private fun albumRoute(mbid: String): String = "album/$mbid"

/**
 * An artist by MBID alone, which is all most callers have.
 *
 * Unchanged, and deliberately so: the library, search, an album's artist link,
 * the player sidebar and a notification tap all reach an artist the mirror has a
 * row for, and that row is where the name comes from. Carries no query string at
 * all, so the URL this produces is the same string it has always produced.
 */
internal fun artistRoute(mbid: String): String = "artist/$mbid"

/**
 * An artist the mirror has never heard of, with the name - and only the name the
 * catalogue actually supplied - carried along.
 *
 * ### What this is for
 *
 * The artist screen's "Search the catalogue" rows. REQUIREMENTS.md "Placing a
 * request" gives an artist whose id the server derived from a name no discography
 * to look up, so the screen offers MusicBrainz's artists of that name instead;
 * tapping one opens this same destination for an MBID that has no `artist` row
 * behind it. `refreshArtistDiscography` writes album rows and never an artist
 * row, so no amount of waiting produces a name. The caller already had one, and
 * this is how it gets there.
 *
 * ### Why the name is encoded
 *
 * Artist names carry every character that would otherwise end the route: "AC/DC"
 * ends the path segment, "Simon & Garfunkel" starts a second query argument,
 * "Alice?" starts the query early and "#1 Crush" starts a fragment. [Uri.encode]
 * is the same answer [genreRoute] gives for the same problem, and the navigation
 * library decodes the value on the way back out - see `GenreViewModel`'s own
 * KDoc - so nothing is done to it in the view model. A name that broke the route
 * would be worse than "Unknown artist": the destination would not match at all.
 *
 * ### Why a blank name produces no argument
 *
 * `?artistName=` reads back as an empty string, and an empty header is the defect
 * this fixes wearing different clothes. With nothing to say, this falls back to
 * [artistRoute] and the screen shows "Unknown artist" exactly as before.
 *
 * @param subtitle MusicBrainz's disambiguation comment, or null. Null when the
 *   catalogue sent none - no count and no wording is invented here, because
 *   `ArtistUiState.subtitle` already has an honest fallback for the case and a
 *   subtitle minted in the navigation layer would be a claim no source made.
 */
internal fun catalogueArtistRoute(mbid: String, name: String, subtitle: String?): String {
    if (name.isBlank()) return artistRoute(mbid)
    val withName: String = artistRoute(mbid) + "?" + ARG_ARTIST_NAME + "=" + Uri.encode(name)
    return if (subtitle.isNullOrBlank()) {
        withName
    } else {
        withName + "&" + ARG_ARTIST_SUBTITLE + "=" + Uri.encode(subtitle)
    }
}

/**
 * The artist destination's arguments, in one place because the optionality is the
 * whole point of them.
 *
 * `nullable = true` **and** `defaultValue = null` on both optional arguments.
 * Either alone is not enough to make the navigation library treat a query
 * argument as absent-but-fine, and an argument it considers required is an
 * argument whose absence stops `artist/{artistId}` matching - which would break
 * every caller that has only an MBID, the notification tap included. Extracted so
 * `ArtistRouteTest` asserts the arguments the graph really registers rather than
 * a copy of them.
 */
internal fun artistArguments(): List<NamedNavArgument> = listOf(
    navArgument(ARG_ARTIST_ID) { type = NavType.StringType },
    navArgument(ARG_ARTIST_NAME) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
    navArgument(ARG_ARTIST_SUBTITLE) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

private fun playlistRoute(id: String): String = "playlist/$id"

/**
 * A genre's route, with the genre name percent-encoded.
 *
 * The argument is the genre **name**, not a slug or an id, because that is what
 * `getGenres` returns and what `getSongsByGenre` takes back. Real genre names
 * carry the two characters that would otherwise break the match outright: a
 * slash ("Rock/Pop") ends the path segment and the route no longer resolves, and
 * an ampersand ("Drum & bass") is fine in a path but not once anything treats it
 * as a query. Encoding here and decoding in the view model keeps both working
 * without inventing a slug the server would not recognise on the way back.
 */
private fun genreRoute(genre: String): String = "genre/" + Uri.encode(genre)

/**
 * Which tab a destination in the inner graph belongs to, read off the back
 * stack rather than off the route.
 *
 * REQUIREMENTS.md "Tablet layout" keeps the bottom bar and the nav rail visible
 * on album and artist detail, so one of the four items always has to be lit -
 * and the honest answer to which one is *the tab the listener pushed it from*,
 * which only the back stack knows.
 *
 * ### The bug this replaces
 *
 * A `when` over the current route used to answer it, mapping album, artist,
 * playlists, playlist, genres and genre to Library unconditionally. Reach an
 * artist by searching for it and the bar lit Library while the back stack read
 * `library -> search -> artist/dido`. That much is plain from the old code. What
 * it then cost was reported from the device: tapping Search did nothing visible
 * at all.
 *
 * The mechanism, read from `NavController` rather than stepped through, is that
 * a wrongly-lit bar sends the tap down the wrong branch of `selectTab`. With
 * `Search != Library` it took the `popUpTo(start) { saveState = true } ...
 * restoreState = true` branch, which pops `artist/dido` and `search`, files them
 * as Search's saved stack, and then restores that same stack because Search is
 * what it is navigating to. The listener was returned to the screen they were
 * already on, and would have been every time they tried. "Nothing happens" is
 * also the evidence for it: had the restore not fired, the tap would have pushed
 * a bare `search` and they would have seen the search field.
 *
 * That was the second sighting of one bug. The first was Settings doing nothing
 * when tapped from the Crossfade screen, which `selectTab`'s own first branch
 * was added to fix. A route cannot answer "where did this come from" - a
 * playlist, a genre, an album and an artist are all reachable from more than one
 * tab - so no `when` over routes was ever going to be right.
 *
 * ### Why the top of the stack and not the bottom
 *
 * The four tab routes are the only ones [NeedlerDestination.fromRoute] matches,
 * and tab switching pops back to the graph's start destination, so at most one
 * tab route sits on the stack at a time - except transiently, which is why this
 * walks **down from the top** and takes the first it finds. On
 * `library -> search -> artist/dido -> album/x` that is Search, which is where
 * the listener actually is.
 *
 * @param backStackRoutes the inner graph's back stack, bottom entry first, with
 *   the graph's own entry already removed. A list of plain route strings and not
 *   a `NavController`, so this is a pure function a test can drive directly;
 *   deriving it inside the composable instead left the only interesting logic in
 *   the file reachable only through an instrumented navigation host.
 * @return the tab to light. [NeedlerDestination.Start] when the stack holds no
 *   tab route at all, which is the state between a controller being created and
 *   its start destination being added.
 */
internal fun owningTab(backStackRoutes: List<String?>): NeedlerDestination =
    backStackRoutes.asReversed().firstNotNullOfOrNull(NeedlerDestination::fromRoute)
        ?: NeedlerDestination.Start

/**
 * The app's navigation graph.
 *
 * Two levels, and the split is what keeps the chrome still while the content
 * changes.
 *
 * The **outer** graph holds Connect, Home and the two player destinations.
 * Connect has no bottom bar and no nav rail - the pack draws none on screens 01
 * and 16 - so it cannot live inside the scaffold, and onboarding is popped off
 * the back stack the moment it succeeds. Now Playing and the crate are out here
 * for the same reason: screens 07 and 08 draw neither bar nor rail, and both are
 * a layer over Home rather than a destination within it.
 *
 * The **inner** graph, inside [NeedlerHome], holds the four destinations and the
 * album and artist detail that keep the chrome. It sits inside
 * [NeedlerNavigationScaffold]'s content slot, so switching tab recomposes the
 * content and leaves the bar, the rail and the player sidebar alone.
 *
 * @param startConnected start at Home rather than Connect. **Nothing passes it,
 *   and that is now deliberate.** It was written for a host that would resolve
 *   the saved session before this composes, which was never wired up - and for
 *   eleven versions a cold start therefore dropped a signed-in user on the
 *   sign-in form, because `startDestination` is read once and the default is
 *   false. The session is resolved one layer down instead:
 *   `ConnectViewModel.arriveWith` reports `connected` the moment it sees a usable
 *   session and this graph pops Connect off the back stack exactly as it does
 *   after a real sign-in, underneath the splash that `NeedlerApp` draws over the
 *   navigation. That KDoc carries the reasoning and the alternative rejected.
 *   The parameter stays so a preview or a test can start at Home without a
 *   session; it must not become the production answer again without resolving
 *   the session first, which is the part that cost the eleven versions.
 */
// ModalBottomSheet carries @ExperimentalMaterial3Api in some Material3
// releases and not others. Opting in costs a warning when it is stable and
// is required when it is not, so it is the cheaper of the two mistakes.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeedlerNavHost(
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startConnected: Boolean = false,
    pullsBadgeCount: Int = 0,
    notificationDestination: NotificationDestination? = null,
    onNotificationDestinationHandled: () -> Unit = {},
) {
    // Where "open the artist" from Now Playing lands.
    //
    // ROUTE_NOW_PLAYING is a sibling of ROUTE_HOME in *this* graph and
    // ROUTE_ARTIST lives in the inner one, so this controller cannot reach it.
    // Duplicating the route out here would draw an artist screen with no bottom
    // bar under it, so the player records a target and pops instead; NeedlerHome
    // already accepts a destination and routes it for notifications, and this
    // reuses that rather than adding a second mechanism for the same job.
    var pendingDestination: NotificationDestination? by remember { mutableStateOf(null) }

    // Read once, here, and captured by the six lambdas below. The transition
    // lambdas `NavHost` takes are not composable, so none of them can read
    // LocalReducedMotion for itself.
    val reducedMotion: Boolean = NeedlerTheme.reducedMotion

    NavHost(
        navController = navController,
        startDestination = if (startConnected) ROUTE_HOME else ROUTE_CONNECT,
        modifier = modifier,
        // The player layer rises and falls; Connect and Home cross-fade. See
        // NeedlerNavTransitions for what the library's own defaults were doing
        // to the bottom bar and the mini-player before any of this was named.
        enterTransition = {
            NeedlerNavTransitions.enter(
                toPlayerLayer = targetState.destination.route in PLAYER_LAYER_ROUTES,
                reducedMotion = reducedMotion,
            )
        },
        exitTransition = {
            NeedlerNavTransitions.exit(
                toPlayerLayer = targetState.destination.route in PLAYER_LAYER_ROUTES,
                reducedMotion = reducedMotion,
            )
        },
        popEnterTransition = {
            NeedlerNavTransitions.popEnter(
                fromPlayerLayer = initialState.destination.route in PLAYER_LAYER_ROUTES,
                reducedMotion = reducedMotion,
            )
        },
        popExitTransition = {
            NeedlerNavTransitions.popExit(
                fromPlayerLayer = initialState.destination.route in PLAYER_LAYER_ROUTES,
                reducedMotion = reducedMotion,
            )
        },
        // The same two again, for a pop the system is dragging rather than
        // committing. navigation-compose keeps these separate so a gestural Back
        // can be seeked; giving them the same answer is what makes Now Playing's
        // chevron-down and a Back gesture land identically, which is the whole
        // of that gesture either way. Left at the library's own defaults - a
        // spring fade in and `scaleOut(0.7f)` - the finger would have scaled the
        // player down in place while the bar faded up behind it, and the chevron
        // would have done something else entirely.
        //
        // The swipe edge is ignored on purpose: this surface moves on the
        // vertical axis, so which side the finger came from says nothing about
        // where it should go.
        predictivePopEnterTransition = { _ ->
            NeedlerNavTransitions.popEnter(
                fromPlayerLayer = initialState.destination.route in PLAYER_LAYER_ROUTES,
                reducedMotion = reducedMotion,
            )
        },
        predictivePopExitTransition = { _ ->
            NeedlerNavTransitions.popExit(
                fromPlayerLayer = initialState.destination.route in PLAYER_LAYER_ROUTES,
                reducedMotion = reducedMotion,
            )
        },
    ) {
        composable(ROUTE_CONNECT) {
            ConnectRoute(
                widthSizeClass = widthSizeClass,
                onConnected = {
                    navController.navigate(ROUTE_HOME) {
                        popUpTo(ROUTE_CONNECT) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(ROUTE_HOME) {
            NeedlerHome(
                widthSizeClass = widthSizeClass,
                pullsBadgeCount = pullsBadgeCount,
                // Single-top, and deliberately no popUpTo. Single-top because
                // the mini player is a 64dp card carrying a 44dp play button and
                // a thumb that lands twice is ordinary; two taps that both got
                // through would stack two identical players, and the first Back
                // would look like it had done nothing. No popUpTo because the
                // player is a layer over Home, not a replacement for it -
                // closing it has to return the listener to the tab, and the
                // scroll position, they left.
                onOpenNowPlaying = {
                    navController.navigate(ROUTE_NOW_PLAYING) { launchSingleTop = true }
                },
                notificationDestination = pendingDestination ?: notificationDestination,
                onNotificationDestinationHandled = {
                    if (pendingDestination != null) pendingDestination = null
                    else onNotificationDestinationHandled()
                },
            )
        }

        // Now Playing, screen 07, and the crate, screen 08. Siblings of Home
        // rather than destinations inside it - see ROUTE_NOW_PLAYING for why the
        // pack puts them out here.
        composable(ROUTE_NOW_PLAYING) {
            // Screen 21 is a sheet, not a destination, and this is the host that
            // answers that question. OutputPickerRoute draws its own dimmed
            // backdrop and carries no dismiss callback, which is why it stayed
            // unreachable: made a destination it would strand the listener on a
            // screen with no way off. Its KDoc names the alternative - OutputSheet
            // is "the content alone for a host that brings its own sheet" - so the
            // sheet, and therefore the dismissal, belongs here. ModalBottomSheet
            // supplies the scrim, the drag handle and the back gesture, none of
            // which :feature:player then has to invent.
            var outputPickerOpen: Boolean by rememberSaveable { mutableStateOf(false) }

            NowPlayingRoute(
                // The chevron down in the header. A collapse rather than a back,
                // but the same call AlbumRoute and ArtistRoute make: whatever
                // Home was showing is still underneath, so popping to it is the
                // whole of the gesture.
                onClose = { navController.popBackStack() },
                onOpenCrate = {
                    navController.navigate(ROUTE_CRATE) { launchSingleTop = true }
                },
                onChooseOutput = { outputPickerOpen = true },
                // Collapse first, then let Home route. Entering the artist screen
                // from inside the scaffold is what keeps the bottom bar under it.
                onOpenArtist = { mbid ->
                    pendingDestination = NotificationDestination.Artist(mbid.value)
                    navController.popBackStack()
                },
            )

            if (outputPickerOpen) {
                val outputViewModel: OutputViewModel = hiltViewModel()
                val outputState: OutputUiState by outputViewModel.state
                    .collectAsStateWithLifecycle()

                ModalBottomSheet(
                    onDismissRequest = { outputPickerOpen = false },
                    containerColor = NeedlerTheme.colors.surface,
                ) {
                    OutputSheet(
                        state = outputState,
                        // Choosing an output closes the sheet, which is what the
                        // pack draws: the tick lands and the sheet goes. The
                        // selection itself is the view model's business.
                        onSelect = { target ->
                            outputViewModel.select(target)
                            outputPickerOpen = false
                        },
                        onVolumeChange = outputViewModel::setVolume,
                    )
                }
            }
        }

        // The crate is a destination of its own so the back gesture leaves it
        // without stopping playback.
        composable(ROUTE_CRATE) {
            CrateRoute(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * The four destinations inside the navigation chrome.
 *
 * Tab switching uses the standard save-and-restore pattern: pop back to the
 * graph's start destination saving each tab's state, single-top so a repeated
 * tap does not stack duplicates, and restore the state of the tab being
 * returned to. That is what makes a scrolled Library still be scrolled when the
 * user comes back from Search.
 *
 * @param onOpenNowPlaying opens the full player from the mini player. A
 *   parameter rather than a navigate on this composable's own controller because
 *   Now Playing is in the *outer* graph - it carries no bottom bar - and the
 *   controller in here cannot reach it. It has no default on purpose: a default
 *   of `{}` is precisely the inert tap target this parameter exists to remove.
 */
@Composable
private fun NeedlerHome(
    widthSizeClass: WindowWidthSizeClass,
    pullsBadgeCount: Int,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    notificationDestination: NotificationDestination? = null,
    onNotificationDestinationHandled: () -> Unit = {},
) {
    // The whole back stack, not just its top, because which tab a sub-screen
    // belongs to is a fact about how the listener got there and nothing else.
    // See [owningTab] for the bug that reading the route alone produced.
    //
    // collectAsState and not collectAsStateWithLifecycle: this is already in
    // memory and costs nothing to watch, and a nav bar that lit the wrong tab
    // for a frame after every resume would be a new version of the same
    // complaint.
    val backStack: List<NavBackStackEntry> by navController.currentBackStack.collectAsState()
    // The graph's own entry sits at the bottom of the back queue and is not a
    // destination anyone navigated to; `currentBackStackEntry` filters it out
    // the same way, and both reads below need the same list.
    val routes: List<String?> = backStack
        .filter { it.destination !is NavGraph }
        .map { it.destination.route }
    val currentRoute: String? = routes.lastOrNull()
    val selected: NeedlerDestination = owningTab(routes)

    // Read here for the same reason the outer host reads it: the inner host's
    // transition lambdas are not composable either.
    val reducedMotion: Boolean = NeedlerTheme.reducedMotion

    // A notification tap lands here rather than on the outer graph, because
    // every destination it can name - an album, an artist, the Pulls tab - is
    // inside the navigation chrome. The pack keeps the bottom bar and the nav
    // rail visible on album and artist detail, so opening one from a
    // notification is a change of content, not of context.
    //
    // It is consumed once: onNotificationDestinationHandled clears the state,
    // so a rotation does not re-navigate the user away from wherever they went
    // after the tap.
    LaunchedEffect(notificationDestination) {
        val target: String = when (val destination = notificationDestination) {
            null -> return@LaunchedEffect
            is NotificationDestination.Album -> albumRoute(destination.releaseGroupMbid)
            is NotificationDestination.Artist -> artistRoute(destination.artistMbid)
            NotificationDestination.Pulls -> NeedlerDestination.Pulls.route
            NotificationDestination.Library -> NeedlerDestination.Library.route
        }
        navController.navigate(target) { launchSingleTop = true }
        onNotificationDestinationHandled()
    }

    // Switching to a tab, wherever the switch comes from.
    //
    // The bar is not the only way in. Screen 02 wraps the library's search
    // field in a link to screen 03, so the field opens Search as well, and both
    // routes have to leave the same back stack behind. A bare navigate() from
    // the field pushed an entry the bar's popUpTo could not account for, and
    // Library then ignored every tap until the listener pressed back.
    val selectTab: (NeedlerDestination) -> Unit = { destination ->
        if (destination == selected && currentRoute != destination.route) {
            // Already within this tab, on one of its sub-screens: return to the
            // tab root rather than restoring the sub-screen. Without this,
            // restoreState below would put the sub-screen (Crossfade, Equaliser,
            // an album) straight back, and tapping the tab you are already in
            // would look like it did nothing - which is exactly what Settings did
            // from the Crossfade screen.
            navController.popBackStack(destination.route, inclusive = false)
        } else {
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    NeedlerNavigationScaffold(
        widthSizeClass = widthSizeClass,
        selected = selected,
        onSelect = selectTab,
        modifier = modifier,
        pullsBadgeCount = pullsBadgeCount,
        // The mini player is composed here rather than inside the inner NavHost
        // so it survives a tab switch: it is chrome, like the bar under it, and
        // the pack keeps it on screen on every phone destination. It reads the
        // session itself and composes nothing when the crate is empty, so there
        // is no empty bar to hide and no playback state for this graph to hold.
        //
        // Its view model is scoped to the ROUTE_HOME back stack entry, which is
        // the nearest ViewModelStoreOwner, so the whole of Home shares one
        // PlayerViewModel and switching tab does not rebuild the session
        // connection.
        // The update bar. No callbacks and no navigation: it checks, downloads
        // and hands the APK to the platform installer itself, and draws nothing
        // at all when there is no update, which is almost always.
        updateBanner = { UpdateBannerRoute() },
        miniPlayer = {
            // Tapping the card opens Now Playing, which is what screens 06 and
            // 13 draw it doing. The target is in the outer graph, so it arrives
            // as a parameter rather than being navigated from in here.
            MiniPlayerRoute(onExpand = onOpenNowPlaying)
        },
        // The tablet's permanent player, screen 09, composed here for the same
        // reasons the mini player is: it is chrome, it has to survive a tab
        // switch, and its two view models resolve against the ROUTE_HOME back
        // stack entry, so the whole of Home shares one session connection
        // rather than rebuilding one per destination.
        //
        // The scaffold only calls this slot at expanded width, so a phone in
        // portrait composes neither the sidebar nor its view models; turning it
        // to landscape is what brings them up, and the PlayerViewModel it
        // resolves is the one the mini player was already using, because both
        // ask the same store for it.
        //
        // No expand and no open-crate callback, because at this width there is
        // nothing to expand *to*: the panel holds the artwork, the scrubber,
        // the transport and the crate itself. Now Playing and ROUTE_CRATE are
        // the phone's way of reaching what the tablet simply has on screen.
        sidebar = {
            PlayerSidebarRoute(
                // Inert, for exactly the reason the chip on Now Playing is -
                // see ROUTE_NOW_PLAYING above. OutputPickerRoute takes no
                // dismiss callback and the pack draws screen 21 as a sheet
                // rather than a destination, so inventing a route for it here
                // would strand the listener on a screen with no way off it.
                // Every other control in the panel is live.
                onChooseOutput = {},
                // Trivial here, unlike on Now Playing: the sidebar composes inside
                // NeedlerHome, so this is the inner controller and ROUTE_ARTIST is
                // one of its own destinations.
                onOpenArtist = { navController.navigate(artistRoute(it.value)) },
            )
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = NeedlerDestination.Start.route,
            // A change of content inside chrome that does not move, so all four
            // are the same short cross-fade. The predictive pair is deliberately
            // left at the library's defaults: a gestural Back in here gets the
            // platform's own seeked `scaleOut(0.7f)` preview of the screen
            // behind, which is the one place a finger-dragged Back should look
            // different from a committed one, and the bar stays still under it
            // either way.
            enterTransition = { NeedlerNavTransitions.contentEnter(reducedMotion) },
            exitTransition = { NeedlerNavTransitions.contentExit(reducedMotion) },
            popEnterTransition = { NeedlerNavTransitions.contentEnter(reducedMotion) },
            popExitTransition = { NeedlerNavTransitions.contentExit(reducedMotion) },
        ) {
            composable(NeedlerDestination.Library.route) {
                LibraryRoute(
                    widthSizeClass = widthSizeClass,
                    onOpenAlbum = { navController.navigate(albumRoute(it.value)) },
                    onOpenArtist = { navController.navigate(artistRoute(it.value)) },
                    onOpenSearch = { selectTab(NeedlerDestination.Search) },
                    // Playlists and Genres were registered below as destinations and nothing ever
                    // opened them: four finished, tested, screenshot-baselined screens that no user
                    // could reach, because every test exercised the screens themselves and none
                    // asserted anyone could get to one. A plain navigate, not selectTab - they are
                    // sub-screens of Library in the sense album and artist are, so they keep the bar
                    // lit on Library and Back returns here.
                    onOpenPlaylists = { navController.navigate(ROUTE_PLAYLISTS) },
                    onOpenGenres = { navController.navigate(ROUTE_GENRES) },
                )
            }

            // Album and artist detail sit inside the scaffold rather than above
            // it: the pack keeps the bottom bar and the nav rail visible on
            // screens 04, 05 and 11, so opening an album is a change of content,
            // not a change of context.
            //
            // The arguments are bare MBIDs, never the `al-` / `ar-` prefixed
            // Subsonic ids. The prefixes are a wire detail owned by :core:data.
            composable(
                route = ROUTE_ALBUM,
                arguments = listOf(navArgument(ARG_ALBUM_ID) { type = NavType.StringType }),
            ) {
                AlbumRoute(
                    widthSizeClass = widthSizeClass,
                    onBack = { navController.popBackStack() },
                    onOpenArtist = { navController.navigate(artistRoute(it.value)) },
                )
            }

            composable(route = ROUTE_ARTIST, arguments = artistArguments()) {
                ArtistRoute(
                    widthSizeClass = widthSizeClass,
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { navController.navigate(albumRoute(it.value)) },
                    // A related artist, pushed rather than replaced: the listener
                    // followed a link and Back has to walk it back. The chain
                    // still belongs to whichever tab it started in, which
                    // [owningTab] reads off the stack rather than off the route.
                    onOpenArtist = { navController.navigate(artistRoute(it.value)) },
                    // The namesake rows, and the one caller that knows something
                    // the mirror does not. Same destination, same push, same tab:
                    // only the two optional arguments differ. See
                    // [catalogueArtistRoute] for why the name is encoded and why a
                    // missing subtitle is left missing.
                    onOpenCatalogueArtist = { mbid, name, subtitle ->
                        navController.navigate(catalogueArtistRoute(mbid.value, name, subtitle))
                    },
                )
            }

            composable(NeedlerDestination.Settings.route) {
                SettingsRoute(
                    widthSizeClass = widthSizeClass,
                    onOpenEqualiser = {
                        navController.navigate(ROUTE_EQUALISER) { launchSingleTop = true }
                    },
                    onOpenCrossfade = {
                        navController.navigate(ROUTE_CROSSFADE) { launchSingleTop = true }
                    },
                    // Both rows draw only when a callback is passed, which is
                    // why neither had ever appeared: SettingsScreen has carried
                    // the Licences link since it was written and the host never
                    // supplied a destination for it.
                    onOpenLicences = {
                        navController.navigate(ROUTE_LICENCES) { launchSingleTop = true }
                    },
                    onOpenDiagnostics = {
                        navController.navigate(ROUTE_DIAGNOSTICS) { launchSingleTop = true }
                    },
                    // Both of these end at Connect, and both have to clear Home
                    // behind them: signing out leaves no session for the tabs to
                    // read, and changing server invalidates everything they are
                    // showing. Same shape as onConnected's pop in reverse.
                    onChangeServer = { returnToConnect(navController) },
                    onSignedOut = { returnToConnect(navController) },
                    // The day-25 expiry warning names an action - "Sign in again" - and until this
                    // was wired the only control under it was "Change server", which asks for an
                    // address the user has not got wrong. Same destination, because Needler never
                    // stores the account password and REQUIREMENTS.md's companion bearer has "no
                    // silent renewal", so re-authenticating means Connect either way. Left null the
                    // warning is a statement with no way to act on it.
                    onSignInAgain = { returnToConnect(navController) },
                )
            }

            composable(ROUTE_EQUALISER) {
                EqualiserRoute(onBack = { navController.popBackStack() })
            }

            composable(ROUTE_CROSSFADE) {
                CrossfadeRoute(onBack = { navController.popBackStack() })
            }

            composable(ROUTE_LICENCES) {
                LicencesRoute(
                    widthSizeClass = widthSizeClass,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(ROUTE_DIAGNOSTICS) {
                DiagnosticsRoute(
                    widthSizeClass = widthSizeClass,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(ROUTE_PLAYLISTS) {
                PlaylistsRoute(
                    widthSizeClass = widthSizeClass,
                    onOpenPlaylist = { navController.navigate(playlistRoute(it.value)) },
                )
            }

            composable(
                route = ROUTE_PLAYLIST,
                arguments = listOf(navArgument(ARG_PLAYLIST_ID) { type = NavType.StringType }),
            ) {
                PlaylistRoute(
                    widthSizeClass = widthSizeClass,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(ROUTE_GENRES) {
                GenresRoute(
                    widthSizeClass = widthSizeClass,
                    onOpenGenre = { navController.navigate(genreRoute(it)) },
                )
            }

            composable(
                route = ROUTE_GENRE,
                arguments = listOf(navArgument(ARG_GENRE) { type = NavType.StringType }),
            ) {
                GenreRoute(
                    widthSizeClass = widthSizeClass,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(NeedlerDestination.Search.route) {
                SearchRoute(
                    widthSizeClass = widthSizeClass,
                    onOpenAlbum = { navController.navigate(albumRoute(it.value)) },
                    onOpenArtist = { navController.navigate(artistRoute(it.value)) },
                    // The pack's Cancel returns to the library, and the bar has
                    // to agree with it, so this is the same tab switch the bar
                    // makes rather than a popBackStack that would leave Search
                    // selected under a library screen.
                    onCancel = { selectTab(NeedlerDestination.Library) },
                    // Where the expired-session banner's Sign in button goes.
                    // The same tab switch Cancel makes, for the same reason: the
                    // bar has to agree with where the user ended up.
                    onOpenSettings = { selectTab(NeedlerDestination.Settings) },
                )
            }

            composable(NeedlerDestination.Pulls.route) {
                PullsRoute(
                    widthSizeClass = widthSizeClass,
                    onOpenAlbum = { navController.navigate(albumRoute(it.value)) },
                    // Every empty state on Pulls ends in a button to Search; the same tab switch
                    // LibraryRoute's own "find something" route makes, so the bar lights Search
                    // rather than leaving Pulls lit under a search screen.
                    onOpenSearch = { selectTab(NeedlerDestination.Search) },
                )
            }
        }
    }
}
