package app.duck.notes.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.duck.notes.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/**
 * Tag reads plus user-authored upsert (pin, rename, set DuckCon). Tag *creation from note bodies*
 * and orphan cleanup happen inside [NoteDao.saveNoteWithTags]; the sidebar tree is Phase 7.
 */
@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY sort_order, name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    @Upsert
    suspend fun upsert(tag: TagEntity)
}
