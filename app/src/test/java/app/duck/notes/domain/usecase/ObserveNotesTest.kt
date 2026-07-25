package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.model.Note
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveNotesTest {

    private val notes = FakeNoteRepository()
    private val observeNotes = ObserveNotes(notes)

    private fun note(id: String, modifiedAt: Long, trashed: Boolean = false) =
        Note(id = id, title = id, body = id, createdAt = 0L, modifiedAt = modifiedAt, isTrashed = trashed)

    @Test
    fun newestFirst() = runTest {
        notes.seed(note("old", modifiedAt = 1L))
        notes.seed(note("new", modifiedAt = 9L))
        notes.seed(note("mid", modifiedAt = 5L))

        assertEquals(listOf("new", "mid", "old"), observeNotes().first().map { it.id })
    }

    @Test
    fun trashedNotesAreExcluded() = runTest {
        notes.seed(note("kept", modifiedAt = 1L))
        notes.seed(note("gone", modifiedAt = 2L, trashed = true))

        assertEquals(listOf("kept"), observeNotes().first().map { it.id })
    }

    @Test
    fun filtersByTag() = runTest {
        notes.seed(note("work-note", modifiedAt = 1L), tags = listOf("work"))
        notes.seed(note("home-note", modifiedAt = 2L), tags = listOf("home"))

        assertEquals(listOf("work-note"), observeNotes("work").first().map { it.id })
    }

    @Test
    fun nullTagMeansEveryNote() = runTest {
        notes.seed(note("a", modifiedAt = 1L), tags = listOf("work"))
        notes.seed(note("b", modifiedAt = 2L), tags = emptyList())

        assertEquals(2, observeNotes(null).first().size)
    }

    @Test
    fun emptyWhenNothingStored() = runTest {
        assertEquals(emptyList<Note>(), observeNotes().first())
    }
}
