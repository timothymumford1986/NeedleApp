package app.needler.feature.library.common

import app.needler.core.domain.playback.PlaybackController
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Makes [PlaybackController] an *optional* dependency of this module's
 * ViewModels.
 *
 * REQUIREMENTS.md "The player boundary" puts `PlaybackController` in
 * `:core:domain` and its implementation in `:player:service`, which does not
 * exist yet: `:player:service` currently has no sources at all, so nothing in
 * the app binds the interface.
 *
 * Injecting it directly would therefore not fail here — it would fail in
 * `:app`, where Hilt assembles the one component and validates the whole graph,
 * with "PlaybackController cannot be provided without an @Provides-annotated
 * method". A feature module that made the app stop compiling because the player
 * has not been written yet would be a bad neighbour.
 *
 * `@BindsOptionalOf` says "this type may or may not be bound" without asserting
 * that it is. Today the ViewModels receive an empty `Optional` and every
 * transport call is a no-op; the moment `:player:service` binds a controller,
 * the same `Optional` arrives full and playback starts working with no change
 * here. That is the whole of the migration.
 */
@Module
@InstallIn(SingletonComponent::class)
internal interface PlaybackModule {

    @BindsOptionalOf
    fun playbackController(): PlaybackController
}
