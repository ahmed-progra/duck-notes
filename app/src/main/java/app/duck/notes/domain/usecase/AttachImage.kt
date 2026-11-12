package app.duck.notes.domain.usecase

import app.duck.notes.domain.Clock
import app.duck.notes.domain.markdown.MarkdownFormat
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.repository.AttachmentRepository
import app.duck.notes.domain.repository.AttachmentStorage
import app.duck.notes.domain.repository.NoteRepository
import java.util.UUID
import javax.inject.Inject

/** Copies validated bytes first, then commits the reference and row in one database transaction. */
class AttachImage @Inject constructor(
    private val storage: AttachmentStorage,
    private val attachments: AttachmentRepository,
    private val notes: NoteRepository,
    private val saveNote: SaveNote,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        noteId: String,
        sourceUri: String,
        mime: String,
        alt: String = "",
        body: (() -> String)? = null,
    ): Attachment? {
        if (notes.getNote(noteId)?.isTrashed != false) return null
        val id = UUID.randomUUID().toString()
        val durable = storage.store(sourceUri, id) ?: return null
        val attachment = Attachment(id = id, noteId = noteId, uri = durable, mime = mime, createdAt = clock.nowMillis())
        try {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                val note = notes.getNote(noteId)
                if (note == null || note.isTrashed) {
                    storage.delete(durable)
                    return@withContext null
                }
                saveNote(noteId, MarkdownFormat.appendImage(body?.invoke() ?: note.body, id, alt), attachment = attachment)
                attachment
            }
        } catch (failure: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                // Cancellation can arrive after commit; a referenced file must survive that boundary.
                if (attachments.get(id) == null) storage.delete(durable)
            }
            throw failure
        }
    }
}
