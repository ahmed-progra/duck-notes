package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.model.Note
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObserveNoteTest {

    private val notes = FakeNoteRepository()
    private val observeNote = ObserveNote(notes)

    private val stored = Note(
        id = "n1",
        title = "Kingfisher",
        body = "a small diving bird",
        createdAt = 0L,
        modifiedAt = 0L,
    )

    @Test
    fun emitsTheStoredNote() = runTest {
        notes.seed(stored)

        assertEquals(stored, observeNote("n1").first())
    }

    @Test
    fun emitsNullForAnUnknownId() = runTest {
        notes.seed(stored)

        assertNull(observeNote("missing").first())
    }

    @Test
    fun emitsTrashedNotesToo() = runTest {
        notes.seed(stored.copy(isTrashed = true))

        assertEquals("the editor still needs to read a trashed note", "n1", observeNote("n1").first()?.id)
    }
}
