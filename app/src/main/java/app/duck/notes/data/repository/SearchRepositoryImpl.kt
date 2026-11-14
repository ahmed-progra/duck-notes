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
        var matches = if (query.text.isBlank()) notes.observeNotes().first() else notes.search(query.text)
        query.tagId?.let { id ->
            val tagged = notes.notesByTag(id).first().mapTo(mutableSetOf()) { it.id }
            matches = matches.filter { it.id in tagged }
        }
        query.filters.tagPath?.let { path ->
            val tagged = notes.notesByTagPathOnce(path).mapTo(mutableSetOf()) { it.id }
            matches = matches.filter { it.id in tagged }
        }
        val filters = query.filters
        if (filters.pinnedOnly) matches = matches.filter { it.isPinned }
        if (filters.openTasksOnly) matches = matches.filter { app.duck.notes.domain.markdown.MarkdownScanner.hasOpenTask(it.body) }
        if (filters.imagesOnly) {
            val images = notes.noteIdsWithImages().toSet()
            matches = matches.filter { it.id in images }
        }
        matches = when (filters.scope) {
            app.duck.notes.domain.model.SearchScope.Both -> matches
            app.duck.notes.domain.model.SearchScope.Active -> matches.filterNot { it.isArchived }
            app.duck.notes.domain.model.SearchScope.Archive -> matches.filter { it.isArchived }
        }
        return matches.map { it.toDomain() }
    }
}
