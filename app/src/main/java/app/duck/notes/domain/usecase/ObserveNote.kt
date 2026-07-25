package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** One note by id. Emits null once the note is gone, so the editor can react to a hard delete. */
class ObserveNote @Inject constructor(
    private val notes: NoteRepository,
) {
    operator fun invoke(id: String): Flow<Note?> = notes.observeNote(id)
}
