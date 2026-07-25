package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeLinkRepository
import app.duck.notes.domain.model.NoteLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GetBacklinksTest {

    private val links = listOf(
        NoteLink(sourceId = "a", targetId = "target", linkText = "Target"),
        NoteLink(sourceId = "b", targetId = "target", linkText = "Target"),
        NoteLink(sourceId = "c", targetId = "other", linkText = "Other"),
    )

    @Test
    fun returnsOnlyLinksPointingAtTheNote() = runTest {
        val backlinks = GetBacklinks(FakeLinkRepository(links))("target").first()

        assertEquals(listOf("a", "b"), backlinks.map { it.sourceId })
    }

    @Test
    fun emptyWhenNothingLinksIn() = runTest {
        assertEquals(emptyList<NoteLink>(), GetBacklinks(FakeLinkRepository(links))("lonely").first())
    }

    @Test
    fun emptyWhileNoLinksAreWrittenYet() = runTest {
        assertEquals(emptyList<NoteLink>(), GetBacklinks(FakeLinkRepository())("target").first())
    }
}
