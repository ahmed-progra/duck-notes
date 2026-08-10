package app.duck.notes.data.repository

import app.duck.notes.data.local.dao.NoteLinkDao
import app.duck.notes.data.mapper.toDomain
import app.duck.notes.domain.model.NoteLink
import app.duck.notes.domain.repository.LinkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class LinkRepositoryImpl @Inject constructor(
    private val links: NoteLinkDao,
) : LinkRepository {

    override fun backlinks(noteId: String): Flow<List<NoteLink>> =
        links.observeBacklinks(noteId).map { rows -> rows.map { it.toDomain() } }

}
