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
    private val saveNote = SaveNote(notes, DeriveTitle(), ParseTags(), ResolveWikiLinks(), clock)

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
        notes.save(created.copy(isPinned = true, isLocked = true), emptyList(), emptyList())

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

    // --- wiki links (9b) ------------------------------------------------------

    /**
     * The save path hands the write layer the body's link titles, in first-appearance order.
     *
     * Resolution to a target id is a `NoteDao` query against real rows and belongs to a device test;
     * what a use case owes is handing that query the right list, which is what this pins.
     */
    @Test
    fun resolvesWikiLinkTitlesOnEverySave() = runTest {
        val note = saveNote(id = null, body = "see [[Pond]] and [[Duck plans]]")

        assertEquals(listOf("Pond", "Duck plans"), notes.savedLinks[note.id])
    }

    /**
     * **The reason 9a had to land before this method wrote anything.**
     *
     * A `[[link]]` inside an inline code span or a fenced block is not a link, and until 9a the bare
     * regex behind `ResolveWikiLinks` resolved both — writing a `note_links` row for something the
     * editor correctly refuses to paint, i.e. a backlink the user can neither see nor click. This is
     * that defect asserted from the *save* side: `TagAgreementTest` holds the scanner and the editor
     * to agreement, but it cannot know whether `SaveNote` routes through the scanner at all.
     */
    @Test
    fun aWikiLinkInsideCodeIsNotResolved() = runTest {
        val note = saveNote(
            id = null,
            body = "a `[[Masked]]` span\n\n```\n[[Fenced]]\n```\n\nbut [[Real]] counts",
        )

        assertEquals(listOf("Real"), notes.savedLinks[note.id])
    }

    /**
     * Deleting the last link from a body clears the note's rows rather than leaving them behind.
     *
     * The write path clears before re-inserting, so this is really a check that the *empty* list still
     * reaches it — the case where "pass nothing" and "pass what the body says" look identical from
     * the outside and are not.
     */
    @Test
    fun removingTheLastLinkClearsThemOnTheNextSave() = runTest {
        val created = saveNote(id = null, body = "[[Pond]]")
        assertEquals(listOf("Pond"), notes.savedLinks[created.id])

        saveNote(id = created.id, body = "no links any more")

        assertEquals(emptyList<String>(), notes.savedLinks[created.id])
    }
}
