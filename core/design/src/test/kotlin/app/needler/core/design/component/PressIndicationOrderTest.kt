package app.needler.core.design.component

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the one thing a screenshot cannot see: which shape a press is drawn in.
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
