package app.needler.feature.search.common

import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackController
import app.needler.feature.search.search.SearchNotice

/**
 * Put tracks in the crate, and say what happened.
 *
 * A duplicate of `:feature:library`'s helper of the same name, for the same reason every other
 * member of [SearchFormat] duplicates its twin there: two feature modules cannot share a file
 * without a `:core:*` to put it in, and this one is not a design-system component - it decides
 * which of two domain commands to send.
 *
 * ## Why an empty crate is a different call
 *
 * `PlaybackController.enqueue` "adds tracks to the crate without disturbing what is playing",
 * which with nothing playing means a loaded crate and silence: the user taps Add to the crate,
 * hears nothing, sees nothing, and has no reason to believe the tap registered. So with nothing
 * loaded this starts playback through `playTracks` instead, and the notice says it did.
 *
 * `currentState()` rather than the crate count already on screen: the snapshot is the session's own
 * answer at the moment of the command, and the observed flow can be a frame behind a skip or a
 * track ending. The figures the screen draws may lag by a frame; the choice of command may not.
 */
internal suspend fun PlaybackController.addTracksToCrate(
    tracks: List<Track>,
    playNext: Boolean,
): SearchNotice {
    val loaded: Boolean = currentState().hasCurrentItem
    if (loaded) {
        enqueue(tracks, playNext = playNext)
    } else {
        playTracks(tracks, startIndex = 0)
    }
    return SearchNotice.addedToCrate(
        trackCount = tracks.size,
        playNext = playNext,
        started = !loaded,
    )
}
