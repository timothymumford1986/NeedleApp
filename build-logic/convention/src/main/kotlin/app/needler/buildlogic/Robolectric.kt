package app.needler.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/**
 * The JVM arguments Robolectric needs, and the heap a framework-on-the-JVM run needs.
 *
 * ## Why this is shared rather than inline
 *
 * It was inline in `ScreenshotConventionPlugin`, which is the only place that had ever needed
 * Robolectric - so a module that wanted Robolectric *without* screenshots got a failure whose
 * message names neither Robolectric nor the module system:
 *
 *     java.lang.RuntimeException: Failed to interact with raw FileDescriptor internals;
 *     perhaps JRE has changed?
 *
 * `player/service` hit it the first time it tested `BrowseItems`, a class whose metadata is built
 * from a `Bundle` and a `Uri`. Copying ten `--add-opens` lines into that module's build file would
 * have left two lists to keep in step, with the next module to need them copying whichever it found.
 *
 * ## What the arguments are for
 *
 * Robolectric reaches into the JDK to emulate the framework, and the module system says no by
 * default. On JDK 21 and later the first thing that fails is `ApplicationSharedMemory.create()`
 * during application set-up: it goes through Robolectric's `FileDescriptor` interceptor, which calls
 * `jdk.internal.access.SharedSecrets` - a package `java.base` does not export at all. That surfaces
 * as the message above, with the real `IllegalAccessException` three frames down.
 *
 * The export is the one strictly required; the opens are Robolectric's documented set and cost
 * nothing.
 */
fun Project.configureRobolectricTests() {
    tasks.withType(Test::class.java).configureEach {
        // Robolectric downloads its android-all jar on first use and instruments a lot of framework
        // classes; the default heap is not enough to render a tablet-sized bitmap.
        maxHeapSize = "2g"
        jvmArgs(ROBOLECTRIC_JVM_ARGS)
    }
}

private val ROBOLECTRIC_JVM_ARGS: List<String> = listOf(
    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.security=ALL-UNNAMED",
    "--add-opens=java.base/java.text=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
)
