package app.duck.notes.domain.repository

import app.duck.notes.domain.model.Attachment
import kotlinx.coroutines.flow.Flow

/**
 * Attachment rows. The strip observes [observeForNote]; the body's `attachment:<id>` references are
 * the durable/export copy, and the two must agree — that invariant is what `AttachImage` upholds and
 * `AttachImageTest` guards.
 */
interface AttachmentRepository {

    /** The current note's attachments, oldest first, live. */
    fun observeForNote(noteId: String): Flow<List<Attachment>>

    /** One-shot read of a note's attachments — the strip-vs-body agreement check reads this. */
    suspend fun forNote(noteId: String): List<Attachment>

    suspend fun get(id: String): Attachment?

    suspend fun insert(attachment: Attachment)

    suspend fun delete(id: String)
}
