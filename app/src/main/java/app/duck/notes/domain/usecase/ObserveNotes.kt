package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Active notes, newest first. [invoke]'s [tagId] null means every tag.
 *
 * [untagged] and [trashed] are the two views the sidebar needs that a predicate over [invoke] cannot
 * give: `observeNotes` hides trashed rows by contract, and tag membership lives in a join table the
 * domain model does not carry. Everything else the sidebar filters — archived, locked, modified
 * today, has an open task — is a field or a body scan, and stays in the ViewModel where it belongs.
 */
class ObserveNotes @Inject constructor(
    private val notes: NoteRepository,
) {
    operator fun invoke(tagId: String? = null): Flow<List<Note>> = notes.observeNotes(tagId)

    fun untagged(): Flow<List<Note>> = notes.observeUntagged()

    fun trashed(): Flow<List<Note>> = notes.observeTrashed()
}
