package app.duck.notes.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import app.duck.notes.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/** One `note_tags` row projected onto the tag's name — the count query's only shape. */
data class TagNoteRefRow(val name: String, val noteId: String)

/**
 * Tag reads plus user-authored writes (pin, rename, icon).
 *
 * Tag *creation from note bodies* and orphan cleanup still happen inside
 * [NoteDao.saveNoteWithTags] — Phase 7 did not change that, because rename and delete are body
 * edits and the rows follow from re-parsing exactly as they do on an ordinary save. What Phase 7
 * added here are the by-name lookups the two operations need to carry customization across and to
 * clean up after themselves.
 */
@Dao
interface TagDao {

    @Query("SELECT * FROM tags ORDER BY sort_order, name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: String): TagEntity?

    /**
     * By name rather than id, because rename and delete address tags the way the user does — by the
     * text in the body — while the id is a UUID minted on first use.
     */
    @Query("SELECT * FROM tags WHERE name = :name")
    suspend fun getByName(name: String): TagEntity?

    /**
     * Every (tag name, note id) pair over non-trashed notes. Feeds the sidebar's counts.
     *
     * Name, not id: counts are keyed by *path*, so `projects/duck/ui` has to contribute to
     * `projects` and `projects/duck` as well as to itself, and an id says nothing about ancestry.
     */
    @Query(
        """
        SELECT t.name AS name, nt.note_id AS noteId
        FROM note_tags AS nt
        JOIN tags AS t ON t.id = nt.tag_id
        JOIN notes AS n ON n.id = nt.note_id
        WHERE n.is_trashed = 0
        """,
    )
    fun observeNoteRefs(): Flow<List<TagNoteRefRow>>

    /**
     * The row for [path] plus every descendant row — rename's cascade set.
     *
     * Same `substr` prefix rule as [NoteDao.notesByTagPath], and for the same reasons; the index
     * trade is argued in full there.
     */
    @Query(
        """
        SELECT * FROM tags
        WHERE name = :path OR substr(name, 1, length(:path) + 1) = :path || '/'
        ORDER BY name
        """,
    )
    suspend fun rowsUnderPath(path: String): List<TagEntity>

    @Upsert
    suspend fun upsert(tag: TagEntity)

    /**
     * Unconditional. Used by delete, and by rename for the old rows it has just emptied.
     *
     * Needed because [NoteDao.saveNoteWithTags]'s `deleteOrphanTags` **spares pinned and icon'd
     * rows** by design — so the case where a user is most likely to be deleting a tag is exactly the
     * case where the row would otherwise survive its own deletion.
     */
    @Query("DELETE FROM tags WHERE name = :name")
    suspend fun deleteByName(name: String)

    /**
     * Undo's sweep: delete the row **only if nothing references it**.
     *
     * Rename carries the old row's pin onto the new name, which is what makes the new row survive
     * `deleteOrphanTags` — so restoring the old bodies would otherwise leave a pinned, empty `job`
     * behind after an undo that claimed to restore everything.
     *
     * Conditional, and that is the whole subtlety: on a **merge** undo, the target still holds its
     * own original notes and must live. `NOT IN` answers both cases with one query.
     */
    @Query("DELETE FROM tags WHERE name = :name AND id NOT IN (SELECT tag_id FROM note_tags)")
    suspend fun deleteIfOrphaned(name: String)
}
