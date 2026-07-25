package app.duck.notes.domain.fake

import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.SearchQuery
import app.duck.notes.domain.repository.SearchRepository

class FakeSearchRepository(private val results: List<Note> = emptyList()) : SearchRepository {

    /** Null until [search] is called — lets a test prove a query was short-circuited. */
    var lastQuery: SearchQuery? = null
        private set

    override suspend fun search(query: SearchQuery): List<Note> {
        lastQuery = query
        return results
    }
}
