package app.duck.notes.domain.repository

import app.duck.notes.domain.model.NoteLink
import kotlinx.coroutines.flow.Flow

/**
 * `[[WikiLink]]` reads.
 *
 * Read-only by design, not by omission: the rows are written inside the note's own save transaction
 * (`NoteDao.saveNoteWithTags`), because they are derived from the body and must land or fail with
 * it. A second write path here would be a second answer to "what does this note link to".
 */
interface LinkRepository {
    /** Notes that link *to* [noteId], newest first. Sources in the trash are excluded. */
    fun backlinks(noteId: String): Flow<List<NoteLink>>
}
