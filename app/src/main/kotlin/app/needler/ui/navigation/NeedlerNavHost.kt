package app.needler.ui.navigation

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import app.needler.feature.library.album.AlbumRoute
import app.needler.feature.library.artist.ArtistRoute
import app.needler.feature.library.library.LibraryRoute
import app.needler.feature.player.crate.CrateRoute
import app.needler.feature.player.nowplaying.MiniPlayerRoute
import app.needler.feature.player.nowplaying.NowPlayingRoute
import app.needler.ui.placeholder.DestinationPlaceholder

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
            NowPlayingRoute(
                // The chevron down in the header. A collapse rather than a back,
                // but the same call AlbumRoute and ArtistRoute make: whatever
                // Home was showing is still underneath, so popping to it is the
                // whole of the gesture.
                onClose = { navController.popBackStack() },
                onOpenCrate = {
                    navController.navigate(ROUTE_CRATE) { launchSingleTop = true }
                },
                // Nowhere to send this yet. :feature:player does build the
                // picker - screen 21, as OutputPickerRoute - but it takes no
                // dismiss callback, and its own KDoc leaves it to the host to
                // decide whether it is a modal sheet or a destination of its
                // own. Registering it here would be answering that question by
                // accident, and stranding the listener on a screen with no way
                // off it. The chip is inert until the host chooses; every other
                // control on Now Playing works.
                onChooseOutput = {},
            )
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

    NeedlerNavigationScaffold(
        widthSizeClass = widthSizeClass,
        selected = selected,
        onSelect = { destination ->
            navController.navigate(destination.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
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
                    onOpenSearch = { navController.navigate(NeedlerDestination.Search.route) },
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

            // Search, Pulls and Settings are still placeholders. Each names the
            // module that will replace it - see DestinationPlaceholder.
            listOf(
                NeedlerDestination.Search,
                NeedlerDestination.Pulls,
                NeedlerDestination.Settings,
            ).forEach { destination ->
                composable(destination.route) { DestinationPlaceholder(destination) }
            }
        }
    }
}
