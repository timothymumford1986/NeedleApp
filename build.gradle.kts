// Needler — root build script.
//
// This file intentionally contains no `plugins { }` block and no
// `allprojects`/`subprojects` configuration.
//
// Why no plugins block:
//   Declaring `alias(libs.plugins.x) apply false` here would put plugin x on
//   the root script classpath. Any subproject that then requests the same id
//   *with* a version (as :core:network does for kotlin-serialization, and
//   :core:data for androidx.room) fails with "the plugin is already on the
//   classpath with an unknown version, so compatibility cannot be checked".
//   Every plugin is therefore requested exactly once, in the module that needs
//   it, or via a `needler.*` convention plugin from the `build-logic` included
//   build.
//
// Why no cross-project configuration:
//   Shared Android/Kotlin/Compose setup lives in build-logic/convention as real
//   plugins. That keeps each module's script to "which convention plugin am I"
//   plus "what do I depend on", and stays compatible with the configuration
//   cache and with isolated project execution.
//
// Shared SDK levels, Java level and dependency versions:
//   gradle/libs.versions.toml (versions + compileSdk/minSdk/targetSdk)
//   build-logic/convention/src/main/kotlin/ (how they are applied)
