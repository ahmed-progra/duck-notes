package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FixedClock
import app.duck.notes.domain.model.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWikiLinkTest {
    private val notes = FakeNoteRepository()
    private val clock = FixedClock(now = 1_000L)
    private val saveNote = SaveNote(notes, DeriveTitle(), ParseTags(), ResolveWikiLinks(), clock)
    private val openWikiLink = OpenWikiLink(notes, saveNote)

    private fun note(id: String, title: String, modifiedAt: Long = 1_000L) = Note(
        id = id, title = title, body = title, createdAt = 0L, modifiedAt = modifiedAt,
    )

    @Test fun anExistingTitleResolvesWithoutWriting() = runTest {
        notes.seed(note("target", "Pond"))
        notes.writes.clear()
        assertEquals("target", openWikiLink("Pond"))
        assertTrue(notes.writes.isEmpty())
    }

    @Test fun duplicateTitlesUseModificationThenStableId() = runTest {
        notes.seed(note("old", "Pond", 1L))
        notes.seed(note("z", "Pond", 9L))
        notes.seed(note("a", "Pond", 9L))
        assertEquals("a", openWikiLink("Pond"))
        assertEquals(3, notes.all().size)
    }

    @Test fun aTrashedNoteDoesNotSatisfyTheLinkButAnArchivedOneDoes() = runTest {
        notes.seed(note("binned", "Pond").copy(isTrashed = true))
        val created = openWikiLink("Pond")
        assertNotEquals("binned", created)
        assertEquals("Pond", notes.current(created)?.title)
        notes.seed(note("archive", "Archive").copy(isArchived = true))
        assertEquals("archive", openWikiLink("Archive"))
    }

    /** Plant WIKI_CREATE_WRONG_BODY: save an empty body instead of literal. */
    @Test fun creationKeepsTheLiteralBodyAndUsesTheSaveNoteTitle() = runTest {
        clock.now = 9_000L
        val created = requireNotNull(notes.current(openWikiLink("  **Pond**  ")))
        assertEquals("**Pond**", created.body)
        assertEquals("Pond", created.title)
        assertEquals(9_000L, created.createdAt)
        assertEquals(created.id, openWikiLink("**Pond**"))
        assertEquals(1, notes.all().size)
    }

    @Test fun fallbackDoesNotMergeOrRewriteLiteralTitleMatches() = runTest {
        notes.seed(note("normalized", "Pond", 9_000L))
        notes.seed(note("literal", "**Pond**", 1L))
        notes.writes.clear()
        assertEquals("literal", openWikiLink(" **Pond** "))
        assertEquals(2, notes.all().size)
        assertTrue(notes.writes.isEmpty())
    }

    @Test fun whitespaceAndFormattingFallbackReuseTheDerivedTitle() = runTest {
        val first = openWikiLink("# A   note")
        assertEquals("A note", notes.current(first)?.title)
        assertEquals(first, openWikiLink("# A   note"))
        assertEquals(first, openWikiLink("A  note"))
        assertEquals(1, notes.all().size)
    }

    @Test fun creationDoesNotRewriteTheSourceBodyOrTimestamp() = runTest {
        val source = saveNote(null, "Journal\nsee [[Pond]]")
        val before = notes.writes.count { it.id == source.id }
        clock.now = 9_000L
        openWikiLink("Pond")
        assertEquals(before, notes.writes.count { it.id == source.id })
        assertEquals(source, notes.current(source.id))
        assertEquals(listOf("Pond"), notes.savedLinks[source.id])
    }

    /** Plant WIKI_CREATE_NO_RECHECK: omit the resolution inside creationLock. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun overlappingTapsCreateOnlyOneNote() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        notes.beforeSave = { entered.complete(Unit); release.await() }
        val first = async { openWikiLink("Pond") }
        runCurrent()
        assertTrue(entered.isCompleted)
        val second = async { openWikiLink("Pond") }
        runCurrent()
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, notes.writes.size)
        assertEquals(1, notes.all().size)
    }

    @Test fun creationFailureAndCancellationLeaveTheNextAttemptUsable() = runTest {
        notes.beforeSave = { throw java.io.IOException("controlled creation failure") }
        try {
            openWikiLink("Pond")
            throw AssertionError("creation failure was swallowed")
        } catch (_: java.io.IOException) { }
        assertTrue(notes.all().isEmpty())
        notes.beforeSave = { throw kotlinx.coroutines.CancellationException("controlled cancellation") }
        try {
            openWikiLink("Pond")
            throw AssertionError("cancellation was swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        notes.beforeSave = null
        assertEquals("Pond", notes.current(openWikiLink("Pond"))?.title)
    }
}
