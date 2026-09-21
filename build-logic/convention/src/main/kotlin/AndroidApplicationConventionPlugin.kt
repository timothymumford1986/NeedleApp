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

                // Release signing comes entirely from the environment, never from a file in the
                // repository. CI decodes the keystore from a secret into a path it passes here;
                // locally you would export the same four variables. When they are absent the
                // release build is simply unsigned, which is the correct behaviour for anyone who
                // clones this: they get a build, not a key.
                //
                // Android identifies an app by its signing key for the life of the install, so
                // this key cannot be rotated or regenerated. Losing it means no future build can
                // install over an existing one -- users must uninstall first, losing their
                // settings and their downloaded music.
                val keystorePath: String? = providers.environmentVariable("NEEDLER_KEYSTORE").orNull
                val keystorePassword: String? =
                    providers.environmentVariable("NEEDLER_KEYSTORE_PASSWORD").orNull
                val keyAlias: String? = providers.environmentVariable("NEEDLER_KEY_ALIAS").orNull
                val keyPassword: String? =
                    providers.environmentVariable("NEEDLER_KEY_PASSWORD").orNull
                val canSign: Boolean = !keystorePath.isNullOrBlank() &&
                    !keystorePassword.isNullOrBlank() &&
                    !keyAlias.isNullOrBlank() &&
                    !keyPassword.isNullOrBlank()

                if (canSign) {
                    signingConfigs.create("release") {
                        storeFile = file(keystorePath!!)
                        storePassword = keystorePassword
                        this.keyAlias = keyAlias
                        this.keyPassword = keyPassword
                    }
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
                        if (canSign) {
                            signingConfig = signingConfigs.getByName("release")
                        }
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
