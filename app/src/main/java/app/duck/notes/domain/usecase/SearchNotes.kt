package app.duck.notes.domain.usecase

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.SearchQuery
import app.duck.notes.domain.repository.SearchRepository
import javax.inject.Inject

/** Full-text search. Blank text short-circuits — an empty query means "show the list", not "match all". */
class SearchNotes @Inject constructor(
    private val search: SearchRepository,
) {
    suspend operator fun invoke(query: SearchQuery): List<Note> =
        if (query.text.isBlank()) emptyList() else search.search(query)
}
