// :feature:pulls — the request sheet and the download-queue screen.
// REQUIREMENTS.md "Architecture > Modules", "Request and acquire".
//
// Renders the Active/Completed/Failed buckets, per-task progress, and the
// server's returned status rather than a status inferred from a cached role.
// The 2 s foreground polling and the activity-summary revision check live in
// :core:data; this module consumes the resulting flow.
//
// Layering: :core:domain + :core:design only. See :feature:library for why.

plugins {
    id("needler.android.library.compose")
    id("needler.hilt")
}

android {
    namespace = "app.needler.feature.pulls"
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:design"))

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
}
