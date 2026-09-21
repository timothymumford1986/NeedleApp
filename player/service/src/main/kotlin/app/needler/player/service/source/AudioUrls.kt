package app.needler.player.service.source

import app.needler.core.domain.model.StreamFormat
import app.needler.core.domain.model.TrackFetchHandle

/**
 * Builds the audio URL for one track at one quality.
 *
 * A port rather than a direct call into `:core:network` so that everything in this package can be exercised
 * without a server, a credential store or an OkHttp client. The adapter over `SubsonicMediaUrls` is in
 * `di`, which is the only file here that knows the Subsonic lane exists.
 *
 * Implementations must never log or persist what they return: the URL carries the app-password in its
 * `apiKey` parameter.
 */
public interface AudioUrls {

    /**
     * `stream?id=...` at the resolved [format].
     *
     * [format] arrives already decided by `ResolvePlayableSourceUseCase` against the user's preference, the
     * connection's metered-ness and the server's advertised capabilities. Implementations must not
     * re-decide it - that is the rule that stops a transcode being retained as a lossless track's offline
     * copy.
     */
    public fun streamUrl(handle: TrackFetchHandle, format: StreamFormat): String
}
