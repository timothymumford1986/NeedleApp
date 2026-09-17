package app.needler.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider

/**
 * Access to `gradle/libs.versions.toml` from inside a convention plugin.
 *
 * The catalogue is the single source of truth for SDK levels as well as
 * dependency versions, so the convention plugins read `compileSdk`, `minSdk`
 * and `targetSdk` from it rather than hard-coding them here.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

internal fun VersionCatalog.requiredVersion(alias: String): String =
    findVersion(alias)
        .orElseThrow { IllegalStateException("Version '$alias' is missing from gradle/libs.versions.toml") }
        .requiredVersion

internal fun VersionCatalog.requiredInt(alias: String): Int =
    requiredVersion(alias).toIntOrNull()
        ?: error("Version '$alias' in gradle/libs.versions.toml is not an integer")

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias)
        .orElseThrow { IllegalStateException("Library '$alias' is missing from gradle/libs.versions.toml") }
