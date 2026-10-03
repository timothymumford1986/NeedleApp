package app.needler.core.design.component

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * There is exactly one favourite glyph in the repository.
 *
 * This is the regression test for a defect the device reported as "some screens have a 'star' for
 * favourites, some have a love heart. Just be consistent." Two modules had drawn their own, because a
 * feature module cannot see another feature module and the design pack owns no favourite control for
 * either to transcribe - so the cheapest thing to do was always to draw a third one. That is what this
 * test makes expensive.
 *
 * It reads source rather than rendering anything, because what is asserted is not how the control
 * looks but that there is only one of it. A screenshot cannot catch a second glyph: it would simply
 * record the second glyph.
 *
 * Only `src/main` is scanned. This file names the forbidden shapes itself, and a scan that included
 * test sources would fail on its own text.
 */
class FavouriteGlyphTest {

    @Test
    fun `exactly one favourite glyph constant is declared`() {
        val declarations: List<Pair<String, String>> = mainSources().flatMap { file ->
            GLYPH_DECLARATION.findAll(file.readText()).map { pathOf(file) to it.value }
        }

        assertEquals(
            "a favourite glyph is declared in more than one place: " +
                declarations.joinToString { it.first + " -> " + it.second },
            1,
            declarations.size,
        )
        assertEquals(
            "the favourite glyph is not where its KDoc says it lives",
            "core/design/src/main/kotlin/app/needler/core/design/component/FavouriteButton.kt",
            declarations.single().first,
        )
    }

    /**
     * The same, by shape rather than by name: a copy called something this test did not think of -
     * `PathLove`, `PathFave` - would still carry the path data.
     */
    @Test
    fun `the heart path data appears in one file only`() {
        val holders: List<String> =
            mainSources().filter { HEART_PATH_PREFIX in it.readText() }.map { pathOf(it) }

        assertEquals(
            "the heart path data is duplicated: " + holders.joinToString(),
            1,
            holders.size,
        )
    }

    /**
     * The star is gone, not merely unused.
     *
     * REQUIREMENTS.md "Playlists" forbids star *ratings* - `setRating` on this server validates its
     * input and persists nothing - and a five-pointed star is that feature's signifier, so the shape is
     * not one to leave lying about for the next screen to pick up.
     */
    @Test
    fun `no star path data survives anywhere`() {
        val holders: List<String> =
            mainSources().filter { STAR_PATH_PREFIX in it.readText() }.map { pathOf(it) }

        assertEquals("the old star path is still declared", emptyList<String>(), holders)
    }

    /** No feature module draws its own favourite control: both call [NeedlerFavouriteButton]. */
    @Test
    fun `the feature modules hold no favourite glyph of their own`() {
        val offenders: List<String> = mainSources()
            .filter { pathOf(it).startsWith("feature/") }
            .filter { GLYPH_DECLARATION.containsMatchIn(it.readText()) }
            .map { pathOf(it) }

        assertEquals("a feature module declares its own glyph", emptyList<String>(), offenders)
    }
}

/**
 * A glyph constant for a favourite, however it is spelled.
 *
 * Matches a declaration and not a usage, so the references to [PathHeart] at the call sites do not
 * count - only somebody declaring a second shape does.
 */
private val GLYPH_DECLARATION =
    Regex("""const\s+val\s+Path[A-Za-z]*(Heart|Star|Favourite|Favorite|Love)[A-Za-z]*""")

/** The first curve of the heart, enough to recognise a copy of it. */
private const val HEART_PATH_PREFIX = "M12 20.6l-7.1-7.1"

/** The first two points of the star it replaced. */
private const val STAR_PATH_PREFIX = "M12 3.5L14.29 8.85"

private val SKIPPED_DIRECTORIES = setOf("build", ".git", ".gradle", ".kotlin", ".idea")

/**
 * The repository root, found the way the screenshot harness finds it: the nearest ancestor holding
 * `settings.gradle.kts`. A unit test's working directory is its own module.
 */
private val repositoryRoot: File by lazy {
    generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("no settings.gradle.kts above " + File(".").absolutePath)
}

private fun pathOf(file: File): String = file.relativeTo(repositoryRoot).invariantSeparatorsPath

/** Every Kotlin file in a `src/main` source set, build output and VCS metadata aside. */
private fun mainSources(): List<File> = MAIN_SOURCES

private val MAIN_SOURCES: List<File> by lazy {
    val files = repositoryRoot.walkTopDown()
        .onEnter { it.name !in SKIPPED_DIRECTORIES }
        .filter { it.isFile && it.extension == "kt" }
        .filter { "/src/main/" in pathOf(it) }
        .toList()
    // A walk that found nothing would pass every assertion above in silence.
    assertTrue(
        "only " + files.size + " Kotlin sources found under " + repositoryRoot,
        files.size > 100,
    )
    files
}
