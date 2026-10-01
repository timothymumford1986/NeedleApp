package app.needler.licences

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The thing that makes [LicenceCatalogue] worth having: a list that cannot go stale quietly.
 *
 * REQUIREMENTS.md "Legal and attribution" requires the Licences screen to "list every bundled
 * dependency and its licence". A hand-maintained list satisfies that on the day it is written and
 * becomes a lie on the day somebody adds a dependency — and nothing about adding a dependency would
 * otherwise remind them this file exists. A list that silently goes stale is worse than no list,
 * because it looks like an answer.
 *
 * So the freshness check is a test, it reads the real `gradle/libs.versions.toml` off disk, and it runs
 * in `:app:testDebugUnitTest` — the task CI runs — which means it fails on the commit that adds the
 * dependency rather than at the next release. [LicenceCatalogue]'s own documentation records why the
 * list is data rather than generated at build time; this file is the other half of that decision.
 *
 * ## Why it reads the file rather than embedding a copy of the expected set
 *
 * Because a copy is the same problem one level down. The whole failure mode being guarded against is
 * "two lists of dependencies that drift apart", and answering it with a third list would be absurd.
 * The version catalogue is the project's single statement of what it depends on, so that is what gets
 * parsed.
 *
 * ## Why the check is exact in both directions
 *
 * A subset check catches an added dependency and misses a removed one, which leaves the screen naming
 * a library that is not there any more. Both directions are asserted, and each failure message names
 * the coordinates rather than reporting a count, because the point of the test is to tell whoever
 * broke it exactly what to write.
 */
class LicenceCatalogueTest {

    @Test
    fun `every dependency in the version catalogue has a licence recorded for it`() {
        val inCatalogue: Set<String> = coordinatesInVersionCatalogue()
        val declared: Set<String> = LicenceCatalogue.declaredCoordinates

        val undeclared: List<String> = (inCatalogue - declared).sorted()

        assertEquals(
            "gradle/libs.versions.toml declares dependencies that LicenceCatalogue does not list. " +
                "REQUIREMENTS.md \"Legal and attribution\" requires every bundled dependency and its " +
                "licence on the Licences screen, so add an entry for each of these - with its real " +
                "licence, looked up, not guessed: " + undeclared,
            emptyList<String>(),
            undeclared,
        )
    }

    @Test
    fun `no entry names a dependency the project no longer has`() {
        val inCatalogue: Set<String> = coordinatesInVersionCatalogue()
        val declared: Set<String> = LicenceCatalogue.declaredCoordinates

        val fictional: List<String> = (declared - inCatalogue).sorted()

        assertEquals(
            "LicenceCatalogue lists dependencies that are not in gradle/libs.versions.toml any " +
                "more. A licences screen naming a library that is not in the app is as wrong as one " +
                "omitting a library that is: " + fictional,
            emptyList<String>(),
            fictional,
        )
    }

    @Test
    fun `no coordinate is claimed by two entries`() {
        val claims: List<String> = LicenceCatalogue.components.flatMap { it.coordinates }

        val duplicated: List<String> = claims.groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .sorted()

        assertEquals(
            "the same dependency is listed under two entries, so the screen would show it twice " +
                "and possibly under two different licences: " + duplicated,
            emptyList<String>(),
            duplicated,
        )
    }

    @Test
    fun `the parser found something, so a silently empty check cannot pass`() {
        // Without this, a change to the version catalogue's formatting that broke the regex below
        // would make `inCatalogue` empty, the set difference empty, and both freshness tests pass
        // while checking nothing at all. That is the exact failure this whole file exists to prevent,
        // so it is asserted rather than assumed.
        val inCatalogue: Set<String> = coordinatesInVersionCatalogue()

        assertTrue(
            "parsed " + inCatalogue.size + " dependencies out of gradle/libs.versions.toml, which " +
                "is too few to be right - the parser in this test is broken, not the catalogue",
            inCatalogue.size >= MINIMUM_PLAUSIBLE_DEPENDENCIES,
        )
        assertTrue(inCatalogue.contains("com.squareup.okhttp3:okhttp"))
        assertTrue(inCatalogue.contains("androidx.media3:media3-exoplayer"))
    }

    // ---- Needler's own licence -----------------------------------------------

