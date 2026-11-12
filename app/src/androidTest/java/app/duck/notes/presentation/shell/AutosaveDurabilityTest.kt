package app.duck.notes.presentation.shell

import app.duck.notes.domain.usecase.SearchNotes
import app.duck.notes.domain.usecase.ArchiveNote
import app.duck.notes.domain.usecase.DeleteNote
import app.duck.notes.domain.usecase.RestoreNote
import android.content.Context
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.repository.AttachmentRepositoryImpl
import app.duck.notes.data.repository.AttachmentStorageImpl
import app.duck.notes.data.repository.NoteRepositoryImpl
import app.duck.notes.data.repository.LinkRepositoryImpl
import app.duck.notes.data.repository.TagRepositoryImpl
import app.duck.notes.domain.Clock
import app.duck.notes.domain.usecase.AddTagToNote
import app.duck.notes.domain.usecase.AttachImage
import app.duck.notes.domain.usecase.DeleteAttachment
import app.duck.notes.domain.usecase.DeleteTag
import app.duck.notes.domain.usecase.GetBacklinks
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ObserveAttachments
import app.duck.notes.domain.usecase.ObserveNote
import app.duck.notes.domain.usecase.ObserveNotes
import app.duck.notes.domain.usecase.ObserveTagCounts
import app.duck.notes.domain.usecase.ObserveTagTree
import app.duck.notes.domain.usecase.OpenWikiLink
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import app.duck.notes.domain.usecase.PinTag
import app.duck.notes.domain.usecase.RenameTag
import app.duck.notes.domain.usecase.SaveNote
import app.duck.notes.domain.usecase.SetTagIcon
import app.duck.notes.domain.usecase.UndoTagChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Phase 8a's durability claims, against a real SQLite and the real transaction.
 *
 * The JVM suite `DuckShellAutosaveTest` covers the decisions — the debounce, its ceiling, the gate,
 * flush ordering, adoption. (It said "21 tests" here for three phases while the real figure walked to
 * 37. A count in prose has nothing enforcing it, so it only ever rots; the counts worth having are
 * asserted in tests, where they fail. Same lesson `MarkdownCorpus`' own KDoc records.) What it cannot
 * cover is the part that only exists because there is a database, and that is exactly where this
 * phase's worst failure lives:
 *
 * **`ensureTag` resurrecting a deleted tag row.** A stale editor buffer written after a rename does
 * not merely lose the rename — `NoteDao.saveNoteWithTagsAndLinks` re-derives `note_tags` from the body it is
 * handed, and `ensureTag` *mints a fresh `tags` row* for a name the transaction just deleted. No fake
 * models that, because no fake has an `ensureTag`. This suite is the only place it is observable.
 *
 * Real time, not virtual: these assert that the debounce and its ceiling fire against a real clock.
 */
@RunWith(AndroidJUnit4::class)
class AutosaveDurabilityTest {

    private lateinit var db: DuckDatabase
    private lateinit var handle: SavedStateHandle
    private val parseTags = ParseTags()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java)
            // Must match production: system SQLite may lack FTS5, which `notes_fts` requires.
            .setDriver(BundledSQLiteDriver())
            .addCallback(DuckDatabaseCallback())
            .build()
        handle = SavedStateHandle()
    }

    @After
    fun tearDown() = db.close()

    /**
     * A ViewModel over the real database. [handle] is a field so a test can rebuild a *second*
     * ViewModel from the first one's surviving state — process death, as the framework does it.
     */
    private fun shell(autosaveDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate): DuckShellViewModel {
        val notes = NoteRepositoryImpl(db.noteDao())
        val tags = TagRepositoryImpl(db, db.tagDao(), db.noteDao(), DeriveTitle(), parseTags, ResolveWikiLinks())
        val saveNote = SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), Clock { System.currentTimeMillis() })
        val attachments = AttachmentRepositoryImpl(db.attachmentDao())
        val attachStorage = AttachmentStorageImpl(ApplicationProvider.getApplicationContext())
        return DuckShellViewModel(
            handle = handle,
            observeNotes = ObserveNotes(notes),
            observeNote = ObserveNote(notes),
            saveNote = saveNote,
            observeTagTree = ObserveTagTree(tags),
            observeTagCounts = ObserveTagCounts(tags),
            parseTags = parseTags,
            pinTag = PinTag(tags),
            renameTag = RenameTag(tags),
            deleteTag = DeleteTag(tags),
            setTagIcon = SetTagIcon(tags),
            addTagToNote = AddTagToNote(notes, parseTags, saveNote),
            undoTagChange = UndoTagChange(tags),
            attachImage = AttachImage(attachStorage, attachments, notes, saveNote, Clock { System.currentTimeMillis() }),
            openWikiLink = OpenWikiLink(notes, saveNote),
        deleteAttachment = DeleteAttachment(attachStorage, attachments, notes, saveNote),
            observeAttachments = ObserveAttachments(attachments),
            getBacklinks = GetBacklinks(LinkRepositoryImpl(db.noteLinkDao())),
            clock = Clock { System.currentTimeMillis() },
            searchNotes = SearchNotes(app.duck.notes.data.repository.SearchRepositoryImpl(db.noteDao())),
        archiveNote = ArchiveNote(notes, parseTags, ResolveWikiLinks(), Clock { System.currentTimeMillis() }),
        deleteNote = DeleteNote(notes, parseTags, ResolveWikiLinks(), Clock { System.currentTimeMillis() }),
        restoreNote = RestoreNote(notes, parseTags, ResolveWikiLinks(), Clock { System.currentTimeMillis() }),
        autosaveDispatcher = autosaveDispatcher,
        )
    }

    private suspend fun seed(id: String, body: String) {
        db.noteDao().saveNoteWithTagsAndLinks(
            NoteEntity(id = id, title = DeriveTitle()(body), body = body, createdAt = 0L, modifiedAt = 0L),
            parseTags(body),
        )
    }

    private suspend fun bodyOf(id: String) = db.noteDao().getById(id)?.body

    private suspend fun tagNames() = db.tagDao().observeAll().first().map { it.name }.sorted()

    /**
     * Runs [block] against an open note, with the buffer driven from the main thread.
     *
     * `TextFieldState` is Compose snapshot state and `snapshotFlow` observes *applied* snapshots, so
     * every mutation is followed by `Snapshot.sendApplyNotifications()` — outside a composition
     * nothing else applies them, and without it the autosave loop never sees a keystroke.
     */
    private fun withOpenNote(
        id: String,
        body: String,
        block: suspend (DuckShellViewModel) -> Unit,
    ) = runBlocking {
        seed(id, body)
        val vm = withContext(Dispatchers.Main) { shell() }
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { vm.state.collect {} }
        withContext(Dispatchers.Main) { vm.onSelectNote(id) }
        waitUntil { vm.buffer.text.toString() == body }
        block(vm)
        scope.cancel()
    }

    private suspend fun DuckShellViewModel.type(text: String) = withContext(Dispatchers.Main) {
        buffer.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }

    private suspend fun waitUntil(
        timeoutMs: Long = 5_000,
        message: String = "condition not met within ${timeoutMs}ms",
        predicate: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            delay(20)
        }
        assertTrue(message, predicate())
    }

    // --- durability ---------------------------------------------------------

    @Test
    fun filterDeparturePersistsThroughARealDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "filter-durability-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, DuckDatabase::class.java, databaseName)
            .setDriver(BundledSQLiteDriver())
            .addCallback(DuckDatabaseCallback())
            .build()
        db.close()
        db = open()
        val edited = "UniqueDuckR1 — ملاحظتي 🦆\nPending words #work"
        var vm: DuckShellViewModel? = null
        val scope = CoroutineScope(Dispatchers.Main)
        try {
            seed("r1-note", "original")
            val subject = withContext(Dispatchers.Main) { shell() }
            vm = subject
            scope.launch { subject.state.collect {} }
            withContext(Dispatchers.Main) { subject.onSelectNote("r1-note") }
            waitUntil {
                subject.buffer.text.toString() == "original" && subject.state.value.selectedNoteId == "r1-note"
            }
            withContext(Dispatchers.Main) {
                subject.buffer.setTextAndPlaceCursorAtEnd(edited)
                Snapshot.sendApplyNotifications()
                subject.onFilter(ShellFilter.Archive)
            }
            waitUntil { subject.state.value.selectedNoteId == null }
            assertEquals(edited, bodyOf("r1-note"))
            assertEquals(listOf("work"), tagNames())
            assertEquals(listOf("r1-note"), db.noteDao().search("UniqueDuckR1").map { it.id })
            scope.cancel()
            withContext(Dispatchers.Main) { subject.viewModelScope.cancel() }
            db.close()
            db = open()
            assertEquals(edited, bodyOf("r1-note"))
            assertEquals(listOf("work"), tagNames())
        } finally {
            scope.cancel()
            withContext(Dispatchers.Main) { vm?.viewModelScope?.cancel() }
            db.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun failedRoomTransactionRetainsTheEditorAndRetriesTheLatestText() = runBlocking {
        seed("r1-failure", "original")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val autosaveDispatcher = executor.asCoroutineDispatcher()
        executor.submit { entered.countDown(); release.await() }
        var vm: DuckShellViewModel? = null
        val scope = CoroutineScope(Dispatchers.Main)
        try {
            assertTrue("autosave executor did not reach its gate", entered.await(5, TimeUnit.SECONDS))
            // Autosave must not publish the warning on behalf of a broken departure handler.
            val subject = withContext(Dispatchers.Main) { shell(autosaveDispatcher) }
            vm = subject
            scope.launch { subject.state.collect {} }
            withContext(Dispatchers.Main) { subject.onSelectNote("r1-failure") }
            waitUntil {
                subject.buffer.text.toString() == "original" && subject.state.value.selectedNoteId == "r1-failure"
            }
            db.useWriterConnection { connection ->
                connection.usePrepared(
                    "CREATE TRIGGER r1_block_updates BEFORE UPDATE ON notes " +
                        "BEGIN SELECT RAISE(ABORT, 'controlled R1 transaction failure'); END",
                ) { it.step() }
            }
            withContext(Dispatchers.Main) {
                subject.buffer.setTextAndPlaceCursorAtEnd("unsaved — معلّق")
                Snapshot.sendApplyNotifications()
                subject.onFilter(ShellFilter.Archive)
            }
            waitUntil { subject.state.value.saveFailed }
            assertEquals("original", bodyOf("r1-failure"))
            assertEquals("r1-failure", subject.state.value.selectedNoteId)
            assertEquals(ShellFilter.All, subject.state.value.filter)
            assertEquals("unsaved — معلّق", subject.buffer.text.toString())
            db.useWriterConnection { connection ->
                connection.usePrepared("DROP TRIGGER r1_block_updates") { it.step() }
            }
            val latest = "latest retry — آخر محاولة #work"
            withContext(Dispatchers.Main) {
                subject.buffer.setTextAndPlaceCursorAtEnd(latest)
                Snapshot.sendApplyNotifications()
                subject.onRetryNavigation()
            }
            waitUntil { subject.state.value.selectedNoteId == null }
            assertEquals(latest, bodyOf("r1-failure"))
            assertEquals(listOf("work"), tagNames())
            assertEquals(ShellFilter.Archive, subject.state.value.filter)
            assertFalse(subject.state.value.saveFailed)
        } finally {
            scope.cancel()
            withContext(Dispatchers.Main) { vm?.viewModelScope?.cancel() }
            release.countDown()
            autosaveDispatcher.close()
        }
    }

    /**
     * Plant MUTATION_FLUSH_WARNING: set the mutation flush helper's failure warning to false.
     * With autosave parked, only the foreground rename handler can publish the required warning.
     */
    @Test
    fun failedFlushBeforeTagRenameRetainsTheEditorAndRetryOnlyPersists() = runBlocking {
        val noteId = "mutation-flush"
        val original = "original #work"
        seed(noteId, original)
        assertEquals("precondition: the rename has a real tag to affect", listOf("work"), tagNames())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val autosaveDispatcher = executor.asCoroutineDispatcher()
        executor.submit { entered.countDown(); release.await() }
        var vm: DuckShellViewModel? = null
        val scope = CoroutineScope(Dispatchers.Main)
        try {
            assertTrue("autosave executor did not reach its gate", entered.await(5, TimeUnit.SECONDS))
            val subject = withContext(Dispatchers.Main) { shell(autosaveDispatcher) }
            vm = subject
            scope.launch { subject.state.collect {} }
            withContext(Dispatchers.Main) { subject.onSelectNote(noteId) }
            waitUntil {
                subject.buffer.text.toString() == original && subject.state.value.selectedNoteId == noteId
            }
            db.useWriterConnection { connection ->
                connection.usePrepared(
                    "CREATE TRIGGER mutation_block_updates BEFORE UPDATE ON notes " +
                        "BEGIN SELECT RAISE(ABORT, 'controlled mutation flush failure'); END",
                ) { it.step() }
            }
            val pending = "unsaved — معلّق 🦆 #work"
            withContext(Dispatchers.Main) {
                subject.buffer.setTextAndPlaceCursorAtEnd(pending)
                Snapshot.sendApplyNotifications()
                subject.onRenameTag("work", "job")
            }
            waitUntil(message = "failed mutation flush must publish its save warning") {
                subject.state.value.saveFailed
            }
            assertEquals(original, bodyOf(noteId))
            assertEquals(listOf("work"), tagNames())
            assertEquals(noteId, subject.state.value.selectedNoteId)
            assertEquals(ShellFilter.All, subject.state.value.filter)
            assertEquals(pending, subject.buffer.text.toString())
            assertEquals(null, subject.state.value.undo)
            db.useWriterConnection { connection ->
                connection.usePrepared("DROP TRIGGER mutation_block_updates") { it.step() }
            }
            val latest = "latest retry — آخر محاولة 🦆 #work"
            withContext(Dispatchers.Main) {
                subject.buffer.setTextAndPlaceCursorAtEnd(latest)
                Snapshot.sendApplyNotifications()
                subject.onRetryNavigation()
            }
            waitUntil { bodyOf(noteId) == latest && !subject.state.value.saveFailed }
            assertEquals(listOf("work"), tagNames())
            assertEquals(noteId, subject.state.value.selectedNoteId)
            assertEquals(ShellFilter.All, subject.state.value.filter)
            assertEquals(latest, subject.buffer.text.toString())
            assertEquals(null, subject.state.value.undo)

            withContext(Dispatchers.Main) { subject.onRenameTag("work", "job") }
            val renamed = latest.replace("#work", "#job")
            waitUntil { bodyOf(noteId) == renamed && subject.buffer.text.toString() == renamed }
            assertEquals("positive control: explicit rename must reach Room", listOf("job"), tagNames())
            assertEquals(noteId, subject.state.value.selectedNoteId)
            assertEquals(ShellFilter.All, subject.state.value.filter)
        } finally {
            scope.cancel()
            withContext(Dispatchers.Main) { vm?.viewModelScope?.cancel() }
            release.countDown()
            autosaveDispatcher.close()
        }
    }

    /** Type, let the debounce land, then rebuild the ViewModel from the surviving handle. */
    @Test
    fun textSurvivesProcessDeathOnceTheDebounceHasLanded() = withOpenNote("n1", "before") { vm ->
        vm.type("typed and left to settle")
        waitUntil { bodyOf("n1") == "typed and left to settle" }

        // The process dies here. Only the handle survives, and it holds an id, not a body.
        val restored = withContext(Dispatchers.Main) { shell() }
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { restored.state.collect {} }
        waitUntil { restored.buffer.text.toString() == "typed and left to settle" }
        scope.cancel()
    }

    /** No debounce elapses at all — `onStop` is the only thing that can save this. */
    @Test
    fun onStopFlushesWithoutWaitingForTheDebounce() = withOpenNote("n1", "before") { vm ->
        vm.type("typed then immediately backgrounded")
        withContext(Dispatchers.Main) { vm.onStop() }

        waitUntil { bodyOf("n1") == "typed then immediately backgrounded" }
    }

    /**
     * The ceiling fires while typing never pauses.
     *
     * Three seconds of keystrokes at 150ms — never a 400ms gap, so a plain debounce would write
     * nothing at all in that window. Asserted against a real clock rather than a virtual one.
     */
    @Test
    fun theCeilingSavesWithinMaxWaitOfTheFirstKeystroke() = withOpenNote("n1", "") { vm ->
        val started = System.currentTimeMillis()
        var savedAt = 0L

        // 150ms apart: never a 400ms gap, so the idle debounce cannot be what fires.
        repeat(20) { i ->
            vm.type("x".repeat(i + 1))
            if (savedAt == 0L && bodyOf("n1").orEmpty().isNotEmpty()) {
                savedAt = System.currentTimeMillis()
            }
            delay(150)
        }
        if (savedAt == 0L && bodyOf("n1").orEmpty().isNotEmpty()) savedAt = System.currentTimeMillis()

        assertTrue("nothing was saved during 3s of continuous typing", savedAt > 0L)
        val elapsed = savedAt - started
        // Generous slack over the ceiling: this is a real clock on an emulator, and the assertion
        // that matters is "bounded", not "punctual". isNotEmpty() alone would pass even if the write
        // only landed when typing stopped — which is the very failure the ceiling exists to prevent.
        assertTrue(
            "the first save landed ${elapsed}ms after the first keystroke, past the " +
                "${DuckShellViewModel.MAX_WAIT_MS}ms ceiling — the ceiling is not firing and the " +
                "unsaved window is unbounded again",
            elapsed <= DuckShellViewModel.MAX_WAIT_MS + 1_000,
        )
    }

    // --- the two writers ----------------------------------------------------

    /**
     * **The defect this phase exists to prevent, and the only place it is visible.**
     *
     * A rename commits while the editor holds unsaved text for one of the affected notes. If the
     * buffer is merely *not adopted*, it still carries `#work`, and the next autosave hands that body
     * to `saveNoteWithTagsAndLinks` — which re-derives tags and calls `ensureTag("work")`, minting the row
     * the transaction just deleted. The rename is reverted on exactly the note the user was looking
     * at, and the tag reappears in the sidebar.
     *
     * All three assertions matter: the rename survives, the typing survives, and the row stays dead.
     */
    @Test
    fun aRenameSurvivesAnUnsavedEditorBufferAndDoesNotResurrectTheTag() =
        withOpenNote("n1", "a #work b") { vm ->
            vm.type("a #work b PRECIOUS")
            withContext(Dispatchers.Main) { vm.onRenameTag("work", "job") }
            waitUntil { bodyOf("n1")?.contains("#job") == true }
            // Outlast the ceiling, so any stale pending save has had its chance to land.
            delay(DuckShellViewModel.MAX_WAIT_MS + 500)

            val body = bodyOf("n1").orEmpty()
            assertTrue("the rename was reverted by the editor buffer: $body", "#job" in body)
            assertFalse("the old tag is back in the body: $body", "#work" in body)
            assertTrue("unsaved typing was discarded: $body", "PRECIOUS" in body)
            assertFalse(
                "ensureTag resurrected the renamed-away row: ${tagNames()}",
                "work" in tagNames(),
            )
            assertTrue("job" in tagNames())
        }

    /** The same, for delete — which does not cascade, so the row must simply stay gone. */
    @Test
    fun aDeleteSurvivesAnUnsavedEditorBufferAndDoesNotResurrectTheTag() =
        withOpenNote("n1", "a #work b") { vm ->
            vm.type("a #work b PRECIOUS")
            withContext(Dispatchers.Main) { vm.onDeleteTag("work") }
            waitUntil { bodyOf("n1")?.contains("#work") == false }
            delay(DuckShellViewModel.MAX_WAIT_MS + 500)

            val body = bodyOf("n1").orEmpty()
            assertFalse("the deleted tag is back in the body: $body", "#work" in body)
            assertTrue("unsaved typing was discarded: $body", "PRECIOUS" in body)
            assertFalse("ensureTag resurrected the deleted row: ${tagNames()}", "work" in tagNames())
        }

    // --- the saver drop -----------------------------------------------------

    /**
     * A 10k-word note loads into the buffer and puts only an id in the handle.
     *
     * **This is not the saver guard**, and an earlier version of it claimed to be. `rememberSaveable`
     * writes to the composition's `SaveableStateRegistry`, which the *Activity* collects — never to
     * `SavedStateHandle`. Proven rather than reasoned: the saver was temporarily reintroduced into
     * `MarkdownEditorScreen` and this assertion stayed green, because nothing here composes anything.
     * `EditorSaverSourceTest` is the guard that can fail.
     *
     * What this does still prove is the half it can see: a body of this size flows through the
     * ViewModel and the handle stays small, so the `SavedStateHandle` channel is not the leak either.
     * `ShellStateRestorationTest.noNoteBodyReachesTheSavedStateHandle` now makes the same claim over
     * the fakes, so it runs on every build rather than only when an emulator is up; this is the half
     * against real Room.
     */
    @Test
    fun aTenThousandWordNoteLoadsAndLeavesOnlyAnIdInTheHandle() {
        val body = "lorem ipsum dolor sit amet ".repeat(2_000)
        withOpenNote("big", body) { vm ->
            assertEquals(body, vm.buffer.text.toString())
            val oversized = handle.keys()
                .filter { handle.get<Any?>(it).toString().length > MAX_HANDLE_VALUE }
            assertEquals("the note body reached the Bundle: $oversized", emptyList<String>(), oversized)
        }
    }

    private companion object {
        /**
         * Comfortably above any id, filter key or tag path; far below any real note body.
         *
         * Declared again rather than shared: `androidTest` cannot import from `test`, where
         * `ShellStateRestorationTest` holds the same constant for the JVM half of this claim. It was
         * a bare `512` here, with the name and the reason living on an unused constant over there —
         * so neither copy carried both. Now each is used where it is declared.
         */
        const val MAX_HANDLE_VALUE = 512
    }
}
