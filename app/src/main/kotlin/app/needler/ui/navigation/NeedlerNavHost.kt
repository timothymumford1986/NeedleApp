package app.needler.ui.navigation

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.needler.connect.ConnectRoute
import app.needler.ui.placeholder.DestinationPlaceholder

/** Onboarding. Screens 01 and 16. Owned by `:app`. */
private const val ROUTE_CONNECT = "connect"

/** Everything after onboarding: the four destinations inside the navigation chrome. */
private const val ROUTE_HOME = "home"

/**
 * The app's navigation graph.
 *
 * Two levels, and the split is what keeps the chrome still while the content
 * changes.
 *
 * The **outer** graph holds Connect and Home. Connect has no bottom bar and no
 * nav rail - the pack draws none on screens 01 and 16 - so it cannot live
 * inside the scaffold, and onboarding is popped off the back stack the moment
 * it succeeds.
 *
 * The **inner** graph, inside [NeedlerHome], holds the four destinations. It
 * sits inside [NeedlerNavigationScaffold]'s content slot, so switching tab
 * recomposes the content and leaves the bar, the rail and the player sidebar
 * alone.
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
            )
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
 */
@Composable
private fun NeedlerHome(
    widthSizeClass: WindowWidthSizeClass,
    pullsBadgeCount: Int,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = NeedlerDestination.fromRoute(backStackEntry?.destination?.route)
        ?: NeedlerDestination.Start

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
    ) {
        NavHost(
            navController = navController,
            startDestination = NeedlerDestination.Start.route,
        ) {
            // Library, Search and Pulls belong to their feature modules and
            // Settings to :app; none of the four is built yet. Each renders the
            // same labelled placeholder, which names the module that replaces
            // it - see DestinationPlaceholder. Swapping one in is a one-line
            // change here.
            NeedlerDestination.entries.forEach { destination ->
                composable(destination.route) { DestinationPlaceholder(destination) }
            }
        }
    }
}
