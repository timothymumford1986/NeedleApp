// :feature:pulls — the request sheet and the download-queue screen.
// REQUIREMENTS.md "Architecture > Modules", "Request and acquire".
//
// Renders the Active/Completed/Failed buckets, per-task progress, and the
// server's returned status rather than a status inferred from a cached role.
// The 2 s foreground polling and the activity-summary revision check live in
// :core:data; this module consumes the resulting flow.
//
// Layering: :core:domain + :core:design only. See :feature:library for why.

// ---------------------------------------------------------------------------
// Screenshots
// ---------------------------------------------------------------------------
// Every state of the Pulls screen renders to a PNG on the JVM — no emulator, no
// device. Regenerate the whole set with:
//
//     ./gradlew :feature:pulls:recordScreenshots
//
// The images land in `screenshots/` at the repository root, beside :app's,
// :feature:library's and :feature:search's. `-Pneedler.screenshots.verify`
// compares against the committed images and fails on a difference instead of
// overwriting them, which is what `build.yml` runs.
//
// **No baseline for this module exists yet.** It was written on a machine with
// no Android SDK and no JDK 21, so these tests have never been executed and no
// PNG has ever been produced. Until a baseline is recorded, CI's verify run
// fails here with a missing golden — that is what the manual
// `record screenshots` workflow (.github/workflows/record-screenshots.yml) is
// for: dispatch it with `:feature:pulls:test`, look at what it uploads, and
// commit the images. How the rendering is wired is in
// build-logic/.../ScreenshotConventionPlugin.kt.
// ---------------------------------------------------------------------------

plugins {
    id("needler.android.library.compose")
    id("needler.hilt")
    id("needler.screenshots")
}

android {
    namespace = "app.needler.feature.pulls"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design"))

    // Material 3 and the window size classes arrive transitively through
    // :core:design's `api`, but this module compiles against them directly —
    // the rows and notices are Material `Text`, and the screen branches on
    // WindowWidthSizeClass — and a module should declare what it compiles
    // against.
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowSizeClass)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
