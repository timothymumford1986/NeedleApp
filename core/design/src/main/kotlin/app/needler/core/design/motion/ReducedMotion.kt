package app.needler.core.design.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the user has asked for reduced motion.
 *
 * The design pack suppresses every animation under `prefers-reduced-motion: reduce` - the splash is
 * removed outright (`display: none`), the staggered rise is cancelled, and the record stops
 * spinning. On Android the equivalent signal is `Settings.Global.ANIMATOR_DURATION_SCALE` being
 * zero, which is what "Remove animations" in Accessibility settings sets.
 *
 * [app.needler.core.design.theme.NeedlerTheme] installs this from [rememberSystemReducedMotion], and
 * **every** animation helper in this package consults it. Nothing in `:core:design` starts an
 * animation without reading it first.
 *
 * Override it in tests, previews or a screenshot harness by providing it directly.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * Reads `ANIMATOR_DURATION_SCALE` and keeps watching it, so toggling the setting while Needler is
 * in the foreground takes effect immediately.
 *
 * @return `true` when the animator duration scale is zero, i.e. the user wants no animation.
 */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember(context) { mutableStateOf(readsAsReducedMotion(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = readsAsReducedMotion(context)
            }
        }
        // Wrapped: a restricted profile or a stubbed ContentResolver in a test can throw here, and
        // failing to observe the setting must never take the UI down.
        runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                /* notifyForDescendants = */ false,
                observer,
            )
        }
        onDispose { runCatching { resolver.unregisterContentObserver(observer) } }
    }
    return reduced
}

/**
 * Whether the platform animator scale is off.
 *
 * Defaults to "animations on" if the setting cannot be read, so a failure to query never leaves the
 * app silently animation-free.
 */
private fun readsAsReducedMotion(context: Context): Boolean {
    val scale = runCatching {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    }.getOrDefault(1f)
    return scale == 0f
}
