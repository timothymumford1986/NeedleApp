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

plugins {
    id("needler.android.library.compose")
    id("needler.hilt")
}

android {
    namespace = "app.needler.feature.library"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
