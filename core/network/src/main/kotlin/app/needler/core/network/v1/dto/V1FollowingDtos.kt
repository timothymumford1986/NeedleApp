package app.needler.core.network.v1.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/v1/following/new-releases` → the server's `WantedResponse`.
 *
 * The one piece of artist following that is in v1, because it is what the third notification
 * ("New release from a followed artist") needs. `in_library` is always false on this endpoint.
 */
@Serializable
public data class NewReleasesDto(
    @SerialName("items") val items: List<NewReleaseDto> = emptyList(),
    @SerialName("total") val total: Int = 0,
)

@Serializable
public data class NewReleaseDto(
    @SerialName("release_group_mbid") val releaseGroupMbid: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist_name") val artistName: String = "",
    @SerialName("artist_mbid") val artistMbid: String = "",
    @SerialName("primary_type") val primaryType: String? = null,
    @SerialName("first_release_date") val firstReleaseDate: String? = null,
    @SerialName("in_library") val inLibrary: Boolean = false,
)

/**
 * `GET /api/v1/following/new-releases/unseen-count` → `UnseenCountResponse`, and also the
 * response of `POST /api/v1/following/new-releases/seen` (always `{"count": 0}` there).
 */
@Serializable
public data class UnseenCountDto(
    @SerialName("count") val count: Int = 0,
)
