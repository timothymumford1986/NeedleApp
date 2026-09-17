import app.needler.buildlogic.addSharedComposeDependencies
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `needler.android.library.compose` — an Android library module containing
 * Compose UI. Layers Compose on top of `needler.android.library`.
 *
 * Two things are required and both are done here:
 *  - `org.jetbrains.kotlin.plugin.compose`, the Compose compiler plugin. Its
 *    version tracks Kotlin exactly (2.4.20). AGP would otherwise supply the
 *    Compose compiler that matches *its* bundled Kotlin (2.2.10), which cannot
 *    be used with the 2.4.20 compiler; applying the plugin overrides that.
 *  - `buildFeatures.compose = true`, which is still how AGP is told a module
 *    contains Compose.
 */
class AndroidLibraryComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("needler.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<LibraryExtension> {
                buildFeatures {
                    compose = true
                }
            }

            addSharedComposeDependencies()
        }
    }
}
