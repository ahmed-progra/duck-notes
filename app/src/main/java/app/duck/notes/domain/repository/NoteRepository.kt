package app.duck.notes.domain.repository

import app.duck.notes.domain.model.Note
import kotlinx.coroutines.flow.Flow

/**
 * Note storage. [save] is the single write path — insert and update alike — so tag rewiring and
 * search-index upkeep can never diverge between two code paths.
 */
interface NoteRepository {

    /** Active (non-trashed) notes, newest first. [tagId] null means "all". */
    fun observeNotes(tagId: String? = null): Flow<List<Note>>

    /** Trashed notes, most recently trashed first. The one view [observeNotes] excludes. */
    fun observeTrashed(): Flow<List<Note>>

    /** Active notes carrying no tag at all, newest first. */
    fun observeUntagged(): Flow<List<Note>>

    fun observeNote(id: String): Flow<Note?>

    suspend fun getNote(id: String): Note?

    /** Upsert by [Note.id], replacing the note's tags with [tagNames]. */
    suspend fun save(note: Note, tagNames: List<String>)
}
