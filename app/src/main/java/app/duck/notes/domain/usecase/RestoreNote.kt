package app.duck.notes.domain.usecase

import app.duck.notes.domain.Clock
import app.duck.notes.domain.repository.NoteRepository
import javax.inject.Inject

/** Brings a note back out of the trash. Restoring an active note is a no-op. */
class RestoreNote @Inject constructor(
    private val notes: NoteRepository,
    private val parseTags: ParseTags,
    private val resolveWikiLinks: ResolveWikiLinks,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String) {
        val note = notes.getNote(id) ?: return
        if (!note.isTrashed) return
        // The mirror of `DeleteNote`: the body never changed, so the rows are rewritten to what they
        // already were. Restore has nothing to rebuild precisely because trash deleted nothing.
        notes.save(
            note.copy(isTrashed = false, trashedAt = null, modifiedAt = clock.nowMillis()),
            parseTags(note.body),
            resolveWikiLinks(note.body),
        )
    }
}
