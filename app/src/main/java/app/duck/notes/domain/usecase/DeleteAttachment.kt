package app.duck.notes.domain.usecase

import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.repository.AttachmentRepository
import app.duck.notes.domain.repository.AttachmentStorage
import app.duck.notes.domain.repository.NoteRepository
import javax.inject.Inject

/**
 * Removes an attachment completely: its file, its row, and its body reference — so nothing is left
 * orphaned and the strip-vs-body agreement holds after a delete as it does after an attach.
 *
 * **Both the file and the row, never one.** A row without a file is a broken thumbnail; a file without
 * a row is a leak the strip cannot reach to clean. `DeleteAttachmentTest` plants each half and expects
 * red. The body reference goes through `MarkdownScanner.removeImageRef`, the mirror of the append.
 *
 * A missing row is a no-op — the same idempotence `DeleteNote` has, so a double-tap on delete cannot
 * throw.
 */
class DeleteAttachment @Inject constructor(
    private val storage: AttachmentStorage,
    private val attachments: AttachmentRepository,
    private val notes: NoteRepository,
    private val saveNote: SaveNote,
) {
    suspend operator fun invoke(id: String) {
        val row = attachments.get(id) ?: return
        storage.delete(row.uri)
        attachments.delete(id)
        val note = notes.getNote(row.noteId) ?: return
        saveNote(row.noteId, MarkdownScanner.removeImageRef(note.body, id))
    }
}
