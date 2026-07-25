package app.duck.notes.data.local

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.local.dao.NoteDao
import app.duck.notes.data.local.dao.TagDao
import app.duck.notes.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Verifies the FTS5 external-content triggers stay in sync with `notes`, that tag filtering works,
 * and logs perf at 5000 notes. Perf is measured + logged, not hard-asserted, so the suite doesn't
 * flake on slow emulators — eyeball the logged numbers against the < 50 ms / < 30 ms targets.
 */
@RunWith(AndroidJUnit4::class)
class DuckDatabaseTest {

    private lateinit var db: DuckDatabase
    private lateinit var notes: NoteDao
    private lateinit var tags: TagDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java)
            .addCallback(DuckDatabaseCallback(seedSampleNotes = false))
            .build()
        notes = db.noteDao()
        tags = db.tagDao()
    }

    @After
    fun tearDown() = db.close()

    private fun note(title: String, body: String) =
        NoteEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            body = body,
            createdAt = 0L,
            modifiedAt = 0L,
        )

    @Test
    fun insert_isFoundByFts() = runBlocking {
        notes.saveNoteWithTags(note("Kingfisher", "a small diving bird"), emptyList())

        assertEquals(1, notes.search("kingfisher").size)
        assertEquals(1, notes.search("diving").size) // matches body too
        assertTrue(notes.search("penguin").isEmpty())
    }

    @Test
    fun update_movesFtsTerm() = runBlocking {
        val original = note("mallard", "duck body")
        val rowid = notes.insert(original)
        assertEquals(1, notes.search("mallard").size)

        notes.update(original.copy(rowid = rowid, title = "teal"))

        assertTrue("old title term must be gone", notes.search("mallard").isEmpty())
        assertEquals("new title term must be found", 1, notes.search("teal").size)
    }

    @Test
    fun delete_removesFromFts() = runBlocking {
        val n = note("heron", "wading bird")
        notes.insert(n)
        assertEquals(1, notes.search("heron").size)

        // delete matches by rowid; re-read the stored row to get its assigned rowid
        val stored = notes.observeNotes().first().single()
        notes.delete(stored)

        assertTrue(notes.search("heron").isEmpty())
    }

    @Test
    fun trashedNotesAreExcludedFromSearch() = runBlocking {
        notes.saveNoteWithTags(note("Grebe", "trashed").copy(isTrashed = true), emptyList())
        assertTrue(notes.search("grebe").isEmpty())
    }

    @Test
    fun tagFilter_returnsOnlyTaggedNotes() = runBlocking {
        notes.saveNoteWithTags(note("A", "work note"), listOf("work"))
        notes.saveNoteWithTags(note("B", "home note"), listOf("home"))

        val workTagId = tags.observeAll().first().single { it.name == "work" }.id
        val result = notes.notesByTag(workTagId).first()

        assertEquals(1, result.size)
        assertEquals("A", result.single().title)
    }

    @Test
    fun orphanCleanupSkipsPinnedButRemovesPlainTags() = runBlocking {
        // One note owns both tags; deleting it cascades away both cross-refs -> both orphaned.
        val owner = note("C", "temp")
        notes.saveNoteWithTags(owner, listOf("keepme", "dropme"))
        val keep = tags.observeAll().first().single { it.name == "keepme" }
        tags.upsert(keep.copy(isPinned = true)) // user pins one of them

        val stored = notes.observeNotes().first().single { it.id == owner.id }
        notes.delete(stored) // FK cascade removes both note_tags rows

        // Any subsequent save runs orphan cleanup.
        notes.saveNoteWithTags(note("D", "y"), emptyList())

        val remaining = tags.observeAll().first().map { it.name }.toSet()
        assertTrue("pinned orphan must survive", "keepme" in remaining)
        assertTrue("plain orphan must be removed", "dropme" !in remaining)
    }

    @Test
    fun perf_at5000Notes() = runBlocking {
        db.withTransaction {
            repeat(COUNT) { i -> notes.insert(note("Note $i", "alpha body token$i")) }
        }
        val bulkTagId = tags.observeAll().first().firstOrNull()?.id
            ?: run {
                notes.saveNoteWithTags(note("tagged", "alpha"), listOf("bulk"))
                tags.observeAll().first().single { it.name == "bulk" }.id
            }

        val ftsMs = measureTimeMillis { notes.search("alpha") }
        val tagMs = measureTimeMillis { notes.notesByTag(bulkTagId).first() }

        Log.i(TAG, "FTS query over $COUNT notes = $ftsMs ms (target < 50); tag filter = $tagMs ms (target < 30)")
        assertTrue(notes.search("alpha").isNotEmpty())
    }

    private companion object {
        const val COUNT = 5000
        const val TAG = "DuckDatabaseTest"
    }
}
