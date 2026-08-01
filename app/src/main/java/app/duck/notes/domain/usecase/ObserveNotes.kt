package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Active notes, newest first.
 *
 * [untagged], [trashed] and [byTagPath] are the views the sidebar needs that a predicate over
 * [invoke] cannot give: `observeNotes` hides trashed rows by contract, and tag membership lives in a
 * join table the domain model does not carry. Everything else the sidebar filters — archived,
 * locked, modified today, has an open task — is a field or a body scan, and stays in the ViewModel
 * where it belongs.
 */
class ObserveNotes @Inject constructor(
    private val notes: NoteRepository,
) {
    operator fun invoke(): Flow<List<Note>> = notes.observeNotes()

    /**
     * Notes under a tag **and its descendants** — Phase 2 owed this and Phase 7 pays it.
     *
     * `#projects` finds notes tagged `#projects/duck/ui`, because those are separate rows whose names
     * share a prefix and a parent row that could not find its children's notes would be lying about
     * what it filters. The count beside the row counts the same set, for the same reason.
     */
    fun byTagPath(path: String): Flow<List<Note>> = notes.observeNotesByTagPath(path)

    fun untagged(): Flow<List<Note>> = notes.observeUntagged()

    fun trashed(): Flow<List<Note>> = notes.observeTrashed()
}
