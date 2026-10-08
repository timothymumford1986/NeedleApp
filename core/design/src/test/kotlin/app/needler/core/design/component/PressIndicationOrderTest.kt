package app.needler.core.design.component

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two source scans over the design system, for two things a screenshot cannot see.
 *
 * The first is which shape a press is drawn in, which is the file's original subject and everything
 * below this paragraph. The second is
 * [no control in the design system draws its boundary in the hairline], and it is here rather than
 * in a file of its own because it is the same kind of check made the same way - a property of the
 * source that no render, no baseline and no Compose UI test can report.
 *
 * ## Why this is a source scan and not a render
 *
 * `Modifier.clickable` draws its press indication over the bounds of the node it is attached to, and
 * `Modifier.clip` only shapes what is drawn after it. Put them the wrong way round and a round
 * button flashes a square - reported from the device as "you hit play, and a quick square outline
 * appears over the button", on the 80dp accent disc on Now Playing.
 *
 * That defect is invisible to every other kind of test in this repository. The indication is a
 * transient animation, so the 5 screenshot modules cannot hold it: a baseline captures the control
 * at rest and is identical whether the ripple would have been round or square. A Compose UI test can
 * inject a press but cannot read back the clip bounds the indication was drawn into. So the property
 * being asserted is a property of the *source*: in a chain that both clips and presses, the clip
 * comes first.
 *
 * It is a weaker guarantee than rendering and it is honest about that. It cannot tell a round control
 * from a rectangular one, so it does not try - it only fires when a file has decided a node needs a
 * shape and has applied that shape too late to matter. Every one of the three real faults found in
 * the audit of this application was exactly that pattern, and every genuinely rectangular list row
 * was not, because a list row never clips at all.
 *
 * ## The rejected alternative
 *
 * A lint rule, which is where a check like this belongs. It was rejected for this change because a
 * custom lint check is a new module, a new dependency and a new set of baselines, and it would make
 * the same judgement this test makes from the same information. If a lint rule is written later this
 * file should be deleted rather than kept beside it.
 *
 * ## What keeps it from passing vacuously
 *
 * It asserts it found a plausible number of interaction modifiers to look at. A scanner whose regexes
 * stopped matching - after a Compose release renames something, or a refactor moves the component
 * package - would otherwise report no faults by finding no code, which is the failure this whole file
 * exists to prevent.
 */
class PressIndicationOrderTest {

