import app.needler.buildlogic.addSharedUnitTestDependencies
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/**
 * `needler.jvm.library` — a pure Kotlin/JVM library. Used only by
 * `:core:domain`, which REQUIREMENTS.md defines as entities, repository
 * interfaces and use cases.
 *
 * Applying the Kotlin JVM plugin (rather than an Android plugin) is what
 * *enforces* that layer boundary: `android.*` and `androidx.*` classes are not
 * on the compile classpath at all, so a stray Android import fails to compile
 * instead of being caught in review.
 *
 * The JVM target is set on the compile tasks rather than through
 * `kotlin { jvmToolchain(17) }` deliberately: a toolchain spec makes Gradle
 * locate or provision a matching JDK, which needs a toolchain resolver plugin.
 * Setting the target keeps the build working with whatever JDK 17+ runs Gradle.
 */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            // java-library is applied explicitly so the module can use `api(...)`
            // for types that appear in its own public signatures (Flow, Instant).
            pluginManager.apply("java-library")
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            tasks.withType<KotlinJvmCompile>().configureEach {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }

            addSharedUnitTestDependencies()
        }
    }
}
