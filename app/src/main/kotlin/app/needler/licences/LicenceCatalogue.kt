package app.needler.licences

/**
 * A licence something in this app is offered under.
 *
 * @property spdxId the SPDX identifier, because it is the string a compliance tool and a lawyer both
 *   recognise. `LicenseRef-` prefixes a licence with no SPDX identifier, which is the convention
 *   SPDX itself defines for exactly that case and is the honest way to name Google's SDK terms.
 * @property title the human name, for the heading above a group of dependencies.
 * @property url where the canonical text lives. Rendered as plain text rather than as a link: the
 *   design pack has no link treatment beyond the accent colour, and a tappable row that opens a
 *   browser from a screen whose whole point is that it works offline is worse than a URL a reader
 *   can type.
 * @property terms the full text, when this app carries a copy of it. Non-null only for Apache-2.0 —
 *   see [APACHE_2_0_TERMS] for why that one and not the others.
 */
enum class Licence(
    val spdxId: String,
    val title: String,
    val url: String,
    val terms: String? = null,
) {
    Apache2(
        spdxId = "Apache-2.0",
        title = "Apache License 2.0",
        url = "https://www.apache.org/licenses/LICENSE-2.0",
        terms = APACHE_2_0_TERMS,
    ),

    Mit(
        spdxId = "MIT",
        title = "MIT License",
        url = "https://opensource.org/license/mit",
    ),

    Epl1(
        spdxId = "EPL-1.0",
        title = "Eclipse Public License 1.0",
        url = "https://www.eclipse.org/legal/epl-v10.html",
    ),

    Ofl1_1(
        spdxId = "OFL-1.1",
        title = "SIL Open Font License 1.1",
        url = "https://openfontlicense.org",
    ),

    /**
     * Google's own terms for the Play services client libraries. Not an open-source licence, and
     * saying so is the point of listing it: it is the one thing in this app whose terms the reader
     * cannot audit and cannot fork.
     */
    AndroidSdkTerms(
        spdxId = "LicenseRef-Android-SDK",
        title = "Android Software Development Kit License Agreement",
        url = "https://developer.android.com/studio/terms",
    ),
}

/**
 * Where a dependency ends up, which decides whether REQUIREMENTS.md's word "bundled" covers it.
 *
 * REQUIREMENTS.md "Legal and attribution" asks for "every bundled dependency and its licence", and
 * that word is doing real work: two thirds of the entries in `gradle/libs.versions.toml` never reach
 * a user's device. A Gradle plugin, an annotation processor and JUnit are not distributed, so no
 * redistribution condition of any licence applies to them. Listing them anyway, labelled, is the
 * compromise this enum exists for — a reader auditing the project can see the whole dependency set,
 * and a reader asking "what is in the thing I installed" can see which part of it that is.
 *
 * The alternative was listing only [Bundled] entries, which would have been defensible and was
 * rejected for one practical reason: the freshness check in `LicenceCatalogueTest` works by comparing
 * this catalogue against every coordinate in the version catalogue, and a list that deliberately
 * omits two thirds of them cannot be compared against anything.
 *
 * @property label the heading the screen groups under.
 * @property explanation one line under the heading, because "Build tooling" means nothing to the
 *   person this screen is for.
 */
enum class DependencyScope(val label: String, val explanation: String) {

    /** In the APK, running on the user's device. The set the requirement is actually about. */
    Bundled(
        label = "In the app",
        explanation = "Shipped inside Needler and running on your device.",
    ),

    /**
     * Fetched at run time rather than shipped. The two typefaces, which arrive through Google Play
     * services' downloadable-fonts provider and fall back to the system face when it is absent.
     */
    Downloaded(
        label = "Fetched when needed",
        explanation = "Not shipped in the app; downloaded by the system the first time it is used.",
    ),

    /** In a debug build only, stripped from every release. */
    DebugBuildOnly(
        label = "Debug builds only",
        explanation = "Developer tooling. Not present in a release build of Needler.",
    ),

