import app.needler.buildlogic.addSharedAndroidTestDependencies
import app.needler.buildlogic.addSharedComposeDependencies
import app.needler.buildlogic.addSharedUnitTestDependencies
import app.needler.buildlogic.libs
import app.needler.buildlogic.requiredInt
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * `needler.android.application` — an installable Android application. Used by
 * `:app` and by `:wear`, which ships as its own APK.
 *
 * Includes the Compose setup, because both applications are Compose
 * applications. See AndroidLibraryComposeConventionPlugin for why the Compose
 * compiler plugin is applied explicitly.
 *
 * The release build type is configured here rather than per module so the two
 * APKs cannot drift: REQUIREMENTS.md "Security" requires release builds to be
 * obfuscated with `minifyEnabled` and no debug logging. Both application
 * modules therefore need a `proguard-rules.pro` — AGP 9 defaults
 * `android.proguard.failOnMissingFiles` to true and will fail the build if a
 * named keep file is absent.
 *
 * No signing config is declared. REQUIREMENTS.md ships a signed APK from GitHub
 * releases; the keystore is supplied by whoever cuts the release, not committed.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<ApplicationExtension> {
                compileSdk = libs.requiredInt("compileSdk")

                defaultConfig {
                    minSdk = libs.requiredInt("minSdk")
                    targetSdk = libs.requiredInt("targetSdk")
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                buildFeatures {
                    compose = true
                }

                buildTypes {
                    getByName("debug") {
                        isMinifyEnabled = false
                    }
                    getByName("release") {
                        isMinifyEnabled = true
                        isShrinkResources = true
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro",
                        )
                    }
                }

                packaging {
                    resources {
                        excludes.add("/META-INF/{AL2.0,LGPL2.1}")
                        excludes.add("/META-INF/DEPENDENCIES")
                        excludes.add("/META-INF/LICENSE*")
                    }
                }

                testOptions {
                    unitTests {
                        isIncludeAndroidResources = true
                        isReturnDefaultValues = true
                    }
                }
            }

            addSharedComposeDependencies()
            addSharedUnitTestDependencies()
            addSharedAndroidTestDependencies()
        }
    }
}
