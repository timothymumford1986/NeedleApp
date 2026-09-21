package app.needler

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.needler.background.BackgroundViewModel
import app.needler.core.data.background.NotificationDestination
import app.needler.core.design.theme.NeedlerTheme
import app.needler.ui.NeedlerApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * The one activity.
 *
 * Needler is a single-activity Compose app. Connect, the four navigation
 * destinations and everything the feature modules add are composables inside
 * this host; nothing else gets an activity of its own.
 *
 * Three things happen here and nowhere else.
 *
 * **The system splash is dismissed immediately.** `installSplashScreen()` is
 * called so the window has a canvas-coloured background and the record mark
 * from the first frame, and then the keep-on-screen condition is left false.
 * REQUIREMENTS.md budgets cold start to library content at under 1.2 s and
 * notes that the pack's 2.1 s record animation "must overlay a screen already
 * loaded underneath and be skippable by a tap, so it never becomes the reason
 * the app feels slow". Holding the system splash for the animation's duration
 * would do exactly what that rule forbids, so the animation is
 * [app.needler.core.design.motion.NeedlerSplash], composed *over* the loaded
 * content by [NeedlerApp].
 *
 * **Edge-to-edge.** Both system bars are transparent and the content draws
 * behind them; the individual screens consume the insets they need.
 *
 * **The window size class.** REQUIREMENTS.md "Tablet layout": one navigation
 * model at two widths, driven by `WindowSizeClass`, with nothing built twice.
 * The class is computed once here and handed down, so every composable below
 * takes it as a parameter and can therefore be rendered at either width in a
 * screenshot test without an activity of the right size.
 *
 * Two more things arrive here because they are an Activity's to own.
 *
 * **The runtime notification permission.** `POST_NOTIFICATIONS` has to be
 * requested from an Activity, and it is requested at a moment that means
 * something rather than on launch - see [BackgroundViewModel]. A refusal
 * changes nothing structural: the Pulls badge is the channel REQUIREMENTS.md
 * relies on and it needs no permission at all.
 *
 * **Notification taps.** A notification carries its destination as two intent
 * extras, because the module that posts it (`:core:data`) must not know that
 * this Activity exists. They are decoded here and handed to the navigation
 * graph.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val background: BackgroundViewModel by viewModels()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Both of these must run before super.onCreate: the splash screen
        // installs a window callback, and edge-to-edge sets window flags that
        // the first frame reads.
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { false }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        super.onCreate(savedInstanceState)

        setContent {
            NeedlerTheme {
                val windowSizeClass = calculateWindowSizeClass(this)

                // The tap target of whichever notification opened the app, or
                // null on an ordinary launch. Held as state rather than read
                // once, so a tap that arrives while the app is already open
                // (onNewIntent) navigates too.
                var pendingDestination by mutableStateOf(destinationFrom(intent))
                notificationDestinationSink = { pendingDestination = it }

                val badge by background.pullsBadgeCount.collectAsStateWithLifecycle()
                val askForPermission by background
                    .shouldRequestNotificationPermission
                    .collectAsStateWithLifecycle()

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted -> background.onNotificationPermissionResult(granted) }

                LaunchedEffect(askForPermission) {
                    // The policy only ever says yes on Android 13 and above,
                    // where the permission exists; the check is belt and braces
                    // against launching a contract for a permission the
                    // platform does not know.
                    if (askForPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                NeedlerApp(
                    widthSizeClass = windowSizeClass.widthSizeClass,
                    pullsBadgeCount = badge,
                    notificationDestination = pendingDestination,
                    onNotificationDestinationHandled = { pendingDestination = null },
                )
            }
        }
    }

    /**
     * The schedule is re-armed on every foreground, and a stale mirror is
     * refreshed here rather than waiting for a screen to ask.
     *
     * `WorkManager` survives reboots on its own but not an app update or a
     * force-stop, and a poller that quietly stopped looks exactly like a server
     * with nothing to report.
     */
    override fun onResume() {
        super.onResume()
        background.onAppForegrounded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destinationFrom(intent)?.let { notificationDestinationSink?.invoke(it) }
    }

    /** Set by the composition so a later intent can reach the same state holder. */
    private var notificationDestinationSink: ((NotificationDestination?) -> Unit)? = null

    private fun destinationFrom(intent: Intent?): NotificationDestination? =
        NotificationDestination.decode(
            kind = intent?.getStringExtra(NotificationDestination.EXTRA_KIND),
            id = intent?.getStringExtra(NotificationDestination.EXTRA_ID),
        )
}
