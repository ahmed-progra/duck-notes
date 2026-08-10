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
    private val resolveWikiLinks: ResolveWikiLinks,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String) {
        val note = notes.getNote(id) ?: return
        if (note.isTrashed) return
        val now = clock.nowMillis()
        // Same body in, same tags and links out — trashing rewrites the note's derived rows to
        // exactly what they already were. **That is the point:** a trashed note still holds
        // `[[Pond]]` in its body, so its rows stay valid and a restore has nothing to rebuild. The
        // backlink query hides them by filtering on the source's `is_trashed`, which is the same
        // call `NoteDao.notesByTagPathOnce` made for tags — the filter hides, the rewrite must not.
        // Passing nothing here would delete them instead, which is why `save` refuses a default.
        notes.save(
            note.copy(isTrashed = true, trashedAt = now, modifiedAt = now),
            parseTags(note.body),
            resolveWikiLinks(note.body),
        )
    }
}
