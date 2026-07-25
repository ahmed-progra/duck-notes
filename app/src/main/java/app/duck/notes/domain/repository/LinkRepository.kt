package app.duck.notes.domain.repository

import app.duck.notes.domain.model.NoteLink
import kotlinx.coroutines.flow.Flow

/**
 * `[[WikiLink]]` storage. Read-only this phase: Phase 9 owns resolving links on save and writing
 * the rows, so [backlinks] returns empty until then.
 */
interface LinkRepository {
    /** Notes that link *to* [noteId]. */
    fun backlinks(noteId: String): Flow<List<NoteLink>>
}
