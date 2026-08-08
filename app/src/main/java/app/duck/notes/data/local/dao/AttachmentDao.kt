package app.duck.notes.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.duck.notes.data.local.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for attachment rows. An interface, not an abstract class like [NoteDao]: there is
 * no cross-DAO transaction here, so Room needs nothing more than the generated queries.
 *
 * Deletes are by id — the file is removed separately by `AttachmentStorage`, and `DeleteAttachment`
 * owns doing both. The FK on `note_id` cascades a row away when its note is hard-deleted (Phase 14);
 * the file it points at is that sweep's job, the same accepted orphan as any hard delete.
 */
@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE note_id = :noteId ORDER BY created_at ASC")
    fun observeForNote(noteId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE note_id = :noteId ORDER BY created_at ASC")
    suspend fun forNote(noteId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun getById(id: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: String)
}
