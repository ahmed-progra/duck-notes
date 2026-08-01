package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeTagRepository
import app.duck.notes.domain.model.TagRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObserveTagCountsTest {

    private suspend fun counts(vararg refs: Pair<String, String>) =
        ObserveTagCounts(
            FakeTagRepository(refs = refs.map { (tag, note) -> TagRef(tag, note) }),
        )().first()

    @Test
    fun `counts a flat tag`() = runTest {
        val counts = counts("work" to "n1", "work" to "n2")
        assertEquals(2, counts["work"]?.subtree)
        assertEquals(2, counts["work"]?.own)
    }

    /** The whole reason counts are keyed by path: a parent's number has to match what it opens. */
    @Test
    fun `a parent counts its descendants`() = runTest {
        val counts = counts(
            "projects/duck/ui" to "n1",
            "projects/duck/sync" to "n2",
            "projects/site" to "n3",
        )
        assertEquals(3, counts["projects"]?.subtree)
        assertEquals(2, counts["projects/duck"]?.subtree)
        assertEquals(1, counts["projects/duck/ui"]?.subtree)
    }

    /**
     * An intermediate segment nobody tagged directly still gets a count, which is the reason this
     * fold is Kotlin and not a `GROUP BY`: `personal` has no `tags` row for SQL to group on.
     */
    @Test
    fun `an intermediate segment counts its children`() = runTest {
        val counts = counts("personal/recipes" to "n1", "personal/trips" to "n2")
        assertEquals(2, counts["personal"]?.subtree)
        // Nobody wrote #personal, so it holds no notes of its own.
        assertEquals(0, counts["personal"]?.own)
    }

    /**
     * The case that makes summing child counts wrong. One note, two tags in the same branch: a sum
     * would report 2 under `projects` and then open a list of 1.
     */
    @Test
    fun `a note tagged with both a parent and a child counts once`() = runTest {
        val counts = counts("projects/duck" to "n1", "projects/duck/ui" to "n1")
        assertEquals(1, counts["projects"]?.subtree)
        assertEquals(1, counts["projects/duck"]?.subtree)
    }

    /**
     * `own` is what the delete dialog quotes, because delete does not cascade. Quoting `subtree`
     * there would promise to strip the tag from 3 notes and then strip it from 1.
     */
    @Test
    fun `own excludes descendants where subtree includes them`() = runTest {
        val counts = counts(
            "projects" to "n1",
            "projects/duck" to "n2",
            "projects/duck/ui" to "n3",
        )
        assertEquals(3, counts["projects"]?.subtree)
        assertEquals(1, counts["projects"]?.own)
    }

    @Test
    fun `an unknown path has no entry`() = runTest {
        assertNull(counts("work" to "n1")["nope"])
    }

    @Test
    fun `no refs is an empty map, not a crash`() = runTest {
        assertEquals(emptyMap<String, Any>(), counts())
    }
}
