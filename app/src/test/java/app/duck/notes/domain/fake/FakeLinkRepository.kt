package app.duck.notes.domain.fake

import app.duck.notes.domain.model.NoteLink
import app.duck.notes.domain.repository.LinkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class FakeLinkRepository(private val links: List<NoteLink> = emptyList()) : LinkRepository {

    override fun backlinks(noteId: String): Flow<List<NoteLink>> =
        flowOf(links.filter { it.targetId == noteId })
}
