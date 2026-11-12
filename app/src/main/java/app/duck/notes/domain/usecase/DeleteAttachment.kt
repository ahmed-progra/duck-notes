package app.duck.notes.domain.usecase

import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.repository.AttachmentRepository
import app.duck.notes.domain.repository.AttachmentStorage
import app.duck.notes.domain.repository.NoteRepository
import javax.inject.Inject

/** Commit row/reference removal before deleting bytes, so a database failure cannot break an image. */
class DeleteAttachment @Inject constructor(
    private val storage: AttachmentStorage,
    private val attachments: AttachmentRepository,
    private val notes: NoteRepository,
    private val saveNote: SaveNote,
) {
    suspend operator fun invoke(id: String, expectedNoteId: String? = null) {
        val row = attachments.get(id) ?: return
        if (expectedNoteId != null && row.noteId != expectedNoteId) return
        val note = notes.getNote(row.noteId) ?: return
        if (note.isTrashed) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            storage.markForDeletion(row.uri)
            saveNote(row.noteId, MarkdownScanner.removeImageRef(note.body, id), deletedAttachmentId = id)
            storage.delete(row.uri)
        }
    }
}
