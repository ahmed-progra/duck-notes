package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeSearchRepository
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.SearchQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchNotesTest {

    private val hit = Note(id = "n1", title = "Kingfisher", body = "diving bird", createdAt = 0L, modifiedAt = 0L)
    private val search = FakeSearchRepository(listOf(hit))
    private val searchNotes = SearchNotes(search)

    @Test
    fun delegatesAQueryAndReturnsItsResults() = runTest {
        val results = searchNotes(SearchQuery(text = "diving"))

        assertEquals(listOf(hit), results)
        assertEquals("diving", search.lastQuery?.text)
    }

    @Test
    fun passesTheTagFilterThrough() = runTest {
        searchNotes(SearchQuery(text = "diving", tagId = "birds"))

        assertEquals("birds", search.lastQuery?.tagId)
    }

    @Test
    fun blankTextShortCircuitsWithoutQuerying() = runTest {
        assertEquals(emptyList<Note>(), searchNotes(SearchQuery(text = "   ")))

        assertNull("an empty query means 'show the list', not 'match all'", search.lastQuery)
    }
}
