package app.needler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.needler.core.design.motion.NeedlerSplash
import app.needler.core.design.theme.NeedlerTheme
import app.needler.ui.navigation.NeedlerNavHost

/**
 * The whole app below the theme: the navigation graph, with the launch
 * animation over the top of it.
 *
 * The ordering in the `Box` is the requirement, not a detail. REQUIREMENTS.md
 * "Performance budgets" says the 2.1 s splash "must overlay a screen already
 * loaded underneath and be skippable by a tap, so it never becomes the reason
 * the app feels slow", and that is exactly what this does: [NeedlerNavHost] is
 * the first child and composes, measures and draws whether or not the splash is
 * on top of it. Nothing waits for the animation.
 *
 * Three behaviours come free from [NeedlerSplash] itself and are worth naming
 * so nobody re-implements them here: a tap anywhere calls `onFinished`
 * immediately; under reduced motion it is never drawn at all and `onFinished`
 * fires on the first composition; and it paints its own opaque canvas layer, so
 * the content beneath it is genuinely hidden rather than dimmed.
 *
 * The finished flag is saved, so a configuration change - a rotation, a fold,
 * an unfold - does not replay a 2.1 s animation over a session the user is
 * already several taps into.
 */
@Composable
fun NeedlerApp(
    widthSizeClass: WindowWidthSizeClass,
    modifier: Modifier = Modifier,
    pullsBadgeCount: Int = 0,
) {
    var splashFinished by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NeedlerTheme.colors.canvas),
    ) {
        NeedlerNavHost(
            widthSizeClass = widthSizeClass,
            pullsBadgeCount = pullsBadgeCount,
        )

        if (!splashFinished) {
            NeedlerSplash(
                onFinished = { splashFinished = true },
                // The pack draws the mark at 240dp on the phone Connect screen
                // and 420dp on the tablet one.
                markSize = if (widthSizeClass == WindowWidthSizeClass.Expanded) {
                    TABLET_MARK
                } else {
                    PHONE_MARK
                },
            )
        }
    }
}

private val PHONE_MARK: Dp = 240.dp
private val TABLET_MARK: Dp = 420.dp
