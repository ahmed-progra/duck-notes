package app.duck.notes.data.repository

import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.mapper.toDomain
import app.duck.notes.data.mapper.toEntity
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.repository.NoteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class NoteRepositoryImpl @Inject constructor(
    private val notes: NoteDao,
) : NoteRepository {

    override fun observeNotes(): Flow<List<Note>> =
        notes.observeNotes().map { rows -> rows.map { it.toDomain() } }

    override fun observeNotesByTagPath(path: String): Flow<List<Note>> =
        notes.notesByTagPath(path).map { rows -> rows.map { it.toDomain() } }

    override fun observeTrashed(): Flow<List<Note>> =
        notes.observeTrashed().map { rows -> rows.map { it.toDomain() } }

    override fun observeUntagged(): Flow<List<Note>> =
        notes.observeUntagged().map { rows -> rows.map { it.toDomain() } }

    override fun observeNote(id: String): Flow<Note?> =
        notes.observeNote(id).map { it?.toDomain() }

    override fun observeTitles(excludeId: String?): Flow<List<String>> = notes.observeTitles(excludeId)

    override suspend fun getNote(id: String): Note? = notes.getById(id)?.toDomain()

    override suspend fun noteIdByTitle(title: String): String? = notes.noteIdByTitle(title)

    override suspend fun save(note: Note, tagNames: List<String>, linkTitles: List<String>) =
        notes.saveNoteWithTagsAndLinks(note.toEntity(), tagNames, linkTitles)

    override suspend fun saveWithAttachment(
        note: Note,
        tagNames: List<String>,
        linkTitles: List<String>,
        attachment: Attachment,
    ) = notes.saveNoteWithAttachment(note.toEntity(), tagNames, linkTitles, attachment.toEntity())

    override suspend fun saveWithoutAttachment(
        note: Note,
        tagNames: List<String>,
        linkTitles: List<String>,
        attachmentId: String,
    ) = notes.saveNoteWithoutAttachment(note.toEntity(), tagNames, linkTitles, attachmentId)
}
