package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Active notes, newest first. [tagId] null means every tag. */
class ObserveNotes @Inject constructor(
    private val notes: NoteRepository,
) {
    operator fun invoke(tagId: String? = null): Flow<List<Note>> = notes.observeNotes(tagId)
}
