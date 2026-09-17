// :core:domain — entities, repository interfaces, use cases.
// REQUIREMENTS.md "Architecture > Modules".
//
// THIS MODULE IS A PURE KOTLIN/JVM LIBRARY. That is a hard architectural rule,
// not a preference:
//
//   * no Android dependency of any kind (no `android.*`, no `androidx.*`)
//   * no Compose
//   * no Room — persistence is :core:data's business; domain declares interfaces
//   * no OkHttp / no DTOs — the wire format is :core:network's business
//
// Because this module applies the Kotlin JVM plugin rather than an Android one,
// the compiler enforces all of the above: none of those classes are on the
// compile classpath, so a violation is a compile error rather than a review
// comment. Keep it that way — it is what lets the ViewModels, the Media3
// service, the widgets and Wear all share one model.
//
// The only two dependencies are the vocabulary the interfaces are written in:
// coroutines for `Flow`/`suspend`, and kotlinx-datetime for the timestamps that
// appear in entities (added-at, starred-at, last-played, scrobble times).

plugins {
    id("needler.jvm.library")
}

dependencies {
    // `api`, not `implementation`: both appear in this module's public
    // signatures, so every consumer needs them on its compile classpath.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.datetime)
}