    /** Runs on the machine that compiles Needler and reaches no device at all. */
    BuildOnly(
        label = "Build tooling",
        explanation = "Runs on the computer that compiles Needler. Never on your device.",
    ),

    /** Runs in the test suite, on a JVM or an emulator. */
    TestOnly(
        label = "Tests only",
        explanation = "Used to test Needler. Never on your device.",
    ),
}

/**
 * One project, its licence, and every Maven coordinate in `gradle/libs.versions.toml` it accounts
 * for.
 *
 * Coordinates are grouped by project rather than listed one per row because six `androidx.media3`
 * artifacts are one library with one licence and one copyright holder, and a screen that lists them
 * separately is a screen nobody scrolls to the end of. The coordinates are still all present,
 * because they are what the freshness check compares against.
 *
 * @property name what to call it on screen.
 * @property licence the licence declared in the artifact's published POM.
 * @property scope where it ends up. See [DependencyScope].
 * @property coordinates every `group:name` this entry covers. Empty for the handful of things that
 *   are genuinely in the app but not in the version catalogue — the Kotlin standard library, which
 *   the compiler adds, and the two typefaces, which are not dependencies at all.
 * @property note anything a reader would otherwise have to guess at.
 */
data class LicencedComponent(
    val name: String,
    val licence: Licence,
    val scope: DependencyScope,
    val coordinates: List<String> = emptyList(),
    val note: String? = null,
)

/**
 * Every dependency Needler has, what it is licensed under, and Needler's own licence beside them.
 *
 * REQUIREMENTS.md "Legal and attribution": "A Licences screen must list every bundled dependency and
 * its licence", and "**Needler is licensed Apache-2.0** … The Licences screen must therefore show
 * Apache-2.0 for Needler alongside each bundled dependency." This object is that list; [needler] is
 * that first entry.
 *
 * ## The decision: maintained as data, with a test that fails when it goes stale
 *
 * The obvious alternative is generating the list at build time. Something has to read
 * `gradle/libs.versions.toml`, resolve each coordinate's POM and pull the `<licenses>` element out of
 * it, and Gradle is where that work naturally lives — it is what `com.google.android.gms.oss-licenses`
 * and `com.jaredsburrows.license` do. It was rejected, and the reasoning is worth recording because
 * it is not "that would be harder".
 *
 *  1. **It cannot run here.** Generation means a network resolution at build time against every
 *     dependency's POM. REQUIREMENTS.md "Build and toolchain hazards" already records this project's
 *     fragility around exact toolchain versions, and `:app` is built with AGP 9 and built-in Kotlin,
 *     which neither licence plugin has been tested against. A build step that fails offline, in a
 *     tunnel, on the one day someone needs to cut a release, is a worse outcome than a list.
 *  2. **A POM's `<licenses>` element is not authoritative.** It is metadata the publisher fills in by
 *     hand, it is frequently absent, and it is frequently wrong. A generated list carries whatever
 *     was published, silently, with no human ever having looked — which is how a proprietary
 *     dependency ends up in a screen labelled "open source". `com.google.android.gms:play-services-wearable`
 *     in this very list is that case: it is not open source, and the entry says so because a person
 *     wrote it.
 *  3. **The failure mode of a list is fixable; the failure mode of a generator is not.** A stale list
 *     is a wrong list, and the task that asked for this screen put it plainly: a list that silently
 *     goes stale when a dependency is added is worse than no list. So the list is not allowed to go
 *     stale silently.
 *
 * ## How staleness is made detectable
 *
 * `LicenceCatalogueTest` reads `gradle/libs.versions.toml` from disk — the real file, found by
 * walking up from the test's working directory — parses every entry in its `[libraries]` table into a
 * `group:name` coordinate, and asserts that the set is **exactly** [declaredCoordinates]. Not a
 * subset in either direction:
 *
 *  * add a dependency to the version catalogue and forget this file, and the test names the
 *    coordinate that has no licence recorded for it;
 *  * remove a dependency and forget this file, and the test names the entry that is now fiction.
 *
 * It runs in `:app:testDebugUnitTest`, which is the task CI runs, so the check happens on the commit
 * that adds the dependency rather than at the next release. A second assertion checks that
 * [Licence.Apache2]'s text is still byte-identical to the repository's own `LICENSE` file, so the
 * copy of the licence this app distributes cannot drift from the copy it is licensed under either.
 *
 * What the test deliberately does **not** check is that each licence named here is the licence that
 * artifact is actually published under. Nothing on a JVM unit-test classpath can know that without
 * resolving POMs, which is the generation approach and its problems. What the test guarantees is that
 * a human was forced to write a line for every dependency; what that line says is on them.
 */
