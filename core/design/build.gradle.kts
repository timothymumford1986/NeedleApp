// :core:design — palette, type, shape, motion, shared components.
// REQUIREMENTS.md "Architecture > Modules" and "Design system".
//
// The design system module every Compose surface builds on. It owns:
//   * the single dark palette (canvas #0d120a through hairline)
//   * Space Grotesk 500/700 and Hanken Grotesk 400-700, which is why
//     ui-text-google-fonts is here
//   * shape (10/14/16-18/999 px) and the 54-56 px control heights
//   * motion, including the reduced-motion check REQUIREMENTS.md requires
//     (ANIMATOR_DURATION_SCALE == 0 before the splash or any looping animation)
//   * the adaptive scaffolding for one navigation model at two widths
//
// It has no dependency on :core:domain: components take plain parameters, so the
// design system stays previewable and testable without a data layer.
//
// Most dependencies are `api` on purpose — this module is the Compose surface
// area every feature codes against, and re-declaring material3 or Coil in each
// of the four feature modules would be exactly the repetition the module exists
// to prevent.

plugins {
    id("needler.android.library.compose")
}

android {
    namespace = "app.needler.core.design"
}

dependencies {
    // Material 3 plus the adaptive libraries. REQUIREMENTS.md "Tablet layout":
    // one navigation model driven by WindowSizeClass — nav rail plus permanent
    // sidebar on a tablet, bottom bar plus mini-player on a phone.
    // Versions come from the Compose BOM added by the convention plugin.
    api(libs.compose.material3)
    api(libs.compose.material3.windowSizeClass)
    api(libs.compose.material3.adaptiveNavigationSuite)
    api(libs.compose.adaptive)
    api(libs.compose.adaptive.layout)
    api(libs.compose.adaptive.navigation)

    // Downloadable Google Fonts for the two families in the design pack.
    api(libs.compose.ui.text.googleFonts)

    // Artwork. REQUIREMENTS.md "Libraries": Coil 3, for its disk cache with a
    // size budget ("Three tiers" gives artwork its own small LRU budget).
    // coil-network-okhttp keeps artwork on the same OkHttp client, and therefore
    // the same certificate-pinning policy, as everything else.
    api(libs.coil.compose)
    implementation(libs.coil.core)
    implementation(libs.coil.network.okhttp)

    implementation(libs.androidx.core.ktx)
}
