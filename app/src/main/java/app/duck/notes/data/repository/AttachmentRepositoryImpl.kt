package app.duck.notes.data.repository

import app.duck.notes.data.local.dao.AttachmentDao
import app.duck.notes.data.mapper.toDomain
import app.duck.notes.data.mapper.toEntity
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.repository.AttachmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AttachmentRepositoryImpl @Inject constructor(
    private val dao: AttachmentDao,
) : AttachmentRepository {

    override fun observeForNote(noteId: String): Flow<List<Attachment>> =
        dao.observeForNote(noteId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun forNote(noteId: String): List<Attachment> =
        dao.forNote(noteId).map { it.toDomain() }

    override suspend fun get(id: String): Attachment? = dao.getById(id)?.toDomain()

    override suspend fun insert(attachment: Attachment) = dao.insert(attachment.toEntity())

    override suspend fun delete(id: String) = dao.deleteById(id)
}