object LicenceCatalogue {

    /**
     * Needler itself, first on the screen.
     *
     * REQUIREMENTS.md: "DroppedNeedle is AGPL-3.0, but Needler talks to it only over HTTP and links
     * none of its code, so no copyleft obligation attaches to Needler itself. **Needler is licensed
     * Apache-2.0** — MIT's permissions plus an explicit patent grant." The note carries the first
     * half of that, because "is this app's licence infected by the server's?" is a question a reader
     * of this screen may well have, and it is answered here once rather than left to be asked.
     */
    val needler: LicencedComponent = LicencedComponent(
        name = "Needler",
        licence = Licence.Apache2,
        scope = DependencyScope.Bundled,
        note = "This app. Dropped Needle, the server, is AGPL-3.0, but Needler only talks to it " +
            "over HTTP and links none of its code, so none of that licence reaches here.",
    )

    /**
     * Everything else, in the order the screen draws it.
     *
     * Ordered by [DependencyScope] first — what is in the app, then what is not — and within a scope
     * roughly by how likely a reader is to have heard of it.
     */
    val components: List<LicencedComponent> = listOf(
        // ---- in the app ----------------------------------------------------
        LicencedComponent(
            name = "Kotlin standard library",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            note = "Added by the Kotlin compiler rather than declared in the version catalogue, " +
                "which is why it has no coordinate below.",
        ),
        LicencedComponent(
            name = "Jetpack Compose",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf(
                "androidx.compose.runtime:runtime",
                "androidx.compose.ui:ui",
                "androidx.compose.ui:ui-graphics",
                "androidx.compose.ui:ui-tooling-preview",
                "androidx.compose.ui:ui-text-google-fonts",
                "androidx.compose.foundation:foundation",
                "androidx.compose.animation:animation",
            ),
            note = "Every screen in Needler is drawn with it.",
        ),
        LicencedComponent(
            name = "Material 3 for Compose",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf(
                "androidx.compose.material3:material3",
                "androidx.compose.material3:material3-window-size-class",
                "androidx.compose.material3:material3-adaptive-navigation-suite",
                "androidx.compose.material3.adaptive:adaptive",
                "androidx.compose.material3.adaptive:adaptive-layout",
                "androidx.compose.material3.adaptive:adaptive-navigation",
            ),
        ),
        LicencedComponent(
            name = "AndroidX core, activity, lifecycle and navigation",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf(
                "androidx.core:core-ktx",
                "androidx.core:core-splashscreen",
                "androidx.activity:activity-compose",
                "androidx.lifecycle:lifecycle-runtime-compose",
                "androidx.lifecycle:lifecycle-viewmodel-compose",
                "androidx.lifecycle:lifecycle-service",
                "androidx.navigation:navigation-compose",
            ),
        ),
        LicencedComponent(
            name = "Media3",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf(
                "androidx.media3:media3-common",
                "androidx.media3:media3-exoplayer",
                "androidx.media3:media3-session",
                "androidx.media3:media3-datasource-okhttp",
                "androidx.media3:media3-cast",
                "androidx.media3:media3-database",
            ),
            note = "Plays the audio and owns the media session the lock screen and the car talk to.",
        ),
        LicencedComponent(
            name = "Room",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf("androidx.room:room-runtime", "androidx.room:room-ktx"),
            note = "The local mirror of your library.",
        ),
        LicencedComponent(
            name = "WorkManager",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf("androidx.work:work-runtime-ktx"),
        ),
        LicencedComponent(
            name = "DataStore",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf("androidx.datastore:datastore-preferences"),
        ),
        LicencedComponent(
            name = "AndroidX Security Crypto",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf("androidx.security:security-crypto"),
            note = "Keeps your server credentials in Keystore-backed storage.",
        ),
        LicencedComponent(
            name = "Glance",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf("androidx.glance:glance-appwidget", "androidx.glance:glance-material3"),
            note = "The home-screen widgets.",
        ),
        LicencedComponent(
            name = "Wear Compose",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf(
                "androidx.wear.compose:compose-foundation",
                "androidx.wear.compose:compose-material3",
                "androidx.wear.compose:compose-navigation",
                "androidx.wear:wear-tooling-preview",
            ),
            note = "The watch app.",
        ),
        LicencedComponent(
            name = "Hilt",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf(
                "com.google.dagger:hilt-android",
                "androidx.hilt:hilt-work",
                "androidx.hilt:hilt-navigation-compose",
            ),
        ),
        LicencedComponent(
            name = "OkHttp",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf("com.squareup.okhttp3:okhttp"),
            note = "Every request to your server, including the certificate pinning.",
        ),
        LicencedComponent(
            name = "Coil",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf(
                "io.coil-kt.coil3:coil",
                "io.coil-kt.coil3:coil-compose",
                "io.coil-kt.coil3:coil-network-okhttp",
            ),
            note = "Loads and caches album artwork.",
        ),
        LicencedComponent(
            name = "kotlinx coroutines, serialization and datetime",
            licence = Licence.Apache2,
            scope = DependencyScope.Bundled,
            coordinates = listOf(
                "org.jetbrains.kotlinx:kotlinx-coroutines-core",
                "org.jetbrains.kotlinx:kotlinx-coroutines-android",
                "org.jetbrains.kotlinx:kotlinx-serialization-json",
                "org.jetbrains.kotlinx:kotlinx-datetime",
            ),
        ),
        LicencedComponent(
            name = "Google Play services Wearable",
            licence = Licence.AndroidSdkTerms,
            scope = DependencyScope.Bundled,
            coordinates = listOf("com.google.android.gms:play-services-wearable"),
            note = "The only thing in Needler that is not open source. It is the data-layer bridge " +
                "to the watch, and there is no alternative implementation of it: the transport " +
                "belongs to Google Play services.",
        ),

        // ---- fetched when needed --------------------------------------------
        LicencedComponent(
            name = "Space Grotesk",
            licence = Licence.Ofl1_1,
            scope = DependencyScope.Downloaded,
            note = "The wordmark, screen titles and numerals. Fetched through Google Fonts; the " +
                "system typeface stands in when it is unavailable.",
        ),
        LicencedComponent(
            name = "Hanken Grotesk",
            licence = Licence.Ofl1_1,
            scope = DependencyScope.Downloaded,
            note = "Everything else you read in Needler.",
        ),

        // ---- debug builds only ----------------------------------------------
        LicencedComponent(
            name = "Compose UI tooling",
            licence = Licence.Apache2,
            scope = DependencyScope.DebugBuildOnly,
            coordinates = listOf(
                "androidx.compose.ui:ui-tooling",
                "androidx.compose.ui:ui-test-manifest",
            ),
        ),

        // ---- build tooling ---------------------------------------------------
        LicencedComponent(
            name = "Android Gradle Plugin",
            licence = Licence.Apache2,
            scope = DependencyScope.BuildOnly,
            coordinates = listOf("com.android.tools.build:gradle"),
        ),
        LicencedComponent(
            name = "Kotlin and Compose compiler plugins",
            licence = Licence.Apache2,
            scope = DependencyScope.BuildOnly,
            coordinates = listOf(
                "org.jetbrains.kotlin:kotlin-gradle-plugin",
                "org.jetbrains.kotlin:compose-compiler-gradle-plugin",
            ),
        ),
        LicencedComponent(
            name = "Kotlin Symbol Processing",
            licence = Licence.Apache2,
            scope = DependencyScope.BuildOnly,
            coordinates = listOf("com.google.devtools.ksp:symbol-processing-gradle-plugin"),
        ),
        LicencedComponent(
            name = "Hilt and Room code generators",
            licence = Licence.Apache2,
            scope = DependencyScope.BuildOnly,
            coordinates = listOf(
                "com.google.dagger:hilt-android-gradle-plugin",
                "com.google.dagger:hilt-android-compiler",
                "androidx.hilt:hilt-compiler",
                "androidx.room:room-compiler",
            ),
        ),
        LicencedComponent(
            name = "Compose Bill of Materials",
            licence = Licence.Apache2,
            scope = DependencyScope.BuildOnly,
            coordinates = listOf("androidx.compose:compose-bom"),
            note = "A list of version numbers. No code at all.",
        ),

        // ---- tests only ------------------------------------------------------
        LicencedComponent(
            name = "JUnit 4",
            licence = Licence.Epl1,
            scope = DependencyScope.TestOnly,
            coordinates = listOf("junit:junit"),
        ),
        LicencedComponent(
            name = "Robolectric",
            licence = Licence.Mit,
            scope = DependencyScope.TestOnly,
            coordinates = listOf("org.robolectric:robolectric"),
        ),
        LicencedComponent(
            name = "Roborazzi",
            licence = Licence.Apache2,
            scope = DependencyScope.TestOnly,
            coordinates = listOf(
                "io.github.takahirom.roborazzi:roborazzi",
                "io.github.takahirom.roborazzi:roborazzi-compose",
            ),
        ),
        LicencedComponent(
            name = "MockK",
            licence = Licence.Apache2,
            scope = DependencyScope.TestOnly,
            coordinates = listOf("io.mockk:mockk", "io.mockk:mockk-android"),
        ),
        LicencedComponent(
            name = "Turbine",
            licence = Licence.Apache2,
            scope = DependencyScope.TestOnly,
            coordinates = listOf("app.cash.turbine:turbine"),
        ),
        LicencedComponent(
            name = "AndroidX Test, Espresso and the testing artifacts",
            licence = Licence.Apache2,
            scope = DependencyScope.TestOnly,
            coordinates = listOf(
                "androidx.test:core",
                "androidx.test.ext:junit",
                "androidx.test.espresso:espresso-core",
                "androidx.compose.ui:ui-test-junit4",
                "androidx.room:room-testing",
                "androidx.work:work-testing",
                "com.google.dagger:hilt-android-testing",
                "org.jetbrains.kotlinx:kotlinx-coroutines-test",
            ),
        ),
        LicencedComponent(
            name = "MockWebServer",
            licence = Licence.Apache2,
            scope = DependencyScope.TestOnly,
            coordinates = listOf("com.squareup.okhttp3:mockwebserver3-junit4"),
        ),
    )

    /** [needler] and [components], which is what the screen walks. */
    val everything: List<LicencedComponent> = listOf(needler) + components

    /** The set the freshness check compares against `gradle/libs.versions.toml`. */
    val declaredCoordinates: Set<String> = components.flatMap { it.coordinates }.toSet()

    /** [components] in scope order, so the screen can draw one heading per group. */
    val byScope: List<Pair<DependencyScope, List<LicencedComponent>>> =
        DependencyScope.entries
            .map { scope -> scope to components.filter { it.scope == scope } }
            .filter { (_, inScope) -> inScope.isNotEmpty() }

    /**
     * Every licence anything here is under, in the order they first appear, so the screen can print
     * one heading per licence and one copy of the text it has.
     */
    val licencesInUse: List<Licence> = everything.map { it.licence }.distinct()
}
