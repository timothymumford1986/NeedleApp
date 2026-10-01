package app.needler.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import app.needler.core.design.theme.NeedlerColors
import app.needler.core.design.theme.NeedlerDarkColors
import app.needler.core.design.theme.NeedlerTheme

/**
 * Artwork that has something to show when there is no artwork: a letter over a tint derived from the
 * album's or artist's own identity.
 *
 * ## What this replaces
 *
 * [AsyncAlbumArt] alone degrades to `NeedlerColors.artworkPlaceholder`, which is `#1f271b` - the same
 * raised-surface grey as a pressed row. Its own KDoc calls that "a coloured square", and on a device
 * it is not one: an album with no cover draws as a flat empty box, indistinguishable from a layout
 * fault, and a grid of them reads as a broken screen rather than as a library. There is nothing in
 * the design pack to copy here, because the pack tints every square from artwork it has.
 *
 * So the tint is computed instead, and the rules it is computed under are the point:
 *
 *  * **Deterministic.** The same album is the same colour on the library grid, on album detail, in
 *    search results and in a widget, today and after a reinstall. A random or
 *    remembered-per-composition colour would make the same record look like two different records on
 *    two screens, which is worse than a grey box.
 *  * **From the palette.** REQUIREMENTS.md "Design system" is a single dark theme with one accent and
 *    one positive; a placeholder inventing hues of its own would be the loudest thing in the app.
 *    Every tint here is one of the three signal colours mixed into [NeedlerColors.surface], which is
 *    also the shape of the pack's own per-album tints (`#5a1f22`, `#2d3d24`). No new value is added
 *    to the palette and no hex appears in this file.
 *  * **A letter, not a glyph.** The first letter of the title is information - it tells a listener
 *    which record they are looking at in a grid of coverless albums - where a generic music note
 *    tells them nothing they did not already know.
 *
 * ## Where it lives
 *
 * `:core:design`, not in a feature module, because the same hole exists on the search results, the
 * artist rows and the home-screen widgets. One derivation means one colour per album everywhere;
 * three copies of it would mean three, discovered one screen at a time.
 */
