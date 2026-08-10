package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.NoteLink
import app.duck.notes.domain.repository.LinkRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Notes linking *to* this one, newest first, with the linking note's current title.
 *
 * Sources in the trash are excluded by the query rather than here — see `NoteLinkDao.observeBacklinks`
 * for why the filter is on the source only and why trash deletes no rows.
 */
class GetBacklinks @Inject constructor(
    private val links: LinkRepository,
) {
    operator fun invoke(noteId: String): Flow<List<NoteLink>> = links.backlinks(noteId)
}
