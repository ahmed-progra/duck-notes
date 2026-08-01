package app.duck.notes.domain.usecase

import app.duck.notes.domain.fake.FakeTagRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the use case *decides*. What it delegates to — the transaction, the body rewriting, the
 * rollback — is `DuckDatabaseTest`'s, because a fake that reimplemented it would be asserting
 * against the reimplementation.
 */
class RenameTagTest {

    private val tags = FakeTagRepository()
    private val rename = RenameTag(tags)

    @Test
    fun `renames through the repository`() = runTest {
        assertNotNull(rename("work", "job"))
        assertEquals(listOf("work" to "job"), tags.renames)
    }

    /**
     * Names are lowercased because the `tags.name` UNIQUE index is a byte comparison, so `#Work` and
     * `#work` must collapse to one row.
     */
    @Test
    fun `lowercases the target`() = runTest {
        rename("work", "Job")
        assertEquals(listOf("work" to "job"), tags.renames)
    }

    @Test
    fun `trims the target`() = runTest {
        rename("work", "  job  ")
        assertEquals(listOf("work" to "job"), tags.renames)
    }

    /** Renaming to what it already is must not rewrite 200 bodies for nothing. */
    @Test
    fun `a rename to the same name does nothing`() = runTest {
        assertNull(rename("work", "work"))
        assertTrue(tags.renames.isEmpty())
    }

    /** After lowercasing, `Work` on a tag named `work` is the same no-op. */
    @Test
    fun `a rename differing only in case does nothing`() = runTest {
        assertNull(rename("work", "Work"))
        assertTrue(tags.renames.isEmpty())
    }

    /**
     * The dialog validates live to drive its confirm button, so this is belt-and-braces — but a
     * precondition that corrupts note bodies when violated does not get left to a caller.
     */
    @Test
    fun `an invalid name never reaches the repository`() = runTest {
        listOf("", "   ", "123", "a/", "a#b").forEach { bad ->
            assertNull("expected $bad to be rejected", rename("work", bad))
        }
        assertTrue(tags.renames.isEmpty())
    }

    @Test
    fun `a nested target is valid`() = runTest {
        assertNotNull(rename("work", "projects/duck"))
        assertEquals(listOf("work" to "projects/duck"), tags.renames)
    }

    @Test
    fun `a multi word target is valid`() = runTest {
        assertNotNull(rename("work", "day job"))
        assertEquals(listOf("work" to "day job"), tags.renames)
    }
}
