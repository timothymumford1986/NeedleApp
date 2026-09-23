package app.needler.ui.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavType
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.needler.connect.ConnectRoute
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
 * Detail destinations, which live inside the scaffold alongside the four tabs.
 *
 * The argument names match what :feature:library reads from its SavedStateHandle
 * (AlbumViewModel.ALBUM_ID_ARG and ArtistViewModel.ARTIST_ID_ARG). Values are bare
 * MBIDs; the `al-` and `ar-` Subsonic prefixes never appear in a route.
 */
private const val ARG_ALBUM_ID = "albumId"
private const val ARG_ARTIST_ID = "artistId"
private const val ROUTE_ALBUM = "album/{$ARG_ALBUM_ID}"
private const val ROUTE_ARTIST = "artist/{$ARG_ARTIST_ID}"

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

private fun artistRoute(mbid: String): String = "artist/$mbid"

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
 * @param startConnected start at Home rather than Connect. The host passes true
 *   once a saved session exists; until `SessionRepository` has an
 *   implementation behind it, a cold start begins at Connect.
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
    NavHost(
        navController = navController,
        startDestination = if (startConnected) ROUTE_HOME else ROUTE_CONNECT,
        modifier = modifier,
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
                notificationDestination = notificationDestination,
                onNotificationDestinationHandled = onNotificationDestinationHandled,
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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = NeedlerDestination.fromRoute(backStackEntry?.destination?.route)
        ?: NeedlerDestination.Start

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
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
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
            )
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = NeedlerDestination.Start.route,
        ) {
            composable(NeedlerDestination.Library.route) {
                LibraryRoute(
                    widthSizeClass = widthSizeClass,
                    onOpenAlbum = { navController.navigate(albumRoute(it.value)) },
                    onOpenArtist = { navController.navigate(artistRoute(it.value)) },
                    onOpenSearch = { selectTab(NeedlerDestination.Search) },
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

            composable(
                route = ROUTE_ARTIST,
                arguments = listOf(navArgument(ARG_ARTIST_ID) { type = NavType.StringType }),
            ) {
                ArtistRoute(
                    widthSizeClass = widthSizeClass,
                    onBack = { navController.popBackStack() },
                    onOpenAlbum = { navController.navigate(albumRoute(it.value)) },
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
                    // Both of these end at Connect, and both have to clear Home
                    // behind them: signing out leaves no session for the tabs to
                    // read, and changing server invalidates everything they are
                    // showing. Same shape as onConnected's pop in reverse.
                    onChangeServer = { returnToConnect(navController) },
                    onSignedOut = { returnToConnect(navController) },
                )
            }

            composable(ROUTE_EQUALISER) {
                EqualiserRoute(onBack = { navController.popBackStack() })
            }

            composable(ROUTE_CROSSFADE) {
                CrossfadeRoute(onBack = { navController.popBackStack() })
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
                )
            }

            composable(NeedlerDestination.Pulls.route) {
                PullsRoute(
                    widthSizeClass = widthSizeClass,
                    onOpenAlbum = { navController.navigate(albumRoute(it.value)) },
                )
            }
        }
    }
}
