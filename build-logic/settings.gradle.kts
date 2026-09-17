// `build-logic` is an included build (see the root settings.gradle.kts) whose
// only job is to publish the `needler.*` convention plugins.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Share the one version catalogue with the main build, so the toolchain
    // versions the convention plugins put on the classpath can never drift from
    // the versions the modules consume.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
