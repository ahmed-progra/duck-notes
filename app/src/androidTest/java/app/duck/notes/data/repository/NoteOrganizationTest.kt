package app.duck.notes.data.repository

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.SearchFilters
import app.duck.notes.domain.model.SearchScope
import app.duck.notes.domain.model.SearchQuery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteOrganizationTest {
    private lateinit var db: DuckDatabase
    private lateinit var notes: NoteRepositoryImpl
    private lateinit var search: SearchRepositoryImpl
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), DuckDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).addCallback(DuckDatabaseCallback()).build()
        notes = NoteRepositoryImpl(db.noteDao())
        search = SearchRepositoryImpl(db.noteDao())
    }
    @After fun close() = db.close()

    /** Plant: restamp modified_at in setPinned; an older duplicate must not steal wiki-link resolution. */
    @Test fun pinningChangesOnlyMetadataAndNeverMakesTrashEditable() = runBlocking {
        val older = Note("old", "Same", "# Same\n#projects/duck", 1, 10)
        val newer = older.copy(id = "new", modifiedAt = 20)
        notes.save(older, listOf("projects/duck"), emptyList())
        notes.save(newer, listOf("projects/duck"), emptyList())
        assertEquals("new", notes.noteIdByTitle("Same"))
        notes.setPinned("old", true)
        assertEquals(older.copy(isPinned = true), notes.getNote("old"))
        assertEquals("new", notes.noteIdByTitle("Same"))
        val trash = older.copy(id = "trash", isTrashed = true)
        notes.save(trash, listOf("projects/duck"), emptyList())
        notes.setPinned("trash", true)
        assertEquals(trash, notes.getNote("trash"))
    }

    /** Plant: bypass any filter; each clause has a matching and a nonmatching real Room row. */
    @Test fun searchCombinesActualTagsTasksImagesPinAndArchiveWithoutText() = runBlocking {
        val active = Note("active", "research active", "research\n- [ ] task\n#projects/duck", 1, 10, isPinned = true)
        val done = active.copy(id = "done", body = "research\n- [x] task\n#projects/other", isPinned = false)
        val archive = active.copy(id = "archive", body = "research\n- [ ] task\n#reading", isArchived = true, isPinned = false)
        val trash = active.copy(id = "trash", isTrashed = true)
        notes.save(active, listOf("projects/duck"), emptyList())
        notes.save(done, listOf("projects/other"), emptyList())
        notes.save(archive, listOf("reading"), emptyList())
        notes.save(trash, listOf("projects/duck"), emptyList())
        notes.saveWithAttachment(active, listOf("projects/duck"), emptyList(), Attachment("image", "active", "attachments/image", "image/png", createdAt = 1))
        suspend fun ids(filters: SearchFilters, text: String = "research") = search.search(SearchQuery(text, filters = filters)).map { it.id }.toSet()
        assertEquals(setOf("active", "done", "archive"), ids(SearchFilters()))
        assertEquals(setOf("active", "done"), ids(SearchFilters(tagPath = "projects")))
        assertEquals(setOf("active"), ids(SearchFilters(pinnedOnly = true)))
        assertEquals(setOf("active", "archive"), ids(SearchFilters(openTasksOnly = true)))
        assertEquals(setOf("active"), ids(SearchFilters(imagesOnly = true)))
        assertEquals(setOf("active", "done"), ids(SearchFilters(scope = SearchScope.Active)))
        assertEquals(setOf("archive"), ids(SearchFilters(scope = SearchScope.Archive)))
        assertEquals(setOf("active"), ids(SearchFilters(tagPath = "projects", pinnedOnly = true, openTasksOnly = true, imagesOnly = true, scope = SearchScope.Active), ""))
        assertTrue(ids(SearchFilters(tagPath = "missing")).isEmpty())
    }
}
