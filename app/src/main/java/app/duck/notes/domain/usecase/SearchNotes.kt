package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.SearchQuery
import app.duck.notes.domain.repository.SearchRepository
import javax.inject.Inject

/** Blank unfiltered text returns to the sidebar; filters can search without text. */
class SearchNotes @Inject constructor(
    private val search: SearchRepository,
) {
    suspend operator fun invoke(query: SearchQuery): List<Note> =
        if (query.text.isBlank() && query.tagId == null && query.filters == app.duck.notes.domain.model.SearchFilters()) emptyList() else search.search(query)
}
