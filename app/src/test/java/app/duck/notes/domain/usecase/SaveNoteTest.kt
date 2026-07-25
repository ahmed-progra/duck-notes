package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FixedClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveNoteTest {

    private val notes = FakeNoteRepository()
    private val clock = FixedClock(now = 1_000L)
    private val saveNote = SaveNote(notes, DeriveTitle(), ParseTags(), clock)

    @Test
    fun createsNoteWithDerivedTitleAndParsedTags() = runTest {
        val note = saveNote(id = null, body = "# Duck plans\n\nFeed the #duck at #home")

        assertEquals("Duck plans", note.title)
        assertEquals(listOf("duck", "home"), notes.savedTags[note.id])
        assertEquals(1_000L, note.createdAt)
        assertEquals(1_000L, note.modifiedAt)
    }

    @Test
    fun generatesAnIdWhenNoneGiven() = runTest {
        val first = saveNote(id = null, body = "one")
        val second = saveNote(id = null, body = "two")

        assertTrue(first.id.isNotEmpty())
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun updatePreservesCreatedAtAndRestampsModifiedAt() = runTest {
        val created = saveNote(id = null, body = "original")
        clock.now = 5_000L

        val updated = saveNote(id = created.id, body = "revised")

        assertEquals(created.id, updated.id)
        assertEquals("revised", updated.title)
        assertEquals("createdAt must survive an edit", 1_000L, updated.createdAt)
        assertEquals(5_000L, updated.modifiedAt)
    }

    @Test
    fun updatePreservesUserFlags() = runTest {
        val created = saveNote(id = null, body = "original")
        notes.save(created.copy(isPinned = true, isLocked = true), emptyList())

        val updated = saveNote(id = created.id, body = "revised")

        assertTrue("pin must survive an edit", updated.isPinned)
        assertTrue("lock must survive an edit", updated.isLocked)
    }

    @Test
    fun retagsOnEveryEdit() = runTest {
        val created = saveNote(id = null, body = "#before")
        assertEquals(listOf("before"), notes.savedTags[created.id])

        saveNote(id = created.id, body = "#after")

        assertEquals(listOf("after"), notes.savedTags[created.id])
    }

    @Test
    fun emptyBodyIsUntitledWithNoTags() = runTest {
        val note = saveNote(id = null, body = "")

        assertEquals("Untitled", note.title)
        assertEquals(emptyList<String>(), notes.savedTags[note.id])
    }

    @Test
    fun anUnknownIdIsCreatedWithThatId() = runTest {
        val note = saveNote(id = "chosen-id", body = "body")

        assertEquals("chosen-id", note.id)
        assertEquals(note, notes.current("chosen-id"))
    }
}
