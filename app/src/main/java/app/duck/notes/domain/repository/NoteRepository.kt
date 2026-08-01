package app.duck.notes.domain.repository

import app.duck.notes.domain.model.Note
import kotlinx.coroutines.flow.Flow

/**
 * Note storage. [save] is the single write path — insert and update alike — so tag rewiring and
 * search-index upkeep can never diverge between two code paths.
 */
interface NoteRepository {

    /** Active (non-trashed) notes, newest first. */
    fun observeNotes(): Flow<List<Note>>

    /**
     * Active notes under [path] **and its descendants** — `projects` finds `projects/duck/ui`.
     *
     * Keyed by path rather than by tag id, which is what makes the subtree query possible at all: an
     * id says nothing about ancestry, while the path is both what the user typed and what the
     * sidebar row is keyed by. It also means a filter no longer has to resolve through the tag tree
     * to reach a query.
     */
    fun observeNotesByTagPath(path: String): Flow<List<Note>>

    /** Trashed notes, most recently trashed first. The one view [observeNotes] excludes. */
    fun observeTrashed(): Flow<List<Note>>

    /** Active notes carrying no tag at all, newest first. */
    fun observeUntagged(): Flow<List<Note>>

    fun observeNote(id: String): Flow<Note?>

    suspend fun getNote(id: String): Note?

    /** Upsert by [Note.id], replacing the note's tags with [tagNames]. */
    suspend fun save(note: Note, tagNames: List<String>)
}
