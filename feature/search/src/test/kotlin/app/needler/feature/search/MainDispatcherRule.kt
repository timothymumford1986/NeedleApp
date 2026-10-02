package app.needler.feature.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Puts a test dispatcher behind `Dispatchers.Main`.
 *
 * `viewModelScope` is hard-wired to `Dispatchers.Main.immediate`, which does not exist in a plain
 * JVM test, so without this the ViewModel throws on construction.
 *
 * A copy of `:feature:library`'s rule of the same name, for the same reason [Fakes] is a copy: a
 * test source set is not publishable, and the shared `:core:testing` module that would hold one
 * copy of both is in the handover notes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
