// Needler — build settings
//
// Plugin resolution comes from two places:
//   1. `build-logic`, an included build that publishes the `needler.*`
//      convention plugins. Because it is included from `pluginManagement`,
//      modules apply them by id with no version.
//   2. The declared repositories, for plugins a module applies directly
//      (kotlin-serialization, androidx.room).
//
// The root build script deliberately declares no plugins. Putting a plugin on
// the root script classpath makes any versioned request for the same id in a
// subproject fail ("already on the classpath with an unknown version"), so
// versions are requested exactly once, where they are used.

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // No module may declare its own repositories.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
    }
    // gradle/libs.versions.toml is picked up automatically as `libs`.
}

rootProject.name = "Needler"

// Module list: REQUIREMENTS.md "Architecture > Modules".
include(":app")

include(":core:design")
include(":core:domain")
include(":core:data")
include(":core:network")

include(":feature:library")
include(":feature:search")
include(":feature:pulls")
include(":feature:player")

include(":player:service")

include(":widget")
include(":wear")
