package app.duck.notes.domain.usecase

import app.duck.notes.domain.Clock
import app.duck.notes.domain.repository.NoteRepository
import javax.inject.Inject

/**
 * Moves a note to the trash. Soft delete — the row survives so `RestoreNote` can undo it and the
 * 30-day auto-purge (Phase 14) can find it. Re-trashing an already-trashed note is a no-op, so the
 * original `trashedAt` keeps counting down.
 */
class DeleteNote @Inject constructor(
    private val notes: NoteRepository,
    private val parseTags: ParseTags,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String) {
        val note = notes.getNote(id) ?: return
        if (note.isTrashed) return
        val now = clock.nowMillis()
        notes.save(
            note.copy(isTrashed = true, trashedAt = now, modifiedAt = now),
            parseTags(note.body),
        )
    }
}
