// :feature:search — the one unified search field.
// REQUIREMENTS.md "Architecture > Modules", "Browse and search > Search behaviour".
//
// Local FTS results on the first keystroke, catalogue results after a 300 ms
// debounce, merged on release-group MBID. Both halves of that merge happen in
// :core:data behind SearchRepository; this module only renders one already
// unified list and the quiet `service_status` note.
//
// Layering: :core:domain + :core:design only. See :feature:library for why.

// ---------------------------------------------------------------------------
// Screenshots
// ---------------------------------------------------------------------------
// Every state this screen has renders to a PNG on the JVM — no emulator, no
// device. Regenerate the whole set with:
//
//     ./gradlew :feature:search:recordScreenshots
//
// The images land in `screenshots/` at the repository root, beside :app's and
// :feature:library's. `-Pneedler.screenshots.verify` compares against the
// committed images and fails on a difference instead of overwriting them, which
// is what CI runs — so the baselines have to be recorded once, with the flag
// off, on a machine that has the Android SDK and JDK 21.
// ---------------------------------------------------------------------------

plugins {
    id("needler.android.library.compose")
    id("needler.hilt")
    id("needler.screenshots")
}

android {
    namespace = "app.needler.feature.search"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design"))

    // Material 3 and the window size classes arrive transitively through
    // :core:design's `api`, but this module compiles against them directly —
    // the rows are Material text and the screen branches on WindowWidthSizeClass
    // — and a module should declare what it compiles against. Same reasoning,
    // same two lines, as :feature:library.
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowSizeClass)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
