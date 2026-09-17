// :core:network — OkHttp, the Subsonic and /api/v1 clients, certificate pinning.
// REQUIREMENTS.md "Architecture > Modules" and "Connectivity and failures".
//
// THIS MODULE MUST NOT DEPEND ON :core:domain.
//
// It is a transport layer and nothing more: it speaks HTTP and exposes DTOs
// that mirror the two server lanes exactly as the server sends them. All
// DTO -> domain mapping lives in :core:data, which is the only module that sees
// both sides. Adding a dependency on :core:domain here would let domain types
// leak into the wire model (and vice versa), which is precisely the coupling
// REQUIREMENTS.md "Architecture" removes by hiding both lanes behind one domain
// layer.
//
// It is an Android library rather than a JVM one so that it can carry the two
// permissions it is the reason for in its own manifest (see
// src/main/AndroidManifest.xml), and because the certificate-pinning work it
// owns ends up needing platform APIs: presenting a leaf certificate's
// fingerprint, subject and expiry for the user to confirm, and persisting that
// pin. The HTTP code itself is deliberately platform-neutral, so if the pin
// store ever moves out this module could become a JVM library — the layering
// rule above is what matters, not which plugin it applies.

plugins {
    id("needler.android.library")
    // @Serializable DTOs for both lanes: OpenSubsonic JSON and /api/v1 JSON.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.needler.core.network"
}

dependencies {
    // Both appear in this module's public API (Call/Response types, the Json
    // instance and the DTOs themselves), so they are `api`.
    api(libs.okhttp.core)
    api(libs.kotlinx.serialization.json)

    // Needed for `suspend` client functions. Strictly speaking this is a third
    // dependency beyond "OkHttp + kotlinx-serialization", but no suspending API
    // can be written without it and it pulls in nothing Android-specific.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.okhttp.mockwebserver)
}
