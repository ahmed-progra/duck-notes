package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeSearchRepository
import app.duck.notes.domain.model.SearchFilters
import app.duck.notes.domain.model.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.test.runTest

class FilteredSearchTest {
    /** Plant: short-circuit all blank text; filter-only searches must still reach the repository. */
    @Test fun filtersWorkWithoutTextAndClearingAllFiltersReturnsToTheSidebar() = runTest {
        val repository = FakeSearchRepository()
        val search = SearchNotes(repository)
        search(SearchQuery(""))
        assertNull(repository.lastQuery)
        val query = SearchQuery("", filters = SearchFilters(pinnedOnly = true))
        search(query)
        assertEquals(query, repository.lastQuery)
    }
}
