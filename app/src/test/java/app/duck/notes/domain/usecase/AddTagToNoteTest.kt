package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FixedClock
import app.duck.notes.domain.model.Note
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the tag lands, and what happens when it should not land at all.
 *
 * This writes into the user's prose from a control that looks like metadata, which is why both
 * questions are pinned here rather than left to the implementation.
 */
class AddTagToNoteTest {

    private val notes = FakeNoteRepository()
    private val clock = FixedClock(1_000L)
    private val parseTags = ParseTags()
    private val addTag = AddTagToNote(
        notes = notes,
        parseTags = parseTags,
        saveNote = SaveNote(notes, DeriveTitle(), parseTags, clock),
    )

    private suspend fun seed(id: String, body: String) =
        notes.seed(Note(id = id, title = "", body = body, createdAt = 0, modifiedAt = 0))

    private fun bodyOf(id: String) = notes.current(id)?.body

    @Test
    fun `appends on its own line at the end`() = runTest {
        seed("n1", "buy milk")
        assertEquals(AddTagResult.Added, addTag("n1", "todo"))
        assertEquals("buy milk\n#todo", bodyOf("n1"))
    }

    @Test
    fun `an empty body gains no leading newline`() = runTest {
        seed("n1", "")
        addTag("n1", "todo")
        assertEquals("#todo", bodyOf("n1"))
    }

    /** `trimEnd` touches only end-of-file whitespace, which markdown carries no meaning in. */
    @Test
    fun `trailing blank lines do not become a ragged gap`() = runTest {
        seed("n1", "buy milk\n\n\n")
        addTag("n1", "todo")
        assertEquals("buy milk\n#todo", bodyOf("n1"))
    }

    @Test
    fun `repeated adds stack one per line`() = runTest {
        seed("n1", "buy milk")
        addTag("n1", "todo")
        addTag("n1", "shopping")
        assertEquals("buy milk\n#todo\n#shopping", bodyOf("n1"))
    }

    @Test
    fun `a multi word tag is written closed`() = runTest {
        seed("n1", "note")
        addTag("n1", "day job")
        assertEquals("note\n#day job#", bodyOf("n1"))
        assertEquals(listOf("day job"), parseTags(bodyOf("n1").orEmpty()))
    }

    /**
     * The visible-corruption case. `saveNoteWithTags` inserts cross-refs with IGNORE and `ParseTags`
     * dedupes, so a second `#work` in the body would leave the row single and the text doubled — a
     * duplicate the database cannot see and the user cannot miss.
     */
    @Test
    fun `a tag already present is a no-op and does not double in the body`() = runTest {
        seed("n1", "buy milk #todo")
        assertEquals(AddTagResult.AlreadyPresent, addTag("n1", "todo"))
        assertEquals("buy milk #todo", bodyOf("n1"))
    }

    /** Exact name, not prefix: `#projects` is a different tag from `#projects/duck`. */
    @Test
    fun `a parent of a present tag is not already present`() = runTest {
        seed("n1", "note #projects/duck")
        assertEquals(AddTagResult.Added, addTag("n1", "projects"))
        assertEquals("note #projects/duck\n#projects", bodyOf("n1"))
    }

    /**
     * The silent one. A body ending inside an unclosed fence swallows the appended tag — the scanner
     * yields no span, `ParseTags` returns nothing, and without the re-parse the menu item would do
     * nothing at all with no error.
     */
    @Test
    fun `a body ending in an unclosed fence reports failure and is not written`() = runTest {
        seed("n1", "note\n\n```\nfun x() {}")
        assertEquals(AddTagResult.NotWritable, addTag("n1", "todo"))
        assertEquals("note\n\n```\nfun x() {}", bodyOf("n1"))
    }

    @Test
    fun `a closed fence does not block the append`() = runTest {
        seed("n1", "note\n\n```\nfun x() {}\n```")
        assertEquals(AddTagResult.Added, addTag("n1", "todo"))
        assertEquals(listOf("todo"), parseTags(bodyOf("n1").orEmpty()))
    }

    @Test
    fun `an unknown note is not writable`() = runTest {
        assertEquals(AddTagResult.NotWritable, addTag("nope", "todo"))
    }

    /** The point of going through `SaveNote`: `note_tags` is re-derived, not appended to. */
    @Test
    fun `the tag reaches note_tags`() = runTest {
        seed("n1", "buy milk")
        addTag("n1", "todo")
        assertEquals(listOf("todo"), notes.savedTags["n1"])
    }
}
