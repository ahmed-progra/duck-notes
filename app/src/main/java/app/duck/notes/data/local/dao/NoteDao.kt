package app.duck.notes.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.room.Update
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

    @Query("SELECT * FROM notes WHERE id = :id")
    abstract suspend fun getById(id: String): NoteEntity?

    @Query("SELECT COUNT(*) FROM notes")
    abstract suspend fun count(): Int

    /**
     * Notes carrying **exactly** this tag row, by id. The search path's tag narrowing.
     *
     * Phase 7 added [notesByTagPath], which is by *name* and includes the subtree — that is what the
     * sidebar filters by now. This one survives because `SearchQuery.tagId` is an id and search is
     * Phase 10's. **Phase 10 has to decide whether `tag:x` means the subtree**, and if it does, this
     * method is the thing to retire rather than a second prefix rule to write.
     */
    @Query(
        """
        SELECT n.* FROM notes AS n
        JOIN note_tags AS nt ON nt.note_id = n.id
        WHERE nt.tag_id = :tagId AND n.is_trashed = 0
        ORDER BY n.modified_at DESC
        """,
    )
    abstract fun notesByTag(tagId: String): Flow<List<NoteEntity>>

    /**
     * Notes under [path] **and every descendant of it** — `projects` finds `projects/duck/ui`. The
     * sidebar's tag filter, owed since Phase 2.
     *
     * ## Three things in the WHERE clause, and none is incidental
     *
     * - **`substr`, not `LIKE :path || '/%'`.** Tag names may contain `_` — `TagScanner.isTagChar`
     *   permits it — and `_` is a `LIKE` single-character wildcard, so `#a_b` would prefix-match
     *   `#axb/c`. `substr` needs no `ESCAPE` clause and no escaping helper to get right.
     * - **`DISTINCT`.** A note carrying both `#projects/duck` and `#projects/duck/ui` matches twice.
     * - **`|| '/'`.** Without the separator `#project` would match `#projects/duck`.
     *
     * ## The index trade, recorded here on purpose
     *
     * `LIKE 'prefix%'` **can** use the UNIQUE index `TagEntity` declares on `tags.name` — SQLite's
     * LIKE optimization applies to a pattern with no leading wildcard. `substr(t.name, 1, n)` is a
     * function on the column, so no index can serve it and this scans `tags` end to end.
     *
     * That is the correct trade at this scale, deliberately: `tags` is the **smallest** table in the
     * schema — one row per distinct tag — while `notes` and `note_tags` are the large ones and are
     * still reached by indexed joins. `DuckDatabaseTest` polices it at 5000 notes against Phase 2's
     * 30ms tag-filter budget.
     *
     * **The temptation is real precisely because the index exists**, which is why the reason lives at
     * the query rather than in a commit message: a future perf pass sees a table scan, "fixes" it to
     * `LIKE`, and silently reintroduces the `_` bug above. Anyone moving to `LIKE` owes an `ESCAPE`
     * clause and an escaping helper.
     */
    @Query(
        """
        SELECT DISTINCT n.* FROM notes AS n
        JOIN note_tags AS nt ON nt.note_id = n.id
        JOIN tags AS t ON t.id = nt.tag_id
        WHERE n.is_trashed = 0
          AND (t.name = :path OR substr(t.name, 1, length(:path) + 1) = :path || '/')
        ORDER BY n.modified_at DESC
        """,
    )
    abstract fun notesByTagPath(path: String): Flow<List<NoteEntity>>

    /**
     * One-shot subtree read for **rename**, which cascades to descendants.
     *
     * **Deliberately omits `is_trashed = 0`**, and that is the difference from [notesByTagPath]. A
     * trashed note still holds `#work` in its body; skip it and restoring that note months later
     * re-creates the old tag through `ensureTag` — the rename undoing itself, one note at a time.
     * The filter must hide trashed notes; the rewrite must not.
     */
    @Query(
        """
        SELECT DISTINCT n.* FROM notes AS n
        JOIN note_tags AS nt ON nt.note_id = n.id
        JOIN tags AS t ON t.id = nt.tag_id
        WHERE t.name = :path OR substr(t.name, 1, length(:path) + 1) = :path || '/'
        """,
    )
    abstract suspend fun notesByTagPathOnce(path: String): List<NoteEntity>

    /**
     * One-shot **exact-name** read for delete, which does not cascade. Trashed included, same reason
     * as [notesByTagPathOnce].
     *
     * Separate from [notesByTagPathOnce] because the two operations genuinely differ in scope: a
     * child's name contains its parent's, so rename must cascade or the names break, while
     * `projects/duck` stays valid after `projects` is deleted and simply becomes an intermediate
     * segment.
     */
    @Query(
        """
        SELECT DISTINCT n.* FROM notes AS n
        JOIN note_tags AS nt ON nt.note_id = n.id
        JOIN tags AS t ON t.id = nt.tag_id
        WHERE t.name = :name
        """,
    )
    abstract suspend fun notesByTagNameOnce(name: String): List<NoteEntity>

    /**
     * The sidebar's Trash row. Ordered by [NoteEntity.trashedAt], not `modified_at`: the useful
     * order in a trash view is when it was thrown away, and Phase 14's 30-day purge counts from the
     * same column. Trashed rows are the one case `observeNotes` deliberately hides.
     */
    @Query("SELECT * FROM notes WHERE is_trashed = 1 ORDER BY trashed_at DESC")
    abstract fun observeTrashed(): Flow<List<NoteEntity>>

    /**
     * The sidebar's Untagged row — a note with no `note_tags` row at all.
     *
     * A query rather than scanning bodies with `TagScanner`: `note_tags` is already the parsed
     * result of every save, so re-deriving it per emission would both cost more and risk
     * disagreeing with what the tag tree shows.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE is_trashed = 0 AND id NOT IN (SELECT note_id FROM note_tags)
        ORDER BY modified_at DESC
        """,
    )
    abstract fun observeUntagged(): Flow<List<NoteEntity>>

    // --- Search -------------------------------------------------------------

    /**
     * Prefix full-text search over active notes, most-recent first. Each token is turned into an
     * FTS5 prefix term (`duck note` -> `duck* note*`, implicit AND). Query hardening — operators,
     * phrases, snippet highlighting — lands in Phase 10.
     *
     * `notes_fts` is deliberately NOT aliased: MATCH's left side resolves to the hidden column
     * named after the table, which keeps the real table name even under an alias.
     */
    open suspend fun search(term: String): List<NoteEntity> {
        val match = ftsMatch(term)
        if (match.isEmpty()) return emptyList()
        val sql =
            """
            SELECT n.* FROM notes AS n
            JOIN notes_fts ON notes_fts.rowid = n.rowid
            WHERE notes_fts MATCH ? AND n.is_trashed = 0
            ORDER BY n.modified_at DESC
            """.trimIndent()
        // Bind index is 1-based.
        return searchRaw(RoomRawQuery(sql) { statement -> statement.bindText(1, match) })
    }

    @RawQuery
    protected abstract suspend fun searchRaw(query: RoomRawQuery): List<NoteEntity>

    // --- Direct writes ------------------------------------------------------

    @Insert
    abstract suspend fun insert(note: NoteEntity): Long

    /** Bulk insert in a single Room transaction — used to populate large fixtures. */
    @Insert
    abstract suspend fun insertAll(notes: List<NoteEntity>)

    @Update
    abstract suspend fun update(note: NoteEntity)

    @Delete
    abstract suspend fun delete(note: NoteEntity)

    /**
     * Upsert a note and (re)wire its tags in one transaction: replace the note's cross-refs,
     * create any missing tags, then drop tags left orphaned — except pinned or custom-icon tags,
     * whose user customization must survive losing their last note. The AFTER-INSERT/UPDATE
     * triggers on `notes` mirror title+body into `notes_fts` automatically.
     *
     * Insert vs update is decided by an explicit [rowidFor] lookup, not by `@Upsert`: the primary
     * key here is the autogenerated [NoteEntity.rowid] (so the FTS5 `content_rowid` mapping
     * survives `VACUUM`), while callers identify a note by its UUID `id`. Room keys both `@Update`
     * and `@Upsert` off the primary key, so either would insert a duplicate and trip the unique
     * index on `id` instead of updating.
     */
    @Transaction
    open suspend fun saveNoteWithTags(note: NoteEntity, tagNames: List<String>) {
        val existingRowid = rowidFor(note.id)
        if (existingRowid == null) insert(note) else update(note.copy(rowid = existingRowid))
        clearCrossRefs(note.id)
        for (name in tagNames) {
            insertCrossRef(NoteTagCrossRef(noteId = note.id, tagId = ensureTag(name)))
        }
        deleteOrphanTags()
    }

    // --- Transaction building blocks ---------------------------------------

    @Query("SELECT rowid FROM notes WHERE id = :id")
    protected abstract suspend fun rowidFor(id: String): Long?

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
