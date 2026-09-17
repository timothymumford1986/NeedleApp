import app.needler.buildlogic.addSharedAndroidTestDependencies
import app.needler.buildlogic.addSharedUnitTestDependencies
import app.needler.buildlogic.libs
import app.needler.buildlogic.requiredInt
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `needler.android.library` — an Android library module.
 *
 * Notes on AGP 9:
 *  - The Kotlin Android plugin is NOT applied. AGP 9 has built-in Kotlin and is
 *    incompatible with `org.jetbrains.kotlin.android`. Kotlin sources under
 *    `src/main/kotlin` are compiled by AGP itself.
 *  - No `kotlin { compilerOptions { jvmTarget = ... } }` block is needed:
 *    with built-in Kotlin the Kotlin JVM target defaults to
 *    `android.compileOptions.targetCompatibility`, which is set below.
 *  - `namespace` is intentionally not set here; every module declares its own
 *    (AGP 9 enforces unique package names across modules).
 *  - `targetSdk` is intentionally not set on library modules; it comes from the
 *    consuming application.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")

            extensions.configure<LibraryExtension> {
                compileSdk = libs.requiredInt("compileSdk")

                defaultConfig {
                    minSdk = libs.requiredInt("minSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    // No consumerProguardFiles() here on purpose: AGP 9 defaults
                    // `android.proguard.failOnMissingFiles` to true, so naming a
                    // consumer-rules.pro would make every library module fail
                    // until that file exists. Add it per module, with the file,
                    // when a module actually needs to ship keep rules.
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                testOptions {
                    targetSdk = libs.requiredInt("targetSdk")
                    unitTests {
                        isIncludeAndroidResources = true
                        isReturnDefaultValues = true
                    }
                }
            }

            addSharedUnitTestDependencies()
            addSharedAndroidTestDependencies()
        }
    }
}
