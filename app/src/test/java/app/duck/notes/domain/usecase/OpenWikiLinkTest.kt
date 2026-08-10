package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FixedClock
import app.duck.notes.domain.model.Note
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following a tapped `[[WikiLink]]` — resolve, or create and relink — Phase 9d.
 *
 * The half of create-on-tap that has nothing to do with layout, and therefore nothing to do with an
 * emulator. Where the tap *lands* is `WikiLinkTargetTest` (the offsets) and `WikiLinkTapUiTest` (the
 * screen); what happens once it has landed is here.
 */
class OpenWikiLinkTest {

    private val notes = FakeNoteRepository()
    private val clock = FixedClock(now = 1_000L)
    private val saveNote = SaveNote(notes, DeriveTitle(), ParseTags(), ResolveWikiLinks(), clock)
    private val openWikiLink = OpenWikiLink(notes, saveNote)

    private fun note(id: String, title: String, modifiedAt: Long = 1_000L) = Note(
        id = id,
        title = title,
        body = title,
        createdAt = 0L,
        modifiedAt = modifiedAt,
    )

    // --- resolve --------------------------------------------------------------

    @Test
    fun anExistingTitleResolvesToThatNote() = runTest {
        notes.seed(note("target", "Pond"))
        notes.seed(note("source", "Journal"))

        assertEquals("target", openWikiLink("Pond", sourceId = "source", sourceBody = "[[Pond]]"))
    }

    /** Resolving writes nothing at all — following a link is a read. */
    @Test
    fun resolvingWritesNothing() = runTest {
        notes.seed(note("target", "Pond"))
        val before = notes.writes.size

        openWikiLink("Pond", sourceId = "source", sourceBody = "[[Pond]]")

        assertEquals(before, notes.writes.size)
    }

    /**
     * Two notes sharing a title is the **navigate** case, not a second creation, and it resolves the
     * way `NoteDao.noteIdByTitle` resolves: most recently modified.
     *
     * That agreement is the point of tapping through the same query the save path links by — the note
     * a tap opens is the note the next save writes a `note_links` row to.
     */
    @Test
    fun aDuplicateTitleResolvesToTheMostRecentlyModifiedNote() = runTest {
        notes.seed(note("older", "Pond", modifiedAt = 1_000L))
        notes.seed(note("newer", "Pond", modifiedAt = 9_000L))

        assertEquals("newer", openWikiLink("Pond", sourceId = null, sourceBody = ""))
    }

    /** A trashed note does not satisfy a link, so tapping one creates a fresh note instead. */
    @Test
    fun aTrashedNoteDoesNotSatisfyTheLink() = runTest {
        notes.seed(note("binned", "Pond").copy(isTrashed = true))

        val opened = openWikiLink("Pond", sourceId = null, sourceBody = "")

        assertNotEquals("binned", opened)
        assertEquals("Pond", notes.current(opened)?.title)
    }

    // --- create ---------------------------------------------------------------

    /**
     * An unresolved title creates a note whose derived title is exactly that title.
     *
     * The body is the bare title, so `DeriveTitle` yields it back unchanged and the source's link
     * resolves on the very next save. Anything that made the derived title differ — a prefix, a
     * suffix, a blank line — would leave the link permanently unresolved while looking created.
     */
    @Test
    fun anUnresolvedTitleCreatesTheNote() = runTest {
        val opened = openWikiLink("Pond", sourceId = null, sourceBody = "")

        val created = requireNotNull(notes.current(opened))
        assertEquals("Pond", created.title)
        assertEquals("Pond", created.body)
    }

    /**
     * **Creating relinks the source, and this is the assertion that makes the feature whole.**
     *
     * `note_links` rows are written when the *source* note saves. Creating the target alone leaves
     * the link painted, resolvable, and with no row — so the new note's backlinks panel, which 9b
     * built, would be empty. The second save re-derives the source's links now that the target
     * exists. Dropping that save is the plant, and nothing else in the suite notices it.
     */
    @Test
    fun creatingRelinksTheSourceSoTheBacklinkExists() = runTest {
        notes.seed(note("source", "Journal"))

        openWikiLink("Pond", sourceId = "source", sourceBody = "see [[Pond]]")

        assertEquals(
            "the source's links must be re-derived once the target exists",
            listOf("Pond"),
            notes.savedLinks["source"],
        )
    }

    /**
     * The relinking save must **not** restamp the source.
     *
     * It carries no new keystrokes — the body is the one the caller just flushed — so restamping
     * would move the source to the top of every list because the user followed a link out of it.
     * `restamp = true` is the plant; the body is identical either way, so only `modifiedAt` catches it.
     */
    @Test
    fun creatingDoesNotRestampTheSourceNote() = runTest {
        notes.seed(note("source", "Journal", modifiedAt = 1_000L))
        clock.now = 9_000L

        openWikiLink("Pond", sourceId = "source", sourceBody = "see [[Pond]]")

        assertEquals(
            "following a link out of a note is not an edit to it",
            1_000L,
            notes.current("source")?.modifiedAt,
        )
    }

    /** The created note *is* stamped — it is a genuinely new note. */
    @Test
    fun theCreatedNoteIsStampedWithTheClock() = runTest {
        clock.now = 9_000L

        val opened = openWikiLink("Pond", sourceId = null, sourceBody = "")

        assertEquals(9_000L, notes.current(opened)?.createdAt)
    }

    /** Nothing to relink when no note is open, and that must not throw. */
    @Test
    fun creatingWithNoSourceOpenRelinksNothing() = runTest {
        val opened = openWikiLink("Pond", sourceId = null, sourceBody = "")

        assertEquals("Pond", notes.current(opened)?.title)
        assertEquals(1, notes.writes.size)
    }

    /** Creating twice from the same title resolves the second time — no duplicate notes. */
    @Test
    fun tappingTheSameUnresolvedLinkTwiceCreatesOneNote() = runTest {
        val first = openWikiLink("Pond", sourceId = null, sourceBody = "")
        val second = openWikiLink("Pond", sourceId = null, sourceBody = "")

        assertEquals(first, second)
        assertEquals(1, notes.all().count { it.title == "Pond" })
    }

    /** A title with padding was trimmed by `WikiLinkScanner.titleAt` before it ever reached here. */
    @Test
    fun theCreatedTitleIsWhatTheScannerHandedOver() = runTest {
        val title = app.duck.notes.domain.markdown.WikiLinkScanner
            .titleAt("x [[  Padded  ]] y", 2 until 16)

        val opened = openWikiLink(title, sourceId = null, sourceBody = "")

        assertEquals("Padded", notes.current(opened)?.title)
        assertTrue("no padding survived into the note", notes.current(opened)?.body == "Padded")
    }
}
