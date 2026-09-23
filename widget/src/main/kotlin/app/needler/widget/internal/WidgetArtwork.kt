package app.needler.widget.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import app.needler.core.domain.model.ArtworkRef
import coil3.Image
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * Album art for a widget: a small, rounded, software [Bitmap], or null.
 *
 * ## Why the widget cannot draw artwork the way every other screen does
 *
 * Everywhere else in Needler artwork is a Coil `AsyncImage` handed a domain [ArtworkRef], and Coil
 * resolves, fetches, caches and cross-fades it inside the composition. None of that survives the
 * trip to a home screen. Glance composes in this app's process but the result is `RemoteViews`,
 * inflated and drawn by the *launcher*, which cannot run our code, cannot hold a Coil request and
 * cannot be handed a `Painter`. `androidx.glance.ImageProvider` takes a drawable resource, an
 * `Icon`, or a `Bitmap` - and for a cover fetched from a server the only one of those that exists is
 * a `Bitmap`. So the image has to be fully resolved, on this side, before the RemoteViews is built.
 *
 * ## Why it still goes through Coil
 *
 * REQUIREMENTS.md "Libraries" allows the project one OkHttp client and one certificate-pinning
 * policy, and `:app` already builds the one `ImageLoader` that honours it: `buildArtworkImageLoader`
 * registers `ArtworkRefMapper` (which is the only thing in the app that knows an
 * [ArtworkRef.Owned] becomes a Subsonic `getCoverArt` URL carrying the app-password, and an
 * [ArtworkRef.Catalogue] becomes an `/api/v1/covers/...` URL behind the bearer token), hands Coil
 * the `mediaClient` with its pinned certificate and redacted logs, and puts a 256 MB disk cache
 * behind it. `NeedlerApplication` publishes that loader as Coil's singleton.
 *
 * The widget runs in the same process, so [SingletonImageLoader.get] hands it that exact loader.
 * That is the whole design: **a widget must not open its own HTTP connection.** Building a second
 * client here would mean a second cert-pinning policy, a second credential store and a second
 * artwork cache, all for a 64dp square - and it would fetch covers past the pin that REQUIREMENTS.md
 * has one client precisely to enforce.
 *
 * ## Three things that are not optional
 *
 *  * **Software bitmaps only.** Coil defaults to hardware bitmaps, which live in graphics memory,
 *    cannot be read by a software `Canvas` and cannot cross a Binder boundary into the launcher.
 *    `allowHardware(false)` asks for a software one; the copy in [asSoftware] is the belt to that
 *    braces, because a `Bitmap.Config.HARDWARE` reaching a RemoteViews is a crash in someone else's
 *    process.
 *  * **A pixel budget.** A RemoteViews and everything in it crosses a Binder transaction with about
 *    a megabyte to spend, shared with every other widget updating at that moment. [MAX_EDGE_PX]
 *    caps the cover at 192px - 64dp at 3x density, which is the real size on most phones - for
 *    about 147 KB. A full-resolution cover off a FLAC-era release would be several megabytes and
 *    would take the launcher down with it.
 *  * **Corners drawn into the pixels.** The pack rounds the cover at 12px. Glance's `cornerRadius`
 *    modifier needs Android 12 (see `widget_card.xml`) and this project's minSdk is 26, so the
 *    radius is applied to the bitmap itself, in the same pass that crops it square.
 *
 * ## Failure is a tinted square, never an error
 *
 * Every failure path returns null and the card falls back to `widget_artwork_placeholder`. That is
 * not laziness: REQUIREMENTS.md "Widgets" requires the widgets to "render sensibly with no network
 * and no active playback, since that is their most common state", and a home screen is exactly where
 * a server is unreachable - a VPN-only DroppedNeedle, a phone on mobile data, a laptop that is off.
 * The tinted square is what `:core:design`'s `AsyncAlbumArt` shows in the same situation.
 */
internal class WidgetArtwork(private val context: Context) {

    /**
     * Fetches [ref] and returns it cropped square, scaled to [edgePx] and rounded by [cornerPx].
     *
     * @param ref the domain reference, handed to Coil unresolved. Nothing in this module knows how
     *   to turn one into a URL, and it must stay that way: the server address and the credentials
     *   live behind `:core:data`, which REQUIREMENTS.md "Widgets" forbids this module from seeing.
     * @return null when there is no artwork, when the server cannot be reached, or when anything at
     *   all goes wrong. The caller draws the placeholder tint.
     */
    suspend fun load(ref: ArtworkRef?, edgePx: Int, cornerPx: Float): Bitmap? {
        if (ref == null) return null
        val edge: Int = edgePx.coerceIn(1, MAX_EDGE_PX)
        return try {
            val request: ImageRequest = ImageRequest.Builder(context)
                .data(ref)
                .size(edge, edge)
                .allowHardware(false)
                .build()
            val result = SingletonImageLoader.get(context).execute(request)
            val image: Image = (result as? SuccessResult)?.image ?: return null
            // Explicit dimensions rather than toBitmap()'s defaults: the source's own size is what
            // the crop in roundedSquare needs, and passing them spells out that nothing is being
            // stretched here. A cover with no intrinsic size is not one Coil decoded from a server.
            if (image.width <= 0 || image.height <= 0) return null
            roundedSquare(asSoftware(image.toBitmap(image.width, image.height)), edge, cornerPx)
        } catch (cancellation: CancellationException) {
            // The Glance session is torn down whenever the launcher stops asking for this widget,
            // which happens constantly. Rethrowing keeps that cancellation structural instead of
            // turning it into "no artwork" and a pointless redraw.
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Centre-crops [source] to a square of [edge] pixels with corners of [cornerPx].
     *
     * One pass, through a [BitmapShader] with a scale-and-translate matrix, because the alternative
     * - scale, then clip a rounded path - antialiases the corners badly and allocates twice.
     * `max` rather than `min` on the scale is what makes it a crop rather than a letterbox: the
     * pack's covers fill their square, as `ContentScale.Crop` does everywhere else in the app.
     */
    private fun roundedSquare(source: Bitmap, edge: Int, cornerPx: Float): Bitmap {
        val output: Bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val scale: Float = max(
            edge.toFloat() / source.width.toFloat(),
            edge.toFloat() / source.height.toFloat(),
        )
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(
                (edge - source.width * scale) / 2f,
                (edge - source.height * scale) / 2f,
            )
        }
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        Canvas(output).drawRoundRect(
            RectF(0f, 0f, edge.toFloat(), edge.toFloat()),
            cornerPx,
            cornerPx,
            paint,
        )
        return output
    }

    /** A hardware bitmap copied back into main memory, or [bitmap] unchanged when it already is. */
    private fun asSoftware(bitmap: Bitmap): Bitmap =
        if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }

    companion object {

        /**
         * The widest a cover is ever decoded for a widget: 64dp at 3x, about 147 KB as ARGB_8888.
         *
         * Not a quality judgement - a budget. Everything in the RemoteViews shares one Binder
         * transaction, and the launcher is the process that pays for going over it.
         */
        const val MAX_EDGE_PX: Int = 192

        /** [dp] in this device's pixels, for sizing a request the design states in dp. */
        fun pixels(context: Context, dp: Float): Int =
            (dp * context.resources.displayMetrics.density).roundToInt()
    }
}
