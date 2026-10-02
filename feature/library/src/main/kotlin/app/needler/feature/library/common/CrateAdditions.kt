package app.needler.feature.library.common

import app.needler.core.domain.model.Track
import app.needler.core.domain.playback.PlaybackController
import app.needler.feature.library.album.AlbumNotice

/**
 * Put tracks in the crate, and say what happened.
 *
 * One implementation for the four surfaces in this module that can now add - album detail, artist
 * detail, the library's album rows and its Songs tab - because the decision below is not obvious and
 * four copies of it would not stay the same.
 *
 * ## Why an empty crate is a different call
 *
 * `PlaybackController.enqueue` "adds tracks to the crate without disturbing what is playing", which
 * with nothing playing means a loaded crate and silence: the user taps Add to the crate, hears
 * nothing, sees nothing, and has no reason to believe the tap registered. Nothing in
 * `PlaybackController` starts a crate that is already loaded either - there is `play`, but it acts
 * on the session, and a session with no media item is exactly the state being fixed.
 *
 * So with nothing loaded this starts playback instead, through `playTracks`, and the notice says it
 * did. REQUIREMENTS.md "Queue" has the crate persist across restarts, so this is also the first-run
 * case: the crate is empty until something fills it, and the control that fills it should not need
 * a second tap somewhere else to be heard.
 *
 * `currentState()` rather than the crate count the screen is already showing: the snapshot is the
 * session's own answer at the moment of the command, and the observed flow can be a frame behind a
 * skip or a track ending. The figures on screen may lag by a frame; the choice of command may not.
 *
 * The notice is returned rather than posted, so the caller decides whether it belongs in its busy
 * gate - every one of them already has `runExclusively` and a notice field of its own.
 */
internal suspend fun PlaybackController.addTracksToCrate(
    tracks: List<Track>,
    playNext: Boolean,
): AlbumNotice.AddedToCrate {
    val loaded: Boolean = currentState().hasCurrentItem
    if (loaded) {
        enqueue(tracks, playNext = playNext)
    } else {
        playTracks(tracks, startIndex = 0)
    }
    return AlbumNotice.AddedToCrate(
        trackCount = tracks.size,
        playNext = playNext,
        // `started` outranks `playNext` in the sentence: there is nothing to play next of.
        started = !loaded,
    )
}