    @Test
    fun `needler is shown as Apache-2 0, first, with the server's licence explained`() {
        // REQUIREMENTS.md: "**Needler is licensed Apache-2.0** ... The Licences screen must therefore
        // show Apache-2.0 for Needler alongside each bundled dependency."
        assertEquals(Licence.Apache2, LicenceCatalogue.needler.licence)
        assertEquals("Apache-2.0", LicenceCatalogue.needler.licence.spdxId)
        assertEquals(LicenceCatalogue.needler, LicenceCatalogue.everything.first())

        // And the AGPL question REQUIREMENTS.md answers - "no copyleft obligation attaches to Needler
        // itself" - is answered on the screen rather than only in the document.
        val note: String = LicenceCatalogue.needler.note.orEmpty()
        assertTrue(note, note.contains("AGPL-3.0"))
    }

    @Test
    fun `the apache text this app carries is the licence this repository ships`() {
        // The constant was generated from this file rather than transcribed, and this is what keeps
        // that true. A legal document rewritten by hand is a legal document with an unknown diff in it.
        val onDisk: String = File(repositoryRoot(), "LICENSE")
            .readText()
            .replace("\r\n", "\n")
        val terms: String = onDisk.substringBefore(END_OF_TERMS) + END_OF_TERMS

        assertEquals(terms.trimStart('\n'), APACHE_2_0_TERMS)
    }

    @Test
    fun `apache-2 0 is the only licence whose full text is carried, and it is carried`() {
        // Section 4(a) of Apache-2.0 asks a redistributor for "a copy of this License", and Needler
        // ships as an APK with no store page beside it, so the copy has to be in the binary. The other
        // licences print their canonical address instead - see LicenceBlock in LicencesScreen.
        assertTrue(Licence.Apache2.terms != null)
        val withText: List<Licence> = Licence.entries.filter { it.terms != null }
        assertEquals(listOf(Licence.Apache2), withText)
    }

    // ---- what the screen has to be able to draw --------------------------------

    @Test
    fun `every entry has a name, a licence with an identifier and a canonical address`() {
        LicenceCatalogue.everything.forEach { component ->
            assertTrue("an entry with no name: " + component, component.name.isNotBlank())
            assertTrue(component.name, component.licence.spdxId.isNotBlank())
            assertTrue(component.name, component.licence.title.isNotBlank())
            assertTrue(component.name, component.licence.url.startsWith("https://"))
        }
    }

    @Test
    fun `the one dependency that is not open source says so`() {
        val proprietary: List<LicencedComponent> = LicenceCatalogue.everything
            .filter { it.licence == Licence.AndroidSdkTerms }

        val gms: LicencedComponent = proprietary.single()
        assertEquals(listOf("com.google.android.gms:play-services-wearable"), gms.coordinates)
        assertEquals(DependencyScope.Bundled, gms.scope)
        // The honesty is the point of listing it at all. A generated list would have printed whatever
        // the POM claimed, with nobody having looked.
        assertTrue(gms.note.orEmpty(), gms.note.orEmpty().contains("not open source"))
    }

    @Test
    fun `every scope the screen groups by has something in it`() {
        val grouped: List<DependencyScope> = LicenceCatalogue.byScope.map { it.first }

        assertEquals(DependencyScope.entries.toList(), grouped)
        LicenceCatalogue.byScope.forEach { (scope, inScope) ->
            assertTrue(scope.name, inScope.isNotEmpty())
            assertTrue(scope.name, scope.label.isNotBlank())
            assertTrue(scope.name, scope.explanation.isNotBlank())
        }
    }

    @Test
    fun `the bundled group is the one the requirement is about, and it is the largest`() {
        val bundled: List<LicencedComponent> =
            LicenceCatalogue.components.filter { it.scope == DependencyScope.Bundled }

        assertTrue(bundled.isNotEmpty())
        // Not an arbitrary assertion: if "bundled" ever stopped being the biggest group, either a
        // dependency has been mis-scoped or the app has stopped shipping most of what it depends on.
        LicenceCatalogue.byScope
            .filter { it.first != DependencyScope.Bundled }
            .forEach { (scope, inScope) ->
                assertTrue(scope.name, inScope.size <= bundled.size)
            }
    }

    // ---- the attribution line REQUIREMENTS.md names by hand ---------------------

    @Test
    fun `the attribution line credits exactly the five services the requirement names`() {
        // REQUIREMENTS.md "Legal and attribution": "The attribution line credits Dropped Needle,
        // slskd, MusicBrainz, ListenBrainz and Cover Art Archive."
        assertEquals(
            listOf("Dropped Needle", "slskd", "MusicBrainz", "ListenBrainz", "Cover Art Archive"),
            NeedlerLegal.credits,
        )
    }

    @Test
    fun `every credited service has a line saying what it is`() {
        NeedlerLegal.credits.forEach { name ->
            val description: String? = NeedlerLegal.creditDescriptions[name]
            assertTrue("no description for " + name, !description.isNullOrBlank())
        }
        // And nothing is described that is not credited, which would be a description nothing draws.
        assertEquals(NeedlerLegal.credits.toSet(), NeedlerLegal.creditDescriptions.keys)
    }

