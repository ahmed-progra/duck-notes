package app.duck.notes.domain.usecase

import app.duck.notes.domain.repository.AttachmentRepository
import app.duck.notes.domain.repository.AttachmentStorage
import javax.inject.Inject
import app.duck.notes.domain.repository.AttachmentWorkLock
import kotlinx.coroutines.sync.withLock

/** Retry explicit delete intents only; scanning unreferenced files would race incoming imports. */
class RecoverAttachmentDeletions @Inject constructor(
    private val attachments: AttachmentRepository,
    private val storage: AttachmentStorage,
) {
    suspend operator fun invoke() = AttachmentWorkLock.mutex.withLock {
        for (key in storage.pendingDeletions()) {
            if (attachments.get(key.substringAfterLast('/')) == null) storage.delete(key)
        }
    }
}
