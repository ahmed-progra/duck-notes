package app.duck.notes.domain.fake

import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.repository.AttachmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [AttachmentRepository]. Filters by note and orders oldest-first by `createdAt`, mirroring
 * `AttachmentDao`'s `ORDER BY created_at ASC` — so a use-case test sees the same order the strip does.
 */
class FakeAttachmentRepository : AttachmentRepository {

    private val rows = MutableStateFlow<List<Attachment>>(emptyList())

    /** Every stored row, for assertions that do not care which note. */
    fun all(): List<Attachment> = rows.value

    override fun observeForNote(noteId: String): Flow<List<Attachment>> = rows.map { list ->
        list.filter { it.noteId == noteId }.sortedBy { it.createdAt }
    }

    override suspend fun forNote(noteId: String): List<Attachment> =
        rows.value.filter { it.noteId == noteId }.sortedBy { it.createdAt }

    override suspend fun get(id: String): Attachment? = rows.value.find { it.id == id }

    override suspend fun insert(attachment: Attachment) {
        rows.value = rows.value.filterNot { it.id == attachment.id } + attachment
    }

    override suspend fun delete(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}