    @Test
    fun `the disclaimer still says the four things the requirement lists`() {
        // REQUIREMENTS.md: the disclaimer "is a requirement rather than decoration. It states that
        // Needler is independent and unaffiliated, that it hosts and transmits no music, that the user
        // is responsible for what they acquire, and that the app is provided without warranty."
        val all: String = NeedlerLegal.disclaimer.joinToString(" ")

        assertEquals(4, NeedlerLegal.disclaimer.size)
        assertTrue("independence", all.contains("independent client"))
        assertTrue("unaffiliated", all.contains("not affiliated with"))
        assertTrue("hosts nothing", all.contains("does not host"))
        assertTrue("user responsibility", all.contains("solely responsible"))
        assertTrue("no warranty", all.contains("without warranty of any kind"))
    }

    // ---- reading the project off disk ------------------------------------------

    /**
     * Every `group:name` in the version catalogue's `[libraries]` table.
     *
     * Parsed with a regex rather than a TOML library because adding a TOML parser to the unit-test
     * classpath to read four hundred lines of well-known formatting would be a dependency added in
     * order to check dependencies. The parser is deliberately strict — one alias per line, ending in
     * `}` — and `the parser found something` is the test that stops a strict parser from failing open.
     */
    private fun coordinatesInVersionCatalogue(): Set<String> {
        val text: String = versionCatalogueFile().readText()
        val afterHeading: String = text.substringAfter(LIBRARIES_TABLE, "")
        assertTrue(
            "gradle/libs.versions.toml has no " + LIBRARIES_TABLE + " table",
            afterHeading.isNotEmpty(),
        )
        // Stops at the next table heading, which is `[plugins]`: those are plugin ids rather than
        // coordinates and have no licence of their own to record.
        val table: String = afterHeading.substringBefore("\n[", afterHeading)

        return table.lineSequence()
            .map { line -> line.substringBefore('#').trim() }
            .filter { line -> ENTRY.matches(line) }
            .mapNotNull { line ->
                val group: String? = GROUP.find(line)?.groupValues?.get(1)
                val name: String? = ARTIFACT.find(line)?.groupValues?.get(1)
                if (group == null || name == null) null else group + ":" + name
            }
            .toSet()
    }

    private fun versionCatalogueFile(): File = File(repositoryRoot(), VERSION_CATALOGUE)

    /**
     * The repository root, found by walking up from wherever the test JVM was started.
     *
     * Gradle starts a `Test` task in the module directory, so this is normally one step up from
     * `app/`. It walks rather than assuming, because that is a Gradle default rather than a promise,
     * and because the same test should work if it is ever run from the root or from an IDE.
     *
     * Not finding it throws rather than skipping. A freshness check that quietly passes when it cannot
     * find the file it checks against is the failure mode this whole file exists to prevent.
     */
    private fun repositoryRoot(): File {
        val start = File(System.getProperty("user.dir").orEmpty().ifEmpty { "." }).absoluteFile
        var directory: File? = start
        var climbed = 0
        while (directory != null && climbed <= MAX_CLIMB) {
            if (File(directory, VERSION_CATALOGUE).isFile) return directory
            directory = directory.parentFile
            climbed++
        }
        throw AssertionError(
            "could not find " + VERSION_CATALOGUE + " in " + start + " or in any of its " +
                MAX_CLIMB + " parent directories, so the licence freshness check has nothing to " +
                "check against. It must not pass in that state.",
        )
    }

    private companion object {
        const val VERSION_CATALOGUE: String = "gradle/libs.versions.toml"
        const val LIBRARIES_TABLE: String = "[libraries]"
        const val END_OF_TERMS: String = "   END OF TERMS AND CONDITIONS"

        /** `app/` to the root is one step; the rest is slack for an IDE or a CI layout. */
        const val MAX_CLIMB: Int = 8

        /**
         * A floor, not a count.
         *
         * The catalogue holds seventy-odd libraries and will grow. Asserting the exact number here
         * would make this test fail every time a dependency is added, which is the job of the two
         * freshness tests above and would drown their message in noise.
         */
        const val MINIMUM_PLAUSIBLE_DEPENDENCIES: Int = 50

        val ENTRY: Regex = Regex("^[A-Za-z0-9_.\\-]+\\s*=\\s*\\{.*\\}$")
        val GROUP: Regex = Regex("group\\s*=\\s*\"([^\"]+)\"")
        val ARTIFACT: Regex = Regex("name\\s*=\\s*\"([^\"]+)\"")
    }
}
