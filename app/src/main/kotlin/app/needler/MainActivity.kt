package app.needler

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
                NeedlerApp(widthSizeClass = windowSizeClass.widthSizeClass)
            }
        }
    }
}
