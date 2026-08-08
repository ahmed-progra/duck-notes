package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.repository.AttachmentRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** The current note's attachments, live — what the strip renders. One verb, per Phase 3's rule. */
class ObserveAttachments @Inject constructor(
    private val attachments: AttachmentRepository,
) {
    operator fun invoke(noteId: String): Flow<List<Attachment>> = attachments.observeForNote(noteId)
}
