package app.duck.notes.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.local.entity.NoteTagCrossRef
import app.duck.notes.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Reads and writes for notes, plus the atomic note-with-tags save.
 *
 * Abstract class rather than interface so [saveNoteWithTags] can be a `@Transaction` default
 * method that calls the tag/cross-ref helpers below — Room only lets a transaction call methods
 * on the same DAO. Full-text search goes through [search] (`@RawQuery`) because Room's schema
 * parser can't see the hand-built `notes_fts` virtual table and would reject a normal `@Query`.
 */
@Dao
abstract class NoteDao {

    // --- Observation --------------------------------------------------------

    @Query("SELECT * FROM notes WHERE is_trashed = 0 ORDER BY modified_at DESC")
    abstract fun observeNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    abstract fun observeNote(id: String): Flow<NoteEntity?>

    @Query("SELECT COUNT(*) FROM notes")
    abstract suspend fun count(): Int

    @Query(
        """
        SELECT n.* FROM notes AS n
        JOIN note_tags AS nt ON nt.note_id = n.id
        WHERE nt.tag_id = :tagId AND n.is_trashed = 0
        ORDER BY n.modified_at DESC
        """,
    )
    abstract fun notesByTag(tagId: String): Flow<List<NoteEntity>>

    // --- Search -------------------------------------------------------------

    /**
     * Prefix full-text search over active notes, most-recent first. Each token is turned into an
     * FTS5 prefix term (`duck note` -> `duck* note*`, implicit AND). Query hardening — operators,
     * phrases, snippet highlighting — lands in Phase 10.
     */
    open suspend fun search(term: String): List<NoteEntity> {
        val match = ftsMatch(term)
        if (match.isEmpty()) return emptyList()
        val sql =
            """
            SELECT n.* FROM notes AS n
            JOIN notes_fts AS f ON f.rowid = n.rowid
            WHERE f MATCH ? AND n.is_trashed = 0
            ORDER BY n.modified_at DESC
            """.trimIndent()
        return searchRaw(SimpleSQLiteQuery(sql, arrayOf(match)))
    }

    @RawQuery
    protected abstract suspend fun searchRaw(query: SupportSQLiteQuery): List<NoteEntity>

    // --- Direct writes ------------------------------------------------------

    @Insert
    abstract suspend fun insert(note: NoteEntity): Long

    @Update
    abstract suspend fun update(note: NoteEntity)

    @Delete
    abstract suspend fun delete(note: NoteEntity)

    /**
     * Insert a new note and (re)wire its tags in one transaction: replace the note's cross-refs,
     * create any missing tags, then drop tags left orphaned — except pinned or custom-icon tags,
     * whose user customization must survive losing their last note. The AFTER-INSERT trigger on
     * `notes` mirrors title+body into `notes_fts` automatically.
     *
     * Insert-only this phase (seeds/tests create fresh notes); the update path is the Phase 3
     * repository's job.
     */
    @Transaction
    open suspend fun saveNoteWithTags(note: NoteEntity, tagNames: List<String>) {
        insert(note)
        clearCrossRefs(note.id)
        for (name in tagNames) {
            insertCrossRef(NoteTagCrossRef(noteId = note.id, tagId = ensureTag(name)))
        }
        deleteOrphanTags()
    }

    // --- Transaction building blocks ---------------------------------------

    protected open suspend fun ensureTag(name: String): String =
        tagIdByName(name) ?: UUID.randomUUID().toString().also { id ->
            insertTag(TagEntity(id = id, name = name))
        }

    @Query("SELECT id FROM tags WHERE name = :name")
    protected abstract suspend fun tagIdByName(name: String): String?

    @Insert
    protected abstract suspend fun insertTag(tag: TagEntity)

    @Query("DELETE FROM note_tags WHERE note_id = :noteId")
    protected abstract suspend fun clearCrossRefs(noteId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCrossRef(ref: NoteTagCrossRef)

    @Query(
        """
        DELETE FROM tags
        WHERE id NOT IN (SELECT tag_id FROM note_tags)
          AND is_pinned = 0 AND icon_key IS NULL
        """,
    )
    protected abstract suspend fun deleteOrphanTags()

    private fun ftsMatch(term: String): String =
        term.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "$it*" }
}
