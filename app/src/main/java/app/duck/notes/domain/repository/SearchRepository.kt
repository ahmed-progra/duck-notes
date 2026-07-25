package app.duck.notes.domain.repository

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.SearchQuery

/** Full-text search. One-shot rather than a Flow — the caller debounces and re-queries. */
interface SearchRepository {
    suspend fun search(query: SearchQuery): List<Note>
}