@Composable
fun NeedlerArtworkPlaceholder(
    identity: String,
    name: String?,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
    contentDescription: String? = null,
) {
    val colors: NeedlerColors = NeedlerTheme.colors
    val letter: String? = artworkPlaceholderInitial(name)
    // Resolved to a non-null local before the modifier chain, so the `semantics` lambda closes over
    // a plain `String` rather than relying on a smart cast surviving into a non-inline lambda.
    val spoken: String = contentDescription.orEmpty()

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(artworkPlaceholderTint(identity, colors))
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = spoken }
                } else {
                    // Decorative: the row or cell around this already names the album, and a
                    // placeholder that announced itself would have TalkBack say the title twice.
                    Modifier.clearAndSetSemantics {}
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (letter == null) return@BoxWithConstraints
        // Sized from the box rather than from the type scale, because this is a graphic and not
        // copy: the square it sits in does not grow at 200% text, so a letter that did would
        // overflow a 48dp thumbnail. `Dp.toSp()` divides by the font scale, which is what makes the
        // glyph render at the same fraction of the tile at every text size. REQUIREMENTS.md
        // "Accessibility" asks that text scale to 200% without clipping; this satisfies it by not
        // being text in the first place, and the album's name is still read aloud by the row.
        val side: Dp = minOf(maxWidth, maxHeight)
        // An unconstrained parent - a placeholder dropped into a scrolling column with no size -
        // gives an infinite bound, and an infinite font size draws nothing at all. The type scale's
        // own 20sp is the fallback, because a letter at the wrong size is still a letter.
        val fontSize: TextUnit? = if (side.value.isFinite()) {
            with(LocalDensity.current) { (side * LETTER_FRACTION).toSp() }
        } else {
            null
        }
        Text(
            text = letter,
            style = NeedlerTheme.typography.avatarInitial.let { base ->
                if (fontSize == null) base else base.copy(fontSize = fontSize)
            },
            // Secondary rather than primary. Measured against the lightest tint this function can
            // produce, it is 3.44:1 - under the 4.5:1 AA threshold for body text and over the 3:1
            // one for large text, which a glyph at two-fifths of its tile always is. The primary
            // off-white clears AA outright and was rejected on looks: a coverless album would then
            // be the brightest thing on a grid of real covers.
            color = NeedlerTheme.colors.textSecondary,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Artwork with the placeholder behind it: what every surface that shows a cover should call.
 *
 * The placeholder is drawn underneath and [AsyncAlbumArt] over it with a transparent tint of its own,
 * so the letter is what shows while the image is in flight and what stays when there is no image at
 * all. Coil reports neither case to this layer - an album with no cover and an album whose cover is
 * still arriving look identical from here - and that is why the placeholder is a layer rather than a
 * branch: there is no state to branch on, and the correct picture is the same either way.
 *
 * @param model anything Coil accepts. Needler passes an
 *   `app.needler.core.domain.model.ArtworkRef` and `:app` maps it to a request; passing `null` skips
 *   the image and draws the placeholder alone.
 * @param identity the stable key the tint is derived from: a release-group MBID for an album, an
 *   artist MBID for an artist. **Not** the title - two different records called "Greatest Hits" should
 *   be two colours, and a retitled record should not change colour.
 * @param name the album or artist name, for the letter. Pass the title, not the identity.
 * @param contentDescription as [AsyncAlbumArt]: build it with [albumArtContentDescription], or pass
 *   `null` when the surrounding row or cell already names the album.
 */
@Composable
fun NeedlerArtwork(
    model: Any?,
    identity: String,
    name: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = NeedlerTheme.shapes.artworkThumb,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier = modifier.clip(shape)) {
        NeedlerArtworkPlaceholder(
            identity = identity,
            name = name,
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            contentDescription = contentDescription,
        )
        if (model != null) {
            AsyncAlbumArt(
                model = model,
                // The placeholder underneath owns the description, so the image must not repeat it.
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                placeholderColor = Color.Transparent,
                contentScale = contentScale,
            )
        }
    }
}

/**
 * The tint for one identity: deterministic, and always one of [ARTWORK_TINT_COUNT] mixes of a palette
 * colour into [NeedlerColors.surface].
 *
 * Not a `@Composable`, and taking the palette as a parameter with a default, so that it can be tested
 * as a plain function and reached from a Glance widget, where there is no `NeedlerTheme` to read.
 * `NeedlerDarkColors` is the one palette in the pack - there is no light theme - so the default is
 * the answer in every case the app has today.
 *
 * ## Why the hash is written out
 *
 * `String.hashCode` would do the job and is stable on the JVM, but it is stable *by specification of
 * one platform*, and the same album has to be the same colour in the app, in a widget and in
 * whatever renders next. An FNV-1a fold is four lines, depends on nothing, and cannot change under a
 * runtime upgrade. `identity` is lowercased first so that an id differing only in case - a hand-typed
 * MBID, a `ar-`-prefixed fixture - does not produce a second colour for the same artist.
 */
fun artworkPlaceholderTint(
    identity: String,
    colors: NeedlerColors = NeedlerDarkColors,
): Color {
    val hues: List<Color> = listOf(colors.accent, colors.positive, colors.destructive)
    val index: Int = (fold(identity) % ARTWORK_TINT_COUNT.toUInt()).toInt()
    val hue: Color = hues[index % hues.size]
    // Two strengths of each hue, which is how three palette colours become six distinguishable
    // tints without a fourth hue being invented. The darker pass comes first, so a library of one
    // or two albums is drawn in the quieter half.
    val strength: Float = TINT_STRENGTHS[index / hues.size]
    return mix(base = colors.surface, over = hue, fraction = strength)
}

/**
 * The letter drawn on a placeholder, or `null` when [name] has none to offer.
 *
 * The first letter or digit, uppercased. Leading punctuation and whitespace are skipped so that
 * `"...And Justice for All"` reads `A` rather than a full stop; a leading article is deliberately
 * **not** skipped, because the pack sorts and lists "The Marías" under T and a placeholder that
 * disagreed with the list it sits in would be worse than one that is merely dull.
 *
 * `null` rather than a fallback glyph when nothing is usable: a tile with a colour and no letter is
 * honest, where a question mark or a music note would look like an error the user could fix.
 */
fun artworkPlaceholderInitial(name: String?): String? {
    val character: Char = name?.firstOrNull { it.isLetterOrDigit() } ?: return null
    return character.uppercaseChar().toString()
}

/** How many distinct tints [artworkPlaceholderTint] chooses between. */
const val ARTWORK_TINT_COUNT: Int = 6

/**
 * How much of the tile's shorter side the letter occupies.
 *
 * Two-fifths keeps the glyph unmistakably large text - the 3:1 contrast threshold rather than the
 * 4.5:1 one - at every artwork size the app draws, from a 48dp crate thumbnail to a 270dp hero.
 */
private const val LETTER_FRACTION: Float = 0.4f

/**
 * How far each hue is mixed into the surface.
 *
 * Both are low on purpose. The palette's three signal colours are pale and bright; used at full
 * strength across a grid they would shout over the real covers beside them, and the pale red would
 * read as a failure rather than as a record.
 */
private val TINT_STRENGTHS: FloatArray = floatArrayOf(0.18f, 0.32f)

/**
 * FNV-1a over the UTF-16 code units of [value], lowercased.
 *
 * Unsigned so the modulo cannot be negative, which is the classic way a hash-to-index lands on an
 * `IndexOutOfBoundsException` for one unlucky album.
 */
private fun fold(value: String): UInt {
    var hash: UInt = FNV_OFFSET_BASIS
    value.lowercase().forEach { character ->
        hash = hash xor character.code.toUInt()
        hash *= FNV_PRIME
    }
    return hash
}

private const val FNV_OFFSET_BASIS: UInt = 2_166_136_261u

private const val FNV_PRIME: UInt = 16_777_619u

/**
 * Linear interpolation between two palette colours, component by component in sRGB.
 *
 * Written out rather than using `androidx.compose.ui.graphics.lerp`, which interpolates through
 * Oklab: perceptually better, and it makes the result of this function a property of a colour-space
 * implementation rather than of arithmetic anyone can check. These tints are asserted on in a unit
 * test, and the assertion should hold because of what the numbers are.
 */
private fun mix(base: Color, over: Color, fraction: Float): Color = Color(
    red = base.red + (over.red - base.red) * fraction,
    green = base.green + (over.green - base.green) * fraction,
    blue = base.blue + (over.blue - base.blue) * fraction,
    alpha = 1f,
)