    @Test
    fun `no chain applies its shape after the press it was meant to shape`() {
        val offenders: List<String> = scanned().filter { it.clipsAfterPressing }.map { it.report() }

        assertEquals(
            "a modifier chain clips to a shape *after* taking the press, so the press is drawn on " +
                "the node's rectangular bounds and a round or pill-shaped control flashes a " +
                "rectangle. Move the `.clip(shape)` above the interaction modifier, or - better - " +
                "hand the shape and the interaction to `Modifier.needlerPressSurface`, which cannot " +
                "be called in the wrong order:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the scanner is looking at the code it claims to be looking at`() {
        val chains: List<Chain> = scanned()

        assertTrue(
            "found " + chains.size + " interaction modifiers across the Compose sources, which is " +
                "too few to be right - the regexes in this test have gone stale, not the code",
            chains.size >= MINIMUM_PLAUSIBLE_CHAINS,
        )
        assertTrue(
            "found no clipped-then-pressed chain at all, so the ordering check has nothing to " +
                "distinguish and would pass over a file that pressed before it clipped",
            chains.any { it.clipsBeforePressing },
        )
    }

    // ---- the boundary scan ---------------------------------------------------

    /**
     * No control in `:core:design` draws its own edge in
     * `NeedlerColors.hairline`.
     *
     * ## Why this is worth a test
     *
     * `rgba(242,245,238,0.08)` composites to `rgb(31,36,28)` on the canvas and measures **1.20:1**,
     * against the 3:1 WCAG 1.4.11 asks of "user interface components and their boundaries".
     * REQUIREMENTS.md "Accessibility" records that and carries it "as an open question rather than
     * quietly patched" - correctly, because the hairline is also what separates every surface in
     * the pack from the one behind it, and lifting the token repaints the design.
     *
     * The repair was therefore to split the role, not the value: `hairline` keeps the dividers and
     * `NeedlerColors.componentBorder` takes the control boundaries at 4.10:1. A split like that
     * holds for exactly as long as nobody writes `.border(..., colors.hairline, ...)` again, and
     * there is nothing in the type system to stop them - the two are both `Color`, both on the same
     * object, four characters apart in an autocomplete list. `StateBadgeLabelTest` asserts the two
     * ratios; this asserts that the right one is the one being used.
     *
     * ## Why only this module's sources
     *
     * Because this is the only tree this test can honestly fail a build over. `:feature:player` and
     * `:app` each draw hairline-bordered pills of their own - `OutputChip`, `SleepTimerControls`,
     * `PlaceholderScreen`, the Connect screen - and those are their modules' call sites to correct
     * against the token, not this one's to break a build over from underneath them. Widen
     * [BOUNDARY_TREES] when they have.
     *
     * ## What it does not claim
     *
     * It reads lines, so it can be fooled - a border colour routed through a local `val` two
     * screens away is invisible to it. It catches the shape the defect actually had in all eight
     * places it was found: the token named on the border call, or on one of the four lines above it.
     */
    @Test
    fun `no control in the design system draws its boundary in the hairline`() {
        val offenders: List<String> = hairlineBoundaries()

        assertEquals(
            "a control draws its own boundary in `colors.hairline`, which composites to 1.20:1 on " +
                "the canvas and is not a visible edge. Use `colors.componentBorder`, which is the " +
                "same role at 4.10:1; `colors.hairline` is for dividers and row separators only:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * The same guard the ordering scan has: it must have found the hairline *somewhere*, or its
     * regexes have gone stale and it is reporting no faults because it is reading no code.
     *
     * The dividers are what it should find - `NeedlerHairline`, `NeedlerVerticalHairline` and the
     * `outlineVariant` mapping - and those are the uses that are correct, so finding them is the
     * proof that a border use would have been found too.
     */
    @Test
    fun `the boundary scan can see the hairline at all`() {
        val uses: List<String> = hairlineUses()

        assertTrue(
            "found " + uses.size + " uses of `colors.hairline` in " +
                BOUNDARY_TREES.joinToString(", ") + ", which is too few to be right - the regexes " +
                "in this test have gone stale, not the code",
            uses.size >= MINIMUM_PLAUSIBLE_HAIRLINE_USES,
        )
    }

    /** Every `hairline` reference in the scanned trees, comments and KDoc excluded. */
    private fun hairlineUses(): List<String> = boundarySources().flatMap { (root, file) ->
        sourceLines(file).mapIndexedNotNull { index, line ->
            if (!mentionsHairline(line)) {
                null
            } else {
                file.relativeTo(root).invariantSeparatorsPath + ":" + (index + 1) + "  " + line.trim()
            }
        }
    }

    /**
     * The subset of those that are a control's boundary rather than a divider.
     *
     * A border is written one of two ways in this codebase - `.border(width, colour, shape)` on one
     * line, or `borderColor = ...` as a named argument, sometimes with the colour on a later line -
     * so the window is the line itself plus the [BORDER_WINDOW] lines above it. A divider is always
     * `.background(colors.hairline)` and never mentions a border, which is what separates the two.
     */
    private fun hairlineBoundaries(): List<String> = boundarySources().flatMap { (root, file) ->
        val lines: List<String> = sourceLines(file)
        lines.mapIndexedNotNull { index, line ->
            if (!mentionsHairline(line)) return@mapIndexedNotNull null
            val from: Int = (index - BORDER_WINDOW).coerceAtLeast(0)
            val window: String = lines.subList(from, index + 1).joinToString("\n") { masked(it) }
            if (BORDER_CALL.containsMatchIn(window)) {
                "  " + file.relativeTo(root).invariantSeparatorsPath + ":" + (index + 1) +
                    "  " + line.trim()
            } else {
                null
            }
        }
    }

    private fun boundarySources(): List<Pair<File, File>> {
        val root: File = repositoryRoot()
        return BOUNDARY_TREES
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { tree -> tree.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            .sortedBy { it.invariantSeparatorsPath }
            .map { root to it }
    }

    private fun sourceLines(file: File): List<String> =
        file.readText().replace("\r\n", "\n").split("\n")

    /**
     * Whether [line] names the token, in code rather than in prose.
     *
     * Every paragraph in this module that explains why the hairline is 1.20:1 mentions it by name,
     * including the two in `NeedlerColors` that exist to say "do not use this as a border", so a
     * scan that counted comments would report the documentation as the defect.
     */
    private fun mentionsHairline(line: String): Boolean {
        val trimmed: String = line.trim()
        val isProse: Boolean = trimmed.startsWith("*") ||
            trimmed.startsWith("//") ||
            trimmed.startsWith("/*")
        return !isProse && HAIRLINE_TOKEN.containsMatchIn(masked(line))
    }

    /**
     * [line] with `hairlineThickness` hidden.
     *
     * `.border(sizes.hairlineThickness, colors.componentBorder, shape)` is the *fixed* form of the
     * defect and contains both "border" and "hairline", so without this the repair reports itself.
     */
    private fun masked(line: String): String = line.replace("hairlineThickness", "thickness")

    // ---- the scan ------------------------------------------------------------

    /** One interaction modifier, and what its own chain does about shape on either side of it. */
    private data class Chain(
        val path: String,
        val line: Int,
        val modifier: String,
        val clipsBeforePressing: Boolean,
        val clipsAfterPressing: Boolean,
    ) {
        fun report(): String = "  " + path + ":" + line + "  ." + modifier
    }

    private fun scanned(): List<Chain> {
        val root: File = repositoryRoot()
        return composeSources(root).flatMap { file ->
            val lines: List<String> = file.readText().replace("\r\n", "\n").split("\n")
            lines.mapIndexedNotNull { index, line ->
                val applied: String = INTERACTION.find(line)
                    ?.groupValues
                    ?.get(1)
                    ?: return@mapIndexedNotNull null
                // The chain is delimited by structure either side: walking out from the interaction
                // line until a line stops looking like a continuation of one modifier chain is
                // enough to decide where a `.clip` sits relative to it, and that is the whole
                // question being asked.
                Chain(
                    path = file.relativeTo(root).invariantSeparatorsPath,
                    line = index + 1,
                    modifier = applied,
                    clipsBeforePressing = CLIP in chainLines(lines, index, towards = -1),
                    clipsAfterPressing = CLIP in chainLines(lines, index, towards = 1),
                )
            }
        }
    }

    /**
     * The lines of the modifier chain on one side of [from], as a single string.
     *
     * A chain ends where a line is neither a `.modifier(` continuation nor one of the brackets and
     * `then`/`if` scaffolding this codebase wraps conditional modifiers in. That is a heuristic, and
     * it errs towards stopping early: stopping early can only *miss* a fault, never invent one, which
     * is the right direction for a check that fails a build.
     */
    private fun chainLines(lines: List<String>, from: Int, towards: Int): String {
        val collected = StringBuilder()
        var at: Int = from + towards
        while (at in lines.indices) {
            val trimmed: String = lines[at].trim()
            val continues: Boolean = trimmed.startsWith(".") ||
                trimmed.startsWith("Modifier") ||
                // A comment between two modifiers does not end the chain, and this codebase puts
                // one between them often - including on the one chain that already documented its
                // own ordering, which the walk would otherwise stop dead at.
                trimmed.startsWith("//") ||
                trimmed in CHAIN_SCAFFOLDING ||
                trimmed.startsWith("if (") ||
                trimmed.startsWith("} else") ||
                trimmed.endsWith("->") ||
                (trimmed.startsWith("}") && trimmed.length <= 2) ||
                (trimmed.startsWith(")") && trimmed.length <= 2)
            if (!continues) break
            collected.append(trimmed).append('\n')
            at += towards
        }
        return collected.toString()
    }

    private fun composeSources(root: File): List<File> =
        SCANNED_TREES
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { tree -> tree.walkTopDown().filter { it.isFile && it.extension == "kt" } }
            // `widget/` and `wear/` are outside [SCANNED_TREES] on purpose: Glance and Wear Compose
            // each have their own modifier vocabulary and neither was in the audit this came out of.
            .filterNot { it.invariantSeparatorsPath.contains("/src/test/") }
            .sortedBy { it.invariantSeparatorsPath }

    private fun repositoryRoot(): File {
        val start = File(System.getProperty("user.dir").orEmpty().ifEmpty { "." }).absoluteFile
        var directory: File? = start
        var climbed = 0
        while (directory != null && climbed <= MAX_CLIMB) {
            if (File(directory, SETTINGS_FILE).isFile) return directory
            directory = directory.parentFile
            climbed++
        }
        throw AssertionError(
            "could not find " + SETTINGS_FILE + " in " + start + " or in any of its " + MAX_CLIMB +
                " parent directories, so the press-ordering check has no sources to read. It must " +
                "not pass in that state.",
        )
    }

    private companion object {
        val INTERACTION =
            Regex("""\.(clickable|combinedClickable|toggleable|selectable)\s*[({]""")
        const val CLIP: String = ".clip("

        val SCANNED_TREES: List<String> = listOf(
            "core/design/src/main",
            "feature",
            "app/src/main",
        )

        val CHAIN_SCAFFOLDING: Set<String> = setOf(
            "",
            ")",
            "),",
            "}",
            "},",
            "} else {",
            ".then(",
            "Modifier",
            "Modifier,",
        )

        /**
         * The trees [no control in the design system draws its boundary in the hairline] reads.
         *
         * Narrower than [SCANNED_TREES] on purpose, and the test's own KDoc says why: the feature
         * modules have hairline-bordered pills of their own to correct, and failing their build from
         * here would be this module breaking theirs.
         */
        val BOUNDARY_TREES: List<String> = listOf("core/design/src/main")

        /** `colors.hairline` or a bare `hairline`, but never `hairlineThickness`. */
        val HAIRLINE_TOKEN = Regex("""\bhairline\b""")

        /** The two ways a border is written here: positional on one line, or as a named argument. */
        val BORDER_CALL = Regex("""\.border\s*\(|borderColor\s*=""")

        /**
         * How far above a `hairline` line to look for the border call it belongs to.
         *
         * Four, because the widest form in this codebase spreads `.border(` over `width =`,
         * `color =` and `shape =` on separate lines with the closing paren after.
         */
        const val BORDER_WINDOW: Int = 4

        /**
         * The dividers the boundary scan must still be able to see: `NeedlerHairline`,
         * `NeedlerVerticalHairline` and Material's `outlineVariant`.
         */
        const val MINIMUM_PLAUSIBLE_HAIRLINE_USES: Int = 3

        const val SETTINGS_FILE: String = "settings.gradle.kts"
        const val MAX_CLIMB: Int = 6

        /**
         * The audit this test came out of counted 36 interaction modifiers across these trees. The
         * floor is set below that so adding a screen does not have to touch this file, and far
         * enough above zero that a broken regex cannot pass.
         */
        const val MINIMUM_PLAUSIBLE_CHAINS: Int = 25
    }
}
