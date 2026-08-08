package app.duck.notes.domain.usecase

import app.duck.notes.domain.Clock
import app.duck.notes.domain.markdown.MarkdownFormat
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.repository.AttachmentRepository
import app.duck.notes.domain.repository.AttachmentStorage
import app.duck.notes.domain.repository.NoteRepository
import java.util.UUID
import javax.inject.Inject

/**
 * Attaches a picked image to a note: copies its bytes into durable storage, records the row, and
 * appends the markdown reference — so the strip (rows) and the body (`attachment:<id>` references)
 * agree. That agreement is the invariant [app.duck.notes.domain.usecase] `AttachImageTest` guards.
 *
 * ## Durability, not a stored picker URI
 *
 * The source is the photo picker's `content://`, whose read grant is revocable and gone by the next
 * launch — [AttachmentStorage.store] copies the bytes into `filesDir` and returns a location the app
 * owns. A row is recorded **only if the copy succeeds**; a failed read returns null and writes nothing.
 *
 * ## Best-effort, not atomic — decided on purpose
 *
 * The three steps (store file, insert row, save reference) are **not** wrapped in one transaction, and
 * the order is chosen so the only reachable partial state after a mid-sequence kill is benign: a leaked
 * file with no row (a few KB, the same category as the accepted hard-delete orphan), or a row with no
 * body reference (the image is usable in the strip, merely absent from exported markdown). Neither
 * corrupts a note. Reconciliation is deferred to the Phase 14 cleanup that owns hard-delete orphans;
 * the upgrade path is one Room transaction over the row insert and the note-body write.
 *
 * The reference is built by [MarkdownFormat.appendImage], whose literal comes from `MarkdownScanner` —
 * a `"!["` written in a caller is the third implementation of one syntax, which `MarkdownSyntaxSourceTest`
 * forbids.
 */
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
    ): Attachment? {
        // Note first: no note, nothing to attach to — and it avoids an orphan file for a dead id.
        val note = notes.getNote(noteId) ?: return null
        val id = UUID.randomUUID().toString()
        val durable = storage.store(sourceUri, id) ?: return null

        val attachment = Attachment(
            id = id,
            noteId = noteId,
            uri = durable,
            mime = mime,
            createdAt = clock.nowMillis(),
        )
        attachments.insert(attachment)
        saveNote(noteId, MarkdownFormat.appendImage(note.body, id, alt))
        return attachment
    }
}
