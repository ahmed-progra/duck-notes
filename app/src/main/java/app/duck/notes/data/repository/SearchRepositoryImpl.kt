package app.duck.notes.data.repository

import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.mapper.toDomain
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.SearchQuery
import app.duck.notes.domain.repository.SearchRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class SearchRepositoryImpl @Inject constructor(
    private val notes: NoteDao,
) : SearchRepository {

    /**
     * FTS match first, then narrow by tag. The tag pass is a second query intersected in memory
     * rather than a join, because `notes_fts` MATCH has to stay the driving table for the index to
     * be used. Result counts are small — the FTS side has already filtered.
     */
    override suspend fun search(query: SearchQuery): List<Note> {
        val matches = notes.search(query.text)
        val tagId = query.tagId ?: return matches.map { it.toDomain() }
        val tagged = notes.notesByTag(tagId).first().mapTo(mutableSetOf()) { it.id }
        return matches.filter { it.id in tagged }.map { it.toDomain() }
    }
}
