package app.duck.notes.domain.usecase

import app.duck.notes.domain.Clock
import app.duck.notes.domain.repository.NoteRepository
import javax.inject.Inject

class ArchiveNote @Inject constructor(
    private val notes: NoteRepository,
    private val parseTags: ParseTags,
    private val resolveWikiLinks: ResolveWikiLinks,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: String, archived: Boolean) {
        val note = notes.getNote(id) ?: return
        if (note.isTrashed || note.isArchived == archived) return
        notes.save(note.copy(isArchived = archived, modifiedAt = clock.nowMillis()), parseTags(note.body), resolveWikiLinks(note.body))
    }
}
