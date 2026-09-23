package app.needler.feature.pulls

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
 * `viewModelScope` is hard-wired to `Dispatchers.Main.immediate`, which does not
 * exist in a plain JVM test, so without this the ViewModel throws on
 * construction — and `PullsViewModel` does real work in `init`, so it would
 * throw before a single assertion ran.
 *
 * A copy of `:feature:library`'s rule, for the same reason its
 * `NeedlerScreenshots` is a copy: a test source set is not published, so there
 * is no way for one module to depend on another's. A `:core:testing` module
 * holding one copy is the fix, and it is in the handover notes.
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
