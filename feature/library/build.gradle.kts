// :feature:library — library, artist and album screens.
// REQUIREMENTS.md "Architecture > Modules", "Browse and search > Library browse".
//
// Layering rule for every :feature:* module:
//   * depends on :core:domain (entities, repository interfaces, use cases)
//   * depends on :core:design (palette, type, components)
//   * does NOT depend on :core:data or :core:network
//
// A feature asks for a repository *interface* and Hilt in :app injects the
// implementation. That is what keeps "no screen knows whether a fact came from
// Subsonic, from /api/v1, or from the local mirror" true at compile time rather
// than by convention.

// ---------------------------------------------------------------------------
// Screenshots
// ---------------------------------------------------------------------------
// Every screen and state in this module renders to a PNG on the JVM — no
// emulator, no device. Regenerate the whole set with:
//
//     ./gradlew :feature:library:recordScreenshots
//
// The images land in `screenshots/` at the repository root, beside :app's.
// `-Pneedler.screenshots.verify` compares against the committed images and
// fails on a difference instead of overwriting them. How the rendering is
// wired is in build-logic/.../ScreenshotConventionPlugin.kt.
// ---------------------------------------------------------------------------

plugins {
    id("needler.android.library.compose")
    id("needler.hilt")
    id("needler.screenshots")
}

android {
    namespace = "app.needler.feature.library"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design"))

    // Material 3 and the window size classes arrive transitively through
    // :core:design's `api`, but this module compiles against them directly —
    // the sort control is a Material dropdown menu and every screen branches on
    // WindowWidthSizeClass — and a module should declare what it compiles
    // against.
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowSizeClass)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
