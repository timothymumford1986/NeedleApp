plugins {
    `kotlin-dsl`
}

group = "app.needler.buildlogic"

// AGP 9 requires JDK 17 as a minimum, and 17 is the Java level the whole
// project compiles against, so the convention plugins are built the same way.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // These are `implementation`, not `compileOnly`, on purpose.
    //
    // The convention plugins apply AGP, the Compose compiler plugin, KSP and the
    // Hilt plugin *by id*, so those plugins have to be on this project's runtime
    // classpath — that classpath becomes the script classpath of any module
    // which applies a `needler.*` plugin. Using compileOnly would compile fine
    // and then fail at apply time with "plugin not found".
    //
    // Declaring kotlin-gradle-plugin here does a second, load-bearing job:
    // AGP 9.4.0 depends on KGP 2.2.10 and uses it for built-in Kotlin. Putting
    // KGP 2.4.20 on the same classpath raises the Kotlin compiler used by every
    // Android module to 2.4.20, which is the version the Compose compiler plugin
    // and coil 3.6.2 require. See the notes at the top of libs.versions.toml.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "needler.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
            description = "Android application module: SDK levels, Java 17, Compose, release shrinking."
        }
        register("androidLibrary") {
            id = "needler.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
            description = "Android library module: SDK levels, Java 17, shared unit-test deps."
        }
        register("androidLibraryCompose") {
            id = "needler.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
            description = "Android library module that contains Compose UI."
        }
        register("jvmLibrary") {
            id = "needler.jvm.library"
            implementationClass = "JvmLibraryConventionPlugin"
            description = "Pure Kotlin/JVM library with no Android dependencies."
        }
        register("hilt") {
            id = "needler.hilt"
            implementationClass = "HiltConventionPlugin"
            description = "KSP + Hilt wiring for a module that participates in the DI graph."
        }
        register("screenshots") {
            id = "needler.screenshots"
            implementationClass = "ScreenshotConventionPlugin"
            description = "Renders Compose screens to PNG on the JVM (Roborazzi + Robolectric)."
        }
    }
}
