import app.needler.buildlogic.library
import app.needler.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `needler.hilt` — KSP plus Hilt for a module that takes part in the DI graph.
 *
 * Applied alongside an Android convention plugin, never on its own:
 *
 *     plugins {
 *         id("needler.android.library")
 *         id("needler.hilt")
 *     }
 *
 * KSP is applied first so that the `ksp` configuration exists by the time the
 * dependencies below are added. Modules that need a second processor (Room in
 * `:core:data`) just add to the same `ksp` configuration.
 *
 * KAPT is not used anywhere and must not be introduced: the Hilt Gradle plugin
 * does not work with AGP 9's built-in Kotlin via `com.android.legacy-kapt`, and
 * `org.jetbrains.kotlin.kapt` is incompatible with built-in Kotlin outright.
 */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")

            dependencies.apply {
                addProvider("implementation", libs.library("hilt.android"))
                addProvider("ksp", libs.library("hilt.compiler"))
            }
        }
    }
}
