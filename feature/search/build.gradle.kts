// :feature:search — the one unified search field.
// REQUIREMENTS.md "Architecture > Modules", "Browse and search > Search behaviour".
//
// Local FTS results on the first keystroke, catalogue results after a 300 ms
// debounce, merged on release-group MBID. Both halves of that merge happen in
// :core:data behind SearchRepository; this module only renders one already
// unified list and the quiet `service_status` note.
//
// Layering: :core:domain + :core:design only. See :feature:library for why.

plugins {
    id("needler.android.library.compose")
    id("needler.hilt")
}

android {
    namespace = "app.needler.feature.search"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
