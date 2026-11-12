package app.duck.notes.startup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.DuckApp
import app.duck.notes.data.local.SyncState
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.markdown.TagScanner
import app.duck.notes.domain.markdown.TokenKind
import app.duck.notes.domain.markdown.WikiLinkScanner
import app.duck.notes.domain.usecase.DeriveTitle
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Run with WelcomeStartupTestRunner after externally clearing app data.
 * Scenario after_delete requires a fresh PROCESS, retaining the database and welcome flag.
 * Plants: WELCOME_LINK_TYPO, WELCOME_NO_ATTEMPT_WRITE, WELCOME_TIMESTAMP_TIE, WELCOME_DISPLAY_ORDER,
 * WELCOME_FTS_TRIGGER_MISSING. Assertions pin literals independently of content constants.
 * WELCOME_EXISTING_POPULATED_MISCLASSIFIED: change the existing-origin string to "fresh" in
 * WelcomeSeedState; existing_populated must fail its own literal note count (1 versus 5).
 */
@RunWith(AndroidJUnit4::class)
class WelcomeStartupTest {
    @Test
    fun realApplicationSeedsOrdinaryNotesAndRetainsItsDecision() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DuckApp>()
        val scenario = InstrumentationRegistry.getArguments().getString("scenario", "fresh")
        val graph = EntryPointAccessors.fromApplication(app, WelcomeStartupEntryPoint::class.java)
        val existingNote = NoteEntity(
            id = "11ac819d-3c32-4e39-8805-e2b7a5f884d1",
            title = "Existing checkpoint note",
            body = "# Existing checkpoint note\n\nPersonal text survives welcome upgrade.\n#checkpoint/retained\n",
            createdAt = 1_700_000_000_123L,
            modifiedAt = 1_700_000_000_123L,
            isPinned = false, isArchived = false, isTrashed = false,
            isLocked = false, isEncrypted = false, trashedAt = null,
            syncState = SyncState.PENDING, remoteRev = null,
        )
        if (scenario == "prepare_existing" || scenario == "prepare_existing_populated") {
            assertEquals(WelcomeDisabledTestApp::class.java, app.javaClass)
            assertEquals(WelcomeStartupState.Skipped, graph.startup().state.value)
            assertEquals(0, graph.database().noteDao().count())
            if (scenario == "prepare_existing_populated") {
                graph.saveNote()(existingNote.id, existingNote.body, timestampMillis = 1_700_000_000_123L)
                assertEquals(1, graph.database().noteDao().count())
                assertEquals(existingNote, graph.database().noteDao().getById(existingNote.id)?.copy(rowid = 0))
                assertEquals(listOf(existingNote.id), graph.database().noteDao()
                    .search("Personal text survives welcome upgrade").map { it.id })
                assertEquals(listOf(existingNote.id), graph.database().noteDao()
                    .notesByTagPath("checkpoint/retained").first().map { it.id })
            }
            graph.store().edit { it.clear() }
            assertTrue(graph.store().data.first().asMap().isEmpty())
            assertTrue(app.getDatabasePath("duck.db").exists())
            return@runBlocking
        }
        assertEquals(DuckApp::class.java, app.javaClass)
        val result = withTimeout(10_000L) {
            graph.startup().state.first { it != WelcomeStartupState.Loading }
        }
        assertEquals(WelcomeStartupState.Ready, result)
        val notes = graph.database().noteDao()
        if (scenario == "existing_populated") {
            repeat(3) { attempt ->
                assertEquals("existing populated startup must preserve only its user note", 1, notes.count())
                assertEquals(existingNote, notes.getById(existingNote.id)?.copy(rowid = 0))
                assertEquals(listOf(existingNote.id), notes.search("Personal text survives welcome upgrade").map { it.id })
                assertEquals(listOf(existingNote.id), notes.notesByTagPath("checkpoint/retained").first().map { it.id })
                assertEquals(setOf("checkpoint/retained"), graph.database().tagDao().observeAll().first().map { it.name }.toSet())
                val state = graph.store().data.first()
                assertEquals("existing", state[stringPreferencesKey("installation_origin_v1")])
                assertFalse(state.contains(booleanPreferencesKey("welcome_attempted_v1")))
                if (attempt < 2) graph.seeder()(false)
            }
            return@runBlocking
        }
        if (scenario == "link_findings") {
            verifyDeferredLinkFindings(graph)
            return@runBlocking
        }
        if (scenario == "after_delete" || scenario == "existing") {
            assertEquals("restart must not seed an empty database", 0, notes.count())
            graph.seeder()(false)
            assertEquals(0, notes.count())
            return@runBlocking
        }
        assertEquals("unknown production scenario", "fresh", scenario)
        val stored = notes.observeNotes().first()
        assertEquals(4, notes.count())
        assertEquals(4, stored.size)
        stored.zipWithNext().forEach { (newer, older) ->
            assertTrue("strict timestamp order", newer.modifiedAt > older.modifiedAt)
        }
        assertEquals(4, stored.map { it.createdAt }.distinct().size)
        assertEquals(
            "welcome DAO display order",
            listOf("Welcome to Duck", "Get started", "Organize with tags", "Work faster"),
            stored.map { it.title },
        )
        assertEquals("stored tag set must contain only the shared welcome tag",
            setOf("duck/welcome"), graph.database().tagDao().observeAll().first().map { it.name }.toSet())
        stored.forEach {
            assertEquals(it.createdAt, it.modifiedAt)
            assertFalse(it.isPinned || it.isArchived || it.isTrashed || it.isLocked || it.isEncrypted)
            assertTrue(it.body.contains('\n'))
            assertFalse("stored body must not contain CR", it.body.contains('\r'))
            assertFalse(Regex("bear", RegexOption.IGNORE_CASE).containsMatchIn(it.body))
            assertEquals(it.title, DeriveTitle()(it.body))
            assertEquals(listOf("duck/welcome"), TagScanner.scan(it.body).map { tag -> tag.name })
            assertTrue(graph.database().attachmentDao().forNote(it.id).isEmpty())
        }
        val byTitle = stored.associateBy { DeriveTitle()(it.body) }
        assertEquals(4, byTitle.size)
        val hub = byTitle.getValue("Welcome to Duck")
        val scannerEdges = stored.flatMap { source ->
            WikiLinkScanner.titlesIn(source.body).map { source.title to it }
        }
        val expected = setOf(
            "Welcome to Duck" to "Get started",
            "Welcome to Duck" to "Organize with tags",
            "Welcome to Duck" to "Work faster",
        )
        assertEquals(3, scannerEdges.size)
        val rows = stored.flatMap { graph.database().noteLinkDao().observeBacklinks(it.id).first() }
        assertEquals("no vacuous non-null check", 3, rows.size)
        assertEquals(expected, scannerEdges.toSet())
        assertEquals(expected, rows.map { it.sourceTitle to it.linkText }.toSet())
        rows.forEach {
            assertEquals(hub.id, it.sourceId)
            assertEquals(byTitle.getValue(it.linkText).id, it.targetId)
        }
        val refs = graph.database().tagDao().observeNoteRefs().first()
        assertEquals(4, refs.size)
        assertEquals(stored.map { it.id }.toSet(), refs.map { it.noteId }.toSet())
        assertEquals(4, notes.notesByTagPath("duck/welcome").first().size)
        val searches = mapOf(
            "Make room for your first idea" to "Get started",
            "Give each idea a place" to "Organize with tags",
            "Connect a thought to another note" to "Work faster",
            "A quiet place for your notes" to "Welcome to Duck",
        )
        searches.forEach { (term, title) ->
            assertEquals("real FTS must index welcome body: $term",
                setOf(byTitle.getValue(title).id), notes.search(term).map { it.id }.toSet())
        }
        assertTrue(MarkdownScanner.hasOpenTask(byTitle.getValue("Get started").body))
        // Call the real seeder, rather than the memoized startup coordinator.
        graph.seeder()(false)
        graph.seeder()(false)
        assertEquals("repeated seeder must not duplicate the tour", 4, notes.count())
        val state = graph.store().data.first()
        assertEquals("fresh", state[stringPreferencesKey("installation_origin_v1")])
        assertEquals(true, state[booleanPreferencesKey("welcome_attempted_v1")])
        val edited = hub.body + "\nMy own change.\n"
        graph.saveNote()(hub.id, edited)
        graph.seeder()(false)
        assertEquals(edited, notes.getById(hub.id)?.body)
        // User trash first, then physically empty the DB to defeat any empty-database heuristic.
        stored.forEach { graph.deleteNote()(it.id) }
        assertTrue(notes.observeNotes().first().isEmpty())
        assertEquals(4, notes.observeTrashed().first().size)
        notes.observeTrashed().first().forEach { notes.delete(it) }
        assertEquals(0, notes.count())
        graph.seeder()(false)
        assertEquals(0, notes.count())
    }

    /** Production-graph regression for references that were unresolved when first saved. */
    private suspend fun verifyDeferredLinkFindings(graph: WelcomeStartupEntryPoint) {
        val save = graph.saveNote()
        val links = graph.database().noteLinkDao()
        val source = save(null, "# Link finding source\n[[Later target]]\n")
        val target = save(null, "# Later target\n")
        assertEquals("Late target resolves without another source save", 1,
            links.observeBacklinks(target.id).first().size)
        save(source.id, source.body)
        assertEquals("Positive control: source save resolves the real target", 1,
            links.observeBacklinks(target.id).first().size)
        println("LINK_FINDING_LATE_TARGET: before source save=1; after source save=1")
        save(target.id, "# Renamed target\n")
        assertEquals("Rename immediately stops resolving the old title", 0,
            links.observeBacklinks(target.id).first().size)
        save(source.id, source.body)
        assertEquals("Saving unchanged source drops the old title link", 0,
            links.observeBacklinks(target.id).first().size)
        println("LINK_FINDING_RENAME: after target rename=0; after source save=0")
    }
}
