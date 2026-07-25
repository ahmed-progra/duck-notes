package app.duck.notes.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import app.duck.notes.data.local.entity.NoteLinkEntity
import kotlinx.coroutines.flow.Flow

/**
 * `[[WikiLink]]` reads. Backlinks only this phase — writing rows on save lands in Phase 9, so the
 * table stays empty until then. Reads hit the `target_id` index declared on the entity.
 */
@Dao
interface NoteLinkDao {

    @Query("SELECT * FROM note_links WHERE target_id = :noteId")
    fun observeBacklinks(noteId: String): Flow<List<NoteLinkEntity>>
}
