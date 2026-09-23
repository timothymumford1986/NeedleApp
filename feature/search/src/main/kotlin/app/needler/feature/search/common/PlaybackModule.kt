package app.needler.feature.search.common

import app.needler.core.domain.playback.PlaybackController
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Makes [PlaybackController] an *optional* dependency of this module's
 * ViewModel.
 *
 * REQUIREMENTS.md "The player boundary" puts `PlaybackController` in
 * `:core:domain` and its implementation in `:player:service`, which has no
 * sources yet — so nothing in the app binds the interface. Injecting it
 * directly would not fail here; it would fail in `:app`, where Hilt assembles
 * the one component and validates the whole graph, with "PlaybackController
 * cannot be provided without an @Provides-annotated method". A feature module
 * that stopped the app compiling because the player has not been written yet
 * would be a bad neighbour.
 *
 * `@BindsOptionalOf` says "this type may or may not be bound" without asserting
 * that it is. Today [app.needler.feature.search.search.SearchViewModel] receives
 * an empty `Optional` and tapping a song in the Songs block does nothing; the
 * moment `:player:service` binds a controller the same `Optional` arrives full
 * and the tap plays, with no change here.
 *
 * ## Why declaring it twice is safe
 *
 * `:feature:library` declares the same optional binding, and both modules are
 * installed in the same [SingletonComponent]. That is deliberate and it is
 * allowed: `@BindsOptionalOf` contributes a *declaration* that a key is
 * optional, not a binding for it, and Dagger folds any number of declarations
 * for one key into the single synthetic `Optional<T>` binding. The alternative —
 * one shared module in a module both features can see — means putting a Hilt
 * module in `:core:domain`, which is a pure Kotlin/JVM library with no
 * `javax.inject` and no Dagger on its classpath by design.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface PlaybackModule {

    @BindsOptionalOf
    fun playbackController(): PlaybackController
}
