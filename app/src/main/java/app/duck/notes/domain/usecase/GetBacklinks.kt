package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.NoteLink
import app.duck.notes.domain.repository.LinkRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Notes linking *to* this one. Emits empty until Phase 9 starts writing `note_links` rows on save —
 * the query and its plumbing are real, the data isn't there yet.
 */
class GetBacklinks @Inject constructor(
    private val links: LinkRepository,
) {
    operator fun invoke(noteId: String): Flow<List<NoteLink>> = links.backlinks(noteId)
}
