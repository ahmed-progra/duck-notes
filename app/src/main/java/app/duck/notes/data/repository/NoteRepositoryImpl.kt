package app.duck.notes.data.repository

import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.mapper.toDomain
import app.duck.notes.data.mapper.toEntity
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class NoteRepositoryImpl @Inject constructor(
    private val notes: NoteDao,
) : NoteRepository {

    override fun observeNotes(tagId: String?): Flow<List<Note>> =
        (if (tagId == null) notes.observeNotes() else notes.notesByTag(tagId))
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeNote(id: String): Flow<Note?> =
        notes.observeNote(id).map { it?.toDomain() }

    override suspend fun getNote(id: String): Note? = notes.getById(id)?.toDomain()

    override suspend fun save(note: Note, tagNames: List<String>) =
        notes.saveNoteWithTags(note.toEntity(), tagNames)
}
