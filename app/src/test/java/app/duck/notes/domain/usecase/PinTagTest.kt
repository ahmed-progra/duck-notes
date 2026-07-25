package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeTagRepository
import app.duck.notes.domain.model.Tag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTagTest {

    private val tags = FakeTagRepository(listOf(Tag("1", "work"), Tag("2", "home")))
    private val pinTag = PinTag(tags)

    @Test
    fun pinsOnlyTheNamedTag() = runTest {
        pinTag("1", pinned = true)

        assertTrue(tags.current().single { it.id == "1" }.isPinned)
        assertFalse(tags.current().single { it.id == "2" }.isPinned)
    }

    @Test
    fun unpins() = runTest {
        pinTag("1", pinned = true)
        pinTag("1", pinned = false)

        assertFalse(tags.current().single { it.id == "1" }.isPinned)
    }

    @Test
    fun unknownIdLeavesTheTagsAlone() = runTest {
        val before = tags.current()

        pinTag("nope", pinned = true)

        assertEquals(before, tags.current())
    }
}
