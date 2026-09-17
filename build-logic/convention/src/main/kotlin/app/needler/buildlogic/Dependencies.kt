package app.needler.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.AbstractTestTask

/**
 * Test dependencies every Kotlin module in this project gets.
 *
 * JUnit 4 is the project-wide framework (see the rationale in
 * gradle/libs.versions.toml). Turbine and coroutines-test are here rather than
 * repeated twelve times because everything in Needler is a `Flow` or a
 * `suspend fun`; MockK is here because every module tests against the
 * repository interfaces declared in `:core:domain`.
 */
internal fun Project.addSharedUnitTestDependencies() {
    dependencies.apply {
        addProvider("testImplementation", libs.library("junit4"))
        addProvider("testImplementation", libs.library("kotlinx.coroutines.test"))
        addProvider("testImplementation", libs.library("turbine"))
        addProvider("testImplementation", libs.library("mockk.unit"))
    }

    // Gradle 9 fails a test task that discovers nothing, which breaks every module
    // that has no tests yet (the feature modules, :widget, :wear). A module with no
    // tests is a gap to fill, not a build failure, so the check is off project-wide.
    // Modules that DO have tests still fail normally when a test fails.
    tasks.withType(AbstractTestTask::class.java).configureEach {
        failOnNoDiscoveredTests.set(false)
    }
}

/**
 * The Compose artifacts shared by every Compose module: runtime, ui, foundation
 * and tooling.
 *
 * Deliberately excluded:
 *  - `material3`, because `:wear` uses `androidx.wear.compose:compose-material3`
 *    instead and must not pull the phone/tablet Material 3 in as well.
 *  - the adaptive libraries and Coil, which `:core:design` owns and re-exposes
 *    with `api(...)`.
 *
 * All versions come from the Compose BOM, which is added to both the main and
 * the androidTest classpaths.
 */
internal fun Project.addSharedComposeDependencies() {
    dependencies.apply {
        // DependencyHandler.platform(Provider<MinimalExternalModuleDependency>)
        // and addProvider(String, Provider<T>) are both members of the Gradle
        // API, so no org.gradle.kotlin.dsl import is involved here.
        addProvider("implementation", platform(libs.library("compose.bom")))
        addProvider("androidTestImplementation", platform(libs.library("compose.bom")))

        addProvider("implementation", libs.library("compose.runtime"))
        addProvider("implementation", libs.library("compose.ui"))
        addProvider("implementation", libs.library("compose.ui.graphics"))
        addProvider("implementation", libs.library("compose.foundation"))
        addProvider("implementation", libs.library("compose.animation"))
        addProvider("implementation", libs.library("compose.ui.tooling.preview"))

        addProvider("debugImplementation", libs.library("compose.ui.tooling"))
        addProvider("debugImplementation", libs.library("compose.ui.test.manifest"))
        addProvider("androidTestImplementation", libs.library("compose.ui.test.junit4"))
    }
}

/** androidx.test wiring for modules that carry instrumented tests. */
internal fun Project.addSharedAndroidTestDependencies() {
    dependencies.apply {
        addProvider("androidTestImplementation", libs.library("androidx.test.ext.junit"))
        addProvider("androidTestImplementation", libs.library("androidx.test.espresso.core"))
    }
}
