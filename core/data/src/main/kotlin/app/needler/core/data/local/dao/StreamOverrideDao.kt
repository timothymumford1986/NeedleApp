package app.needler.core.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.needler.core.data.local.entity.StreamOverrideEntity
import app.needler.core.data.local.entity.StreamOverrideScopeDb
import kotlinx.coroutines.flow.Flow

/**
 * Per-item stream-quality overrides: at most one row per track and one per album.
 *
 * Every read is a primary-key point lookup on (scope, item_id), so there is no index beyond the key
 * and no ordering to keep. The table holds one row per item the user has actually overridden, which
 * on a real library is a handful.
 *
 * [get] is the playback path - `ResolvePlayableSourceUseCase` calls it twice per streamed track, once
 * per scope - and is a suspend read rather than a Flow deliberately: a subscription per play would
 * outlive the decision it was opened for. [observe] exists for the screens that draw what pressing
 * play would fetch right now, which do need to redraw when the value changes under them.
 */
@Dao
public interface StreamOverrideDao {

    @Query("SELECT * FROM stream_override WHERE scope = :scope AND item_id = :itemId")
    public suspend fun get(scope: StreamOverrideScopeDb, itemId: String): StreamOverrideEntity?

    @Query("SELECT * FROM stream_override WHERE scope = :scope AND item_id = :itemId")
    public fun observe(scope: StreamOverrideScopeDb, itemId: String): Flow<StreamOverrideEntity?>

    /**
     * Sets or replaces one override.
     *
     * `@Upsert` rather than insert-or-update: setting a rung on an item that already has one is the
     * commonest write here, and a two-statement version would leave a window in which the item has no
     * override at all.
     */
    @Upsert
    public suspend fun upsert(override: StreamOverrideEntity)

    @Query("DELETE FROM stream_override WHERE scope = :scope AND item_id = :itemId")
    public suspend fun delete(scope: StreamOverrideScopeDb, itemId: String)

    /**
     * Drops every override.
     *
     * Not called on a server change - see [StreamOverrideEntity] for why a quality preference keyed on
     * a global MBID outlives the server it was set against. This exists for "sign out and forget
     * everything".
     */
    @Query("DELETE FROM stream_override")
    public suspend fun clear()
}
