package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeTagRepository
import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.model.Tag
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Delete's decisions, and the shared undo path.
 *
 * The transactional half — rollback, trashed scope, the explicit row removal that
 * `deleteOrphanTags` would not do for a pinned tag — is `DuckDatabaseTest`'s.
 */
class DeleteTagTest {

    private val tags = FakeTagRepository()

    @Test
    fun `deletes through the repository`() = runTest {
        DeleteTag(tags)("work")
        assertEquals(listOf("work"), tags.deletes)
    }

    /**
     * Delete takes the name as given — no lowercasing pass like rename's, because the name comes
     * from a tag row that is already stored lowercased rather than from a text field.
     */
    @Test
    fun `passes a nested path through unchanged`() = runTest {
        DeleteTag(tags)("projects/duck/ui")
        assertEquals(listOf("projects/duck/ui"), tags.deletes)
    }

    /**
     * Rename and delete produce the same snapshot type and share one restore path, which is what
     * stops undo from drifting away from the thing it inverts.
     */
    @Test
    fun `undo restores the snapshot both operations produce`() = runTest {
        val snapshot = BodySnapshot(
            bodies = mapOf("n1" to "buy milk #work"),
            tags = listOf(Tag(id = "t1", name = "work", isPinned = true)),
        )
        UndoTagChange(tags)(snapshot)
        assertEquals(listOf(snapshot), tags.restores)
    }
}
