package app.duck.notes.presentation.shell

import app.duck.notes.domain.usecase.SearchNotes
import app.duck.notes.domain.usecase.ArchiveNote
import app.duck.notes.domain.usecase.DeleteNote
import app.duck.notes.domain.usecase.RestoreNote
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import app.duck.notes.domain.Clock
import app.duck.notes.domain.fake.FakeAttachmentRepository
import app.duck.notes.domain.fake.FakeAttachmentStorage
import app.duck.notes.domain.fake.FakeLinkRepository
import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FakeTagRepository
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.Tag
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
import app.duck.notes.domain.markdown.FormatAction
import app.duck.notes.domain.markdown.MarkdownTextCache
import app.duck.notes.domain.markdown.ScanResult
import app.duck.notes.domain.markdown.SpanPlanner
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import app.duck.notes.presentation.markdown.applyFormat
import app.duck.notes.presentation.markdown.toggleTask
import app.duck.notes.domain.usecase.PinTag
import app.duck.notes.domain.usecase.RenameTag
import app.duck.notes.domain.usecase.SaveNote
import app.duck.notes.domain.usecase.SetTagIcon
import app.duck.notes.domain.usecase.UndoTagChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 8a: the editor's buffer is durable, and the two writers cannot corrupt each other.
 *
 * ## Three harness rules, each learned from a failing spike rather than assumed
 *
 * 1. **`Snapshot.sendApplyNotifications()` after every buffer mutation.** `snapshotFlow` observes
 *    applied snapshots; outside a composition nothing applies them, so without this call the
 *    autosave collector never sees a keystroke and every test here passes vacuously. [type] is the
 *    only way this suite touches the buffer, so it cannot be forgotten.
 * 2. **Use the emitted value, never a re-read** inside a collector under test.
 * 3. **`advanceUntilIdle()` is safe only because [DuckShellViewModel.cappedDebounce] parks on
 *    `receive()` between saves.** The rejected `merge(debounce, sample)` schedules a perpetual
 *    ticker, and `advanceUntilIdle()` never returns against it — that is not a hypothetical, it hung
 *    this module's whole shell suite before the operator was rewritten.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuckShellAutosaveTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val notes = FakeNoteRepository()
    // `notes` passed in: 8a's subject is two writers racing over one body, so the fake has to rewrite
    // bodies for real (through TagScanner) rather than only recording the call. See its KDoc.
    private val tags = FakeTagRepository(listOf(Tag(id = "work", name = "work")), notes = notes)
    private val attachments = FakeAttachmentRepository().also { notes.attachments = it }
    private val attachStorage = FakeAttachmentStorage()
    private val parseTags = ParseTags()
    private val searchCalls = mutableListOf<String>()
    private var searchHandler: suspend (app.duck.notes.domain.model.SearchQuery) -> List<Note> = { query -> notes.all().filter { !it.isTrashed && it.body.contains(query.text, ignoreCase = true) } }


    /**
     * **Strictly increasing**, and that is what makes the `modifiedAt` tests able to fail.
     *
     * Two writers reach `NoteRepository.save`: `SaveNote` *mints* a `modifiedAt` from the clock, while
     * a tag rewrite copies the note and changes only the body — inheriting whatever stamp is already
     * there. Under a fixed clock the two are indistinguishable in the write log, because the rewrite
     * inherits the stamp the flush just minted and looks exactly like a second `SaveNote`. With the
     * clock advancing on every read, **one distinct stamp means one `SaveNote`**, however many writes
     * landed. That is the property `aRenameOverADirtyBuffer…` actually asserts.
     */
    private var nowSource = 1_000L
    private val clock = Clock { nowSource++ }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun shell(handle: SavedStateHandle = SavedStateHandle()) = DuckShellViewModel(
        handle = handle,
        observeNotes = ObserveNotes(notes),
        observeNote = ObserveNote(notes),
        saveNote = SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), clock),
        observeTagTree = ObserveTagTree(tags),
        observeTagCounts = ObserveTagCounts(tags),
        parseTags = parseTags,
        pinTag = PinTag(tags),
        renameTag = RenameTag(tags),
        deleteTag = DeleteTag(tags),
        setTagIcon = SetTagIcon(tags),
        addTagToNote = AddTagToNote(
            notes,
            parseTags,
            SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), clock),
        ),
        undoTagChange = UndoTagChange(tags),
        attachImage = AttachImage(
            attachStorage, attachments, notes,
            SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), clock), clock,
        ),
        openWikiLink = OpenWikiLink(notes, SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), clock)),
        deleteAttachment = DeleteAttachment(
            attachStorage, attachments, notes,
            SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), clock),
        ),
        observeAttachments = ObserveAttachments(attachments),
        getBacklinks = GetBacklinks(FakeLinkRepository()),
        clock = clock,
        searchNotes = SearchNotes(object : app.duck.notes.domain.repository.SearchRepository { override suspend fun search(query: app.duck.notes.domain.model.SearchQuery): List<Note> { searchCalls += query.text; return searchHandler(query) } }),
        archiveNote = ArchiveNote(notes, parseTags, ResolveWikiLinks(), Clock { 10_000L }),
        deleteNote = DeleteNote(notes, parseTags, ResolveWikiLinks(), Clock { 10_000L }),
        restoreNote = RestoreNote(notes, parseTags, ResolveWikiLinks(), Clock { 10_000L }),
        autosaveDispatcher = dispatcher,
    )

    private fun note(id: String, body: String) =
        Note(id = id, title = id, body = body, createdAt = 0L, modifiedAt = 0L)

    /** A keystroke. Mutation plus the snapshot apply, which is the half that is easy to omit. */
    private fun DuckShellViewModel.type(text: String) {
        buffer.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }

    /** Writes to [id] only — seeds and other notes are noise for a write-count assertion. */
    private fun writesTo(id: String) = notes.writes.filter { it.id == id }.map { it.body }

    /**
     * How many times `SaveNote` **minted** a stamp for [id] — not how many writes landed.
     *
     * A tag rewrite lands in `writes` too, copying the note and inheriting whatever `modifiedAt` is
     * already on it. Right after a flush that inherited stamp is the *fresh* one, so a
     * `modifiedAt >= since` filter counts the rewrite as a second `SaveNote` and the test fails for
     * the wrong reason — which is exactly what happened. Nor is a bare distinct-count enough: with the
     * log cleared, a lone inherited stamp is indistinguishable from a lone minted one.
     *
     * Both halves are needed. **Strictly greater than [baseline]** excludes inherited stamps, because
     * the clock only ever moves forward; **distinct** then collapses the rewrites that copied a stamp
     * this operation did mint. Zero means `SaveNote` never ran; one means it ran once, however many
     * writes followed it.
     */
    private fun mintedStampsAfter(id: String, baseline: Long) =
        notes.writes.filter { it.id == id && it.modifiedAt > baseline }.map { it.modifiedAt }.distinct()

    /**
     * Builds a shell with [id] already open, then hands it to [body] with the write log cleared.
     *
     * Selecting the note is itself a load, and asserting "typing produced one write" against a log
     * that still holds the load is the mistake `openingANoteWritesNothing` exists to make visible.
     */
    private fun withOpenNote(
        id: String = "n1",
        body: String = "hello",
        block: suspend TestScope.(DuckShellViewModel) -> Unit,
    ) = runTest {
        notes.seed(note(id, body))
        val vm = shell()
        val scope = CoroutineScope(dispatcher)
        scope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onSelectNote(id)
        advanceUntilIdle()
        notes.writes.clear()
        try { block(vm) } finally { scope.cancel() }
    }

    // --- the debounce and its ceiling ---------------------------------------

    /**
     * **The two numbers the amended success bar quotes, pinned as literals.**
     *
     * Everything else here measures *behaviour* against these constants by reading them, so the bar
     * moves with the thing under test and no behavioural test can catch a change to the promise
     * itself. Measured: [theCeilingSavesWhileTypingNeverPauses] drives 5 s of typing and asserts two
     * or more saves, so it tolerates any ceiling up to 2500 ms; `AutosaveDurabilityTest`'s device
     * test asserts `elapsed <= MAX_WAIT_MS + 1_000`, which is a bound built from the value it is
     * checking. **`MAX_WAIT_MS = 2_400L` was green across both**, silently growing the stated
     * data-loss window by 20% in a diff that reads as tuning.
     *
     * These are not tunables. They are the two halves of a promise made to the user in the phase's
     * success bar:
     *
     * > No data loss on any orderly departure — `ON_STOP` flushes. **≤ 2 s of typing** on an
     * > abnormal kill (crash, `am force-stop`, kernel OOM) where no `ON_STOP` fires.
     *
     * [DuckShellViewModel.MAX_WAIT_MS] *is* that 2 s. [DuckShellViewModel.IDLE_MS] is the spec's own
     * 400 ms debounce. Changing either changes what Duck promises, so it costs an edit here and a
     * matching edit to the bar in `DUCK_NOTES_BUILD_PROMPT.md`. CLAUDE.md's Guards rule — *pin the
     * literal* — was written from this and three siblings.
     */
    @Test
    fun theAutosaveWindowsThemselvesArePinned() {
        assertEquals(
            "MAX_WAIT_MS is the '<= 2 s of typing' in the phase's success bar, not a tunable. " +
                "Moving it moves what Duck promises on an abnormal kill — update the bar too.",
            2_000L,
            DuckShellViewModel.MAX_WAIT_MS,
        )
        assertEquals(
            "IDLE_MS is the spec's 400ms debounce. Same rule: change the promise, not just the number.",
            400L,
            DuckShellViewModel.IDLE_MS,
        )
    }

    @Test
    fun keystrokesInsideTheIdleWindowProduceExactlyOneSave() = withOpenNote { vm ->
        repeat(5) { i ->
            vm.type("hello$i")
            advanceTimeBy(100)
        }
        advanceUntilIdle()

        assertEquals(listOf("hello4"), writesTo("n1"))
    }

    @Test
    fun aChangeThenQuietSavesTheFinalText() = withOpenNote { vm ->
        vm.type("edited")
        advanceUntilIdle()

        assertEquals(listOf("edited"), writesTo("n1"))
        assertEquals("edited", notes.current("n1")?.body)
    }

    /**
     * Item 1 of the review: the gate is on the collector, not only on `flush()`.
     *
     * Opening a note must be a pure read. Without `lastWritten` being set *before* the buffer is
     * written, `snapshotFlow` fires against a stale gate and the load writes itself straight back —
     * restamping `modifiedAt` and reordering the note list on what the user experienced as a tap.
     */
    @Test
    fun openingANoteWritesNothingAndDoesNotRestampModifiedAt() = runTest {
        notes.seed(note("n1", "untouched"))
        val before = notes.current("n1")?.modifiedAt
        val vm = shell()
        val scope = CoroutineScope(dispatcher)
        scope.launch { vm.state.collect {} }
        advanceUntilIdle()
        notes.writes.clear()

        vm.onSelectNote("n1")
        advanceUntilIdle()

        assertEquals(emptyList<String>(), writesTo("n1"))
        assertEquals(before, notes.current("n1")?.modifiedAt)
        scope.cancel()
    }

    /**
     * The ceiling fires while typing never pauses — the whole reason it exists.
     *
     * Five seconds of steady typing with no gap longer than the idle window. A plain debounce would
     * write nothing at all in that time; see [debounceAloneNeverFiresDuringContinuousTyping].
     */
    @Test
    fun theCeilingSavesWhileTypingNeverPauses() = withOpenNote { vm ->
        repeat(25) { i ->
            vm.type("x".repeat(i + 1))
            advanceTimeBy(200)
        }

        val saves = writesTo("n1")
        assertTrue("the ceiling never fired during continuous typing: $saves", saves.size >= 2)
    }

    /**
     * The ceiling fires while typing never pauses, **and** a plain debounce over the identical input
     * does not. Both halves, one assertion.
     *
     * ## What this replaces, and why the old one could not fail
     *
     * This was `debounceAloneNeverFiresDuringContinuousTyping`, cited as "the positive control for
     * the ceiling" and as the evidence behind the amended success bar. It built its **own**
     * `debounce(400)` inside the test, collected into its own list, and never called
     * `cappedDebounce` at all — so it asserted a property of `kotlinx.coroutines` and nothing about
     * Duck. **No edit to `src/main` could turn it red**, including the exact regression its KDoc
     * described: replacing `cappedDebounce` with a plain debounce left it green. An audit named it
     * as the one finding for which no plant could be written, and CLAUDE.md's Guards rule —
     * *drive the real thing* — was written partly from it.
     *
     * `cappedDebounce` is now top-level and `internal` so this can call it. The contrast that made
     * the original worth having survives, and is now falsifiable: the real operator must fire during
     * continuous typing, the plain one must not.
     *
     * **One assertion over a pair, not two calls.** JUnit stops at the first failure, so a sequential
     * version proves only whichever half runs first — the reason
     * `TaskToggleUiTest.aDragFromTheBoxScrollsTheNoteAndDoesNotToggle` is written the same way.
     */
    @OptIn(FlowPreview::class)
    @Test
    fun theCeilingFiresWhileAPlainDebounceNeverDoes() = runTest {
        val keystrokes = MutableStateFlow("")
        val capped = mutableListOf<String>()
        val plain = mutableListOf<String>()
        val scope = CoroutineScope(dispatcher)

        // The real operator, and the design it replaced, over one input.
        scope.launch { keystrokes.cappedDebounce().collect { capped += it } }
        scope.launch { keystrokes.debounce(DuckShellViewModel.IDLE_MS).collect { plain += it } }
        advanceTimeBy(1)

        // Five seconds of typing with no gap longer than the idle window.
        repeat(25) { i ->
            keystrokes.value = "x".repeat(i + 1)
            advanceTimeBy(200)
        }
        scope.cancel()

        assertEquals(
            "the ceiling must fire during continuous typing (capped=${capped.size}) and a plain " +
                "debounce must not (plain=${plain.size}) — if capped is 0 the unsaved window is " +
                "unbounded again, which is what the amended success bar exists to rule out",
            CappedFired(true) to PlainFired(false),
            CappedFired(capped.size >= 2) to PlainFired(plain.isNotEmpty()),
        )
    }

    /** Named wrappers so a failure reads `CappedFired(false)`, not a bare `false`. */
    private data class CappedFired(val value: Boolean)

    private data class PlainFired(val value: Boolean)

    // --- flush ---------------------------------------------------------------

    @Test
    fun flushWritesImmediatelyWithoutWaitingForTheIdleWindow() = withOpenNote { vm ->
        vm.type("urgent")
        vm.flush()

        assertEquals(listOf("urgent"), writesTo("n1"))
    }

    @Test
    fun flushOnACleanBufferWritesNothing() = withOpenNote { vm ->
        vm.flush()
        vm.flush()

        assertEquals(emptyList<String>(), writesTo("n1"))
    }

    @Test
    fun changingFilterPersistsPendingMixedScriptTextBeforeDeparture() = withOpenNote { vm ->
        val edited = "My note — ملاحظتي 🦆\nPending words #work"
        vm.type(edited)
        assertEquals("precondition: debounce has not saved", "hello", notes.current("n1")?.body)

        vm.onFilter(ShellFilter.Untagged)

        assertEquals("filter departure must persist pending text", edited, notes.current("n1")?.body)
        assertEquals(listOf("work"), notes.savedTags["n1"])
        assertEquals(ShellFilter.Untagged, vm.state.value.filter)
        assertEquals(null, vm.state.value.selectedNoteId)
    }

    @Test
    fun filterDepartureAlsoPersistsTypingAcceptedDuringTheWrite() = withOpenNote { vm ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        notes.beforeSave = { note ->
            if (note.body == "first edit") {
                entered.complete(Unit)
                release.await()
            }
        }
        vm.type("first edit")
        vm.onFilter(ShellFilter.Archive)
        assertTrue("precondition: persistence is suspended", entered.isCompleted)
        assertEquals("n1", vm.state.value.selectedNoteId)
        vm.type("latest edit — آخر تعديل 🦆")
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("latest edit — آخر تعديل 🦆", notes.current("n1")?.body)
        assertEquals(ShellFilter.Archive, vm.state.value.filter)
        assertEquals(null, vm.state.value.selectedNoteId)
    }

    @Test
    fun anOlderFilterDepartureCannotClearANewerNoteSelection() = withOpenNote { vm ->
        notes.seed(note("n2", "second note"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        notes.beforeSave = { note ->
            if (note.body == "pending edit") {
                entered.complete(Unit)
                release.await()
            }
        }
        vm.type("pending edit")
        vm.onFilter(ShellFilter.Archive)
        assertTrue(entered.isCompleted)
        vm.onSelectNote("n2")
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(ShellFilter.All, vm.state.value.filter)
        assertEquals("n2", vm.state.value.selectedNoteId)
        assertEquals("pending edit", notes.current("n1")?.body)
        assertEquals("second note", notes.current("n2")?.body)
    }

    @Test
    fun failedFilterDepartureRetainsTextAndRetriesTheLatestEdit() = withOpenNote { vm ->
        var failures = 0
        notes.beforeSave = { failures++; throw java.io.IOException("controlled save failure") }
        vm.type("pending — معلّق")
        vm.onFilter(ShellFilter.Archive)
        assertEquals(1, failures)
        assertEquals("hello", notes.current("n1")?.body)
        assertEquals(ShellFilter.All, vm.state.value.filter)
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals("pending — معلّق", vm.buffer.text.toString())
        assertTrue(vm.state.value.saveFailed)

        notes.beforeSave = null
        vm.type("latest retry — آخر محاولة")
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("latest retry — آخر محاولة", notes.current("n1")?.body)
        assertEquals(ShellFilter.Archive, vm.state.value.filter)
        assertEquals(null, vm.state.value.selectedNoteId)
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun filterDepartureFromACleanNoteDoesNotWrite() = withOpenNote { vm ->
        val stamp = notes.current("n1")?.modifiedAt
        vm.onFilter(ShellFilter.Archive)
        assertEquals(ShellFilter.Archive, vm.state.value.filter)
        assertEquals(emptyList<String>(), writesTo("n1"))
        assertEquals(stamp, notes.current("n1")?.modifiedAt)
    }

    @Test
    fun aFailedAutosaveKeepsCollectingAndPersistsTheNextEdit() = withOpenNote { vm ->
        var failures = 0
        notes.beforeSave = { failures++; throw java.io.IOException("controlled autosave failure") }
        vm.type("first unsaved edit")
        advanceTimeBy(401)
        advanceUntilIdle()
        assertEquals(1, failures)
        assertEquals("hello", notes.current("n1")?.body)
        assertEquals("first unsaved edit", vm.buffer.text.toString())
        assertTrue(vm.state.value.saveFailed)

        notes.beforeSave = null
        vm.type("second edit survives")
        advanceTimeBy(401)
        advanceUntilIdle()
        assertEquals("second edit survives", notes.current("n1")?.body)
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun failureDuringOnStopCanRetryWithoutNavigatingAway() = withOpenNote { vm ->
        notes.beforeSave = { throw java.io.IOException("controlled lifecycle save failure") }
        vm.type("backgrounded edit")
        vm.onStop()
        assertTrue(vm.state.value.saveFailed)
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals("hello", notes.current("n1")?.body)
        notes.beforeSave = null
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("backgrounded edit", notes.current("n1")?.body)
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun recoveredAutosaveRetiresAnOldFailedNavigationBeforeALaterFailure() = withOpenNote { vm ->
        notes.beforeSave = { throw java.io.IOException("controlled navigation failure") }
        vm.type("first edit")
        vm.onFilter(ShellFilter.Archive)
        assertTrue(vm.state.value.saveFailed)
        assertEquals(ShellFilter.All, vm.state.value.filter)

        notes.beforeSave = null
        vm.type("recovered edit")
        advanceTimeBy(401)
        advanceUntilIdle()
        assertEquals("recovered edit", notes.current("n1")?.body)
        assertFalse(vm.state.value.saveFailed)

        notes.beforeSave = { throw java.io.IOException("unrelated lifecycle failure") }
        vm.type("later edit")
        vm.onStop()
        assertTrue(vm.state.value.saveFailed)
        notes.beforeSave = null
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("later edit", notes.current("n1")?.body)
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals(ShellFilter.All, vm.state.value.filter)
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun revertingFailedEditsRetiresTheWarningAndOldDestinationWithoutWriting() = withOpenNote { vm ->
        notes.beforeSave = { throw java.io.IOException("controlled navigation failure") }
        vm.type("unsaved edit")
        vm.onFilter(ShellFilter.Archive)
        assertTrue(vm.state.value.saveFailed)
        vm.type("hello")
        advanceTimeBy(401)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), writesTo("n1"))
        assertFalse(vm.state.value.saveFailed)

        vm.type("later edit")
        vm.onStop()
        assertTrue(vm.state.value.saveFailed)
        notes.beforeSave = null
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("later edit", notes.current("n1")?.body)
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals(ShellFilter.All, vm.state.value.filter)
    }

    @Test
    fun anOwedTagWriteKeepsItsFailureUntilTheFullWriteSucceeds() = withOpenNote { vm ->
        vm.type("#work")
        advanceTimeBy(401)
        advanceUntilIdle()
        assertEquals("#work", notes.current("n1")?.body)
        assertEquals(emptyList<String>(), notes.savedTags["n1"])
        notes.beforeSave = { throw java.io.IOException("controlled tag completion failure") }
        vm.onFilter(ShellFilter.Archive)
        assertTrue(vm.state.value.saveFailed)

        vm.type("temporary edit")
        advanceTimeBy(200)
        vm.type("#work")
        advanceTimeBy(401)
        advanceUntilIdle()
        assertTrue(vm.state.value.saveFailed)
        assertEquals(emptyList<String>(), notes.savedTags["n1"])
        notes.beforeSave = null
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals(listOf("work"), notes.savedTags["n1"])
        assertEquals(ShellFilter.Archive, vm.state.value.filter)
        assertFalse(vm.state.value.saveFailed)
    }

    @Test
    fun cancelledFilterDepartureRetainsTextWithoutReportingAStorageFailure() = withOpenNote { vm ->
        notes.beforeSave = { throw CancellationException("controlled navigation cancellation") }
        vm.type("cancelled departure edit")
        vm.onFilter(ShellFilter.Archive)
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals(ShellFilter.All, vm.state.value.filter)
        assertEquals("cancelled departure edit", vm.buffer.text.toString())
        assertEquals("hello", notes.current("n1")?.body)
        assertFalse(vm.state.value.saveFailed)
        notes.beforeSave = null
        vm.onFilter(ShellFilter.Archive)
        advanceUntilIdle()
        assertEquals("cancelled departure edit", notes.current("n1")?.body)
        assertEquals(ShellFilter.Archive, vm.state.value.filter)
    }

    @Test
    fun cancelledAutosaveDoesNotReportAStorageFailure() = withOpenNote { vm ->
        notes.beforeSave = { throw CancellationException("controlled autosave cancellation") }
        vm.type("cancelled autosave edit")
        advanceTimeBy(401)
        advanceUntilIdle()
        assertEquals("hello", notes.current("n1")?.body)
        assertEquals("cancelled autosave edit", vm.buffer.text.toString())
        assertFalse(vm.state.value.saveFailed)
        notes.beforeSave = null
        vm.flush()
        assertEquals("cancelled autosave edit", notes.current("n1")?.body)
    }

    @Test
    fun cancelledOnStopDoesNotReportAStorageFailure() = withOpenNote { vm ->
        notes.beforeSave = { throw CancellationException("controlled lifecycle cancellation") }
        vm.type("cancelled background edit")
        vm.onStop()
        assertEquals("hello", notes.current("n1")?.body)
        assertEquals("cancelled background edit", vm.buffer.text.toString())
        assertFalse(vm.state.value.saveFailed)
        notes.beforeSave = null
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("cancelled background edit", notes.current("n1")?.body)
        assertEquals("n1", vm.state.value.selectedNoteId)
    }

    @Test
    fun onStopFlushes() = withOpenNote { vm ->
        vm.type("typed then backgrounded")
        vm.onStop()
        advanceUntilIdle()

        assertEquals(listOf("typed then backgrounded"), writesTo("n1"))
    }

    /** Plant ACTION_FLUSH_WARNING_OFF: set the operation flush catch's saveFailed to false. */
    private fun failedOperationRetainsTextAndRetriesOnlyTheSave(
        action: (DuckShellViewModel) -> Unit,
        assertApplied: () -> Unit,
    ) = withOpenNote(body = "hello #work") { vm ->
        vm.onAttachImage("content://existing", "image/png")
        advanceUntilIdle()
        val persisted = requireNotNull(notes.current("n1")).body
        val existingAttachments = attachments.all()
        val existingFiles = attachStorage.files.toSet()
        val existingCopies = attachStorage.stored.toList()
        assertEquals("positive control: a real attachment exists", 1, existingAttachments.size)
        var failures = 0
        notes.beforeSave = { failures++; throw java.io.IOException("controlled operation flush failure") }
        vm.type("pending — معلّق #work")

        action(vm)

        assertEquals("the operation must attempt its own flush before autosave runs", 1, failures)
        assertTrue("the failed operation flush must immediately publish its warning", vm.state.value.saveFailed)
        assertEquals(persisted, notes.current("n1")?.body)
        assertEquals("pending — معلّق #work", vm.buffer.text.toString())
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals(ShellFilter.All, vm.state.value.filter)
        assertEquals(emptyList<Pair<String, String>>(), tags.renames)
        assertEquals(emptyList<String>(), tags.deletes)
        assertEquals(existingAttachments, attachments.all())
        assertEquals(existingFiles, attachStorage.files)
        assertEquals(existingCopies, attachStorage.stored)

        notes.beforeSave = null
        val latest = "$persisted\nlatest — آخر تعديل #work"
        vm.type(latest)
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals(latest, notes.current("n1")?.body)
        assertEquals(latest, vm.buffer.text.toString())
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals(ShellFilter.All, vm.state.value.filter)
        assertFalse(vm.state.value.saveFailed)
        assertEquals(emptyList<Pair<String, String>>(), tags.renames)
        assertEquals(emptyList<String>(), tags.deletes)
        assertEquals(existingAttachments, attachments.all())
        assertEquals(existingFiles, attachStorage.files)
        assertEquals(existingCopies, attachStorage.stored)

        action(vm)
        advanceUntilIdle()
        assertApplied()
    }

    @Test
    fun failedRenameFlushRetainsTextAndDoesNotReplayTheRename() = failedOperationRetainsTextAndRetriesOnlyTheSave(
        action = { it.onRenameTag("work", "job") },
        assertApplied = { assertEquals(listOf("work" to "job"), tags.renames) },
    )

    @Test
    fun failedTagDeleteFlushRetainsTextAndDoesNotReplayTheDeletion() = failedOperationRetainsTextAndRetriesOnlyTheSave(
        action = { it.onDeleteTag("work") },
        assertApplied = { assertEquals(listOf("work"), tags.deletes) },
    )

    @Test
    fun failedTagAddFlushRetainsTextAndDoesNotReplayTheAppend() = failedOperationRetainsTextAndRetriesOnlyTheSave(
        action = { it.onAddTagToNote("n1", "added") },
        assertApplied = { assertTrue("explicit repeat must append the tag", "added" in notes.savedTags["n1"].orEmpty()) },
    )

    @Test
    fun failedImageAddFlushRetainsTextAndDoesNotReplayTheCopy() = failedOperationRetainsTextAndRetriesOnlyTheSave(
        action = { it.onAttachImage("content://new", "image/png") },
        assertApplied = { assertEquals("explicit repeat must copy one image", 2, attachments.all().size) },
    )

    @Test
    fun failedImageDeleteFlushRetainsTextAndDoesNotReplayTheDeletion() = failedOperationRetainsTextAndRetriesOnlyTheSave(
        action = { it.onDeleteAttachment(attachments.all().single().id) },
        assertApplied = {
            assertTrue("explicit repeat must delete the row", attachments.all().isEmpty())
            assertTrue("explicit repeat must delete the file", attachStorage.files.isEmpty())
        },
    )

    /** Plant MUTATION_FAILURE_KEEPS_OLD_NAVIGATION: omit invalidation in the failed body flush. */
    @Test fun failedPendingNavigationCannotRepopulateRetryAfterNewerRename() = withOpenNote(body = "hello #work") { vm ->
        notes.seed(note("n2", "other note"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        notes.beforeSave = {
            if (it.id == "n1") {
                attempts++
                if (attempts == 1) { entered.complete(Unit); release.await() }
                throw java.io.IOException("controlled pending-navigation and rename flush failure")
            }
        }
        vm.type("pending body #work")
        vm.onSelectNote("n2")
        assertTrue("the earlier navigation must hold its real body write", entered.isCompleted)
        vm.onRenameTag("work", "job")
        release.complete(Unit)
        runCurrent()
        assertEquals("both commands must attempt their body flush before autosave", 2, attempts)
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertTrue(vm.state.value.saveFailed)
        assertEquals("hello #work", notes.current("n1")?.body)
        assertTrue(tags.renames.isEmpty())
        notes.beforeSave = null
        vm.type("latest after both failures #work")
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("Retry after the newer failed rename must not replay older pending navigation", "n1", vm.state.value.selectedNoteId)
        assertEquals("latest after both failures #work", notes.current("n1")?.body)
        assertEquals("other note", notes.current("n2")?.body)
        assertTrue("Retry must not replay the failed rename", tags.renames.isEmpty())
        assertFalse(vm.state.value.saveFailed)
    }

    /** Plant MUTATION_FAILURE_CANCELS_NEWER_NAVIGATION: drop the entry-revision comparison. */
    @Test fun newerSelectionDuringAnOlderFailedRenameStillOpensItsTarget() = withOpenNote(body = "hello #work") { vm ->
        notes.seed(note("n2", "other note"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        notes.beforeSave = {
            if (it.id == "n1") {
                attempts++
                if (attempts == 1) {
                    entered.complete(Unit)
                    release.await()
                    throw java.io.IOException("controlled older rename body failure")
                }
            }
        }
        vm.type("accepted during rename #work")
        vm.onRenameTag("work", "job")
        assertTrue("the older rename must hold its real body write", entered.isCompleted)
        vm.onSelectNote("n2")
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("newer navigation must save after the one-shot mutation failure", 2, attempts)
        assertEquals("accepted during rename #work", notes.current("n1")?.body)
        assertTrue(tags.renames.isEmpty())
        assertEquals("an older failed rename must not cancel newer explicit navigation", "n2", vm.state.value.selectedNoteId)
        assertFalse(vm.state.value.saveFailed)
    }

    /** Plant MUTATION_RETRY_CLEAR_FOREIGN_OWNER: unconditionally clear Retry at mutation entry. */
    @Test fun retiredPickerFailurePreservesTheCurrentFailedNavigationRetry() = withOpenNote { vm ->
        val origin = requireNotNull(vm.editorSession)
        vm.onEditorAttached(origin)
        assertTrue(vm.onImagePickerOpened(origin))
        notes.seed(note("n2", "second note"))
        notes.seed(note("n3", "third note"))
        vm.onSelectNote("n2")
        advanceUntilIdle()
        assertTrue(origin !== vm.editorSession && origin.retired && origin.canEdit)
        origin.buffer.setTextAndPlaceCursorAtEnd("late outgoing edit")
        Snapshot.sendApplyNotifications()
        var attempts = 0
        notes.beforeSave = {
            if (it.id == "n1") { attempts++; throw java.io.IOException("controlled retired body failure") }
        }
        vm.onEditorDetached(origin)
        runCurrent()
        assertEquals("detaching must really attempt the retired flush", 1, attempts)
        assertFalse(origin.closed)
        vm.onSelectNote("n3")
        runCurrent()
        assertEquals("current navigation must fail while settling the same retired body", 2, attempts)
        assertTrue(vm.state.value.saveFailed)
        assertEquals("n2", vm.state.value.selectedNoteId)
        vm.onImagePickerResult("content://late-picker", "image/png")
        runCurrent()
        assertEquals("the retired picker must attempt its own preflush", 3, attempts)
        assertTrue(attachments.all().isEmpty())
        notes.beforeSave = null
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("a retired picker failure must preserve the current navigation Retry", "n3", vm.state.value.selectedNoteId)
        assertEquals("late outgoing edit", notes.current("n1")?.body)
        assertEquals("second note", notes.current("n2")?.body)
        assertTrue(attachments.all().isEmpty())
        assertTrue(origin.closed)
        assertFalse(vm.state.value.saveFailed)
    }

    /** Plant MUTATION_FAILURE_CANCELS_RETIRED_NAVIGATION: remove current-owner qualification in the catch. */
    @Test fun retiredPickerFailurePreservesTheCurrentPendingSelection() = withOpenNote { vm ->
        val origin = requireNotNull(vm.editorSession)
        vm.onEditorAttached(origin)
        assertTrue(vm.onImagePickerOpened(origin))
        notes.seed(note("n2", "second note"))
        notes.seed(note("n3", "third note"))
        vm.onSelectNote("n2")
        advanceUntilIdle()
        assertTrue(origin !== vm.editorSession && origin.retired && origin.canEdit)
        origin.buffer.setTextAndPlaceCursorAtEnd("late outgoing edit")
        Snapshot.sendApplyNotifications()
        var attempts = 0
        notes.beforeSave = {
            if (it.id == "n1") { attempts++; throw java.io.IOException("controlled attached retired body failure") }
        }
        vm.onSelectNote("n3")
        runCurrent()
        assertEquals("navigation must wait for the still-attached outgoing editor", 0, attempts)
        assertEquals("n2", vm.state.value.selectedNoteId)
        vm.onImagePickerResult("content://late-picker", "image/png")
        runCurrent()
        assertEquals("the retired picker must really fail its preflush", 1, attempts)
        assertTrue(vm.state.value.saveFailed)
        assertTrue(attachments.all().isEmpty())
        notes.beforeSave = null
        vm.onEditorDetached(origin)
        advanceUntilIdle()
        assertEquals("a retired picker failure must not cancel the current pending selection", "n3", vm.state.value.selectedNoteId)
        assertEquals("late outgoing edit", notes.current("n1")?.body)
        assertEquals("second note", notes.current("n2")?.body)
        assertTrue(attachments.all().isEmpty())
        assertTrue(origin.closed)
        assertFalse(vm.state.value.saveFailed)
    }

    /** Recovery control: an already-failed navigation must not replay after a mutation fails. */
    @Test
    fun failedOperationFlushRetiresAnOlderFailedNavigation() = withOpenNote { vm ->
        notes.beforeSave = { throw java.io.IOException("controlled save failure") }
        vm.type("pending #work")
        vm.onFilter(ShellFilter.Archive)
        assertTrue(vm.state.value.saveFailed)
        vm.onRenameTag("work", "job")
        notes.beforeSave = null
        vm.type("latest #work")
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("latest #work", notes.current("n1")?.body)
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals(ShellFilter.All, vm.state.value.filter)
        assertEquals(emptyList<Pair<String, String>>(), tags.renames)
        assertFalse(vm.state.value.saveFailed)
    }

    /** Plant ACTION_FLUSH_CANCEL_SWALLOWED: return false instead of rethrowing cancellation. */
    @Test
    fun cancelledOperationFlushCancelsItsJobWithoutWarningOrMutation() = withOpenNote(body = "hello #work") { vm ->
        var operationJob: Job? = null
        notes.beforeSave = {
            operationJob = currentCoroutineContext()[Job]
            throw CancellationException("controlled operation cancellation")
        }
        vm.type("pending #work")
        vm.onRenameTag("work", "job")
        assertTrue("cancellation must propagate out of the operation", operationJob?.isCancelled == true)
        assertFalse(vm.state.value.saveFailed)
        assertEquals("hello #work", notes.current("n1")?.body)
        assertEquals("pending #work", vm.buffer.text.toString())
        assertEquals(emptyList<Pair<String, String>>(), tags.renames)
        notes.beforeSave = null
        vm.onRenameTag("work", "job")
        advanceUntilIdle()
        assertEquals(listOf("work" to "job"), tags.renames)
    }

    // --- flush precedes every body-rewriting operation ----------------------

    @Test
    fun renameFlushesBeforeTheTransaction() = withOpenNote(body = "a #work b") { vm ->
        vm.type("a #work b edited")
        vm.onRenameTag("work", "job")
        advanceUntilIdle()

        assertTrue(
            "rename must not run before the buffer is flushed",
            writesTo("n1").isNotEmpty() && writesTo("n1").first() == "a #work b edited",
        )
        assertEquals(listOf("work" to "job"), tags.renames)
    }

    @Test
    fun deleteFlushesBeforeTheTransaction() = withOpenNote(body = "a #work b") { vm ->
        vm.type("a #work b edited")
        vm.onDeleteTag("work")
        advanceUntilIdle()

        assertEquals("a #work b edited", writesTo("n1").first())
        assertEquals(listOf("work"), tags.deletes)
    }

    @Test
    fun addTagFlushesBeforeTheTransaction() = withOpenNote { vm ->
        vm.type("hello edited")
        vm.onAddTagToNote("n1", "work")
        advanceUntilIdle()

        assertEquals("hello edited", writesTo("n1").first())
        // The append landed on the flushed text, not on the stale body.
        assertTrue(notes.current("n1")?.body.orEmpty().startsWith("hello edited"))
    }

    /** Plant UNDO_IGNORE_LIVE_CONFLICT: replace the affected.all preflight in onUndo with true. */
    @Test
    fun undoRejectsUnsavedTypingInAnAffectedNoteWithoutDiscardingIt() = withOpenNote(body = "a #work b") { vm ->
        vm.onRenameTag("work", "job")
        advanceUntilIdle()
        notes.writes.clear()

        val typed = "a #job b typed while the snackbar was up"
        vm.type(typed)
        vm.onUndo()
        runCurrent()

        assertTrue("conflict must reject the entire restore", tags.restores.isEmpty())
        assertEquals(OperationFailure.Change, vm.state.value.operationFailure)
        assertEquals(typed, vm.buffer.text.toString())
        assertEquals("a #job b", notes.current("n1")?.body)
        assertTrue(requireNotNull(vm.editorSession).canEdit)
        vm.flush()
        assertEquals(typed, notes.current("n1")?.body)
    }

    /**
     * A rename over a **clean** buffer restamps nothing.
     *
     * `TagRepositoryImpl.writeBody` deliberately does not restamp, so the note list keeps its order
     * through a 200-note rename. That guarantee is worthless if the editor's own autosave then fires
     * on the rewritten buffer and restamps it through `SaveNote`. What prevents it is `rewriteBuffer`
     * applying the transform to `lastWritten` as well: Room's copy underwent exactly that transform,
     * so the buffer lands *clean* and no save follows.
     *
     * The clock jumps a minute first, so a stray save could not possibly be mistaken for no save.
     */
    @Test
    fun aRenameOverACleanBufferDoesNotRestampModifiedAt() = withOpenNote(body = "a #work b") { vm ->
        vm.type("a #work b edited")
        vm.flush()
        val stamped = notes.current("n1")?.modifiedAt
        notes.writes.clear()

        // Precondition, asserted rather than assumed: the buffer really is clean here. Without this
        // the test reads as though it covered the dirty case, and it does not.
        assertEquals(vm.buffer.text.toString(), notes.current("n1")?.body)

        vm.onRenameTag("work", "job")
        advanceUntilIdle()

        assertEquals("a #job b edited", vm.buffer.text.toString())
        // The rename's own body rewrite is expected and inherits the existing stamp. What must not
        // happen is SaveNote minting a NEW one — zero distinct stamps means it never ran.
        assertEquals(
            "the rename restamped via SaveNote",
            emptyList<Long>(),
            mintedStampsAfter("n1", stamped ?: 0L),
        )
        assertEquals(
            "the rename restamped modifiedAt and reordered the list",
            stamped,
            notes.current("n1")?.modifiedAt,
        )
    }

    /**
     * A rename over a **dirty** buffer restamps exactly once — by the flush, which is correct.
     *
     * The distinction matters and the clean-buffer test above cannot show it. Unsaved typing *is* an
     * edit, so the flush that precedes the transaction must restamp; what must not happen is a
     * *second* write afterwards from the buffer rewrite. One write, one stamp, and the stamp is the
     * user's edit rather than the rename.
     */
    @Test
    fun aRenameOverADirtyBufferRestampsOnceForTheEditAndNotAgainForTheRename() =
        withOpenNote(body = "a #work b") { vm ->
            val before = notes.current("n1")?.modifiedAt
            notes.writes.clear()

            vm.type("a #work b edited")
            assertNotEquals(
                "precondition: the buffer must be dirty when the rename fires",
                vm.buffer.text.toString(),
                notes.current("n1")?.body,
            )

            vm.onRenameTag("work", "job")
            advanceUntilIdle()

            assertEquals("a #job b edited", vm.buffer.text.toString())
            // Exactly ONE minted stamp — the flush, carrying the user's edit. The rename's rewrite
            // writes again but inherits that stamp, so it adds no distinct value.
            assertEquals(
                "the flush must mint one stamp and the rename must not mint another",
                1,
                mintedStampsAfter("n1", before ?: 0L).size,
            )
            assertNotEquals("the user's own edit must restamp", before, notes.current("n1")?.modifiedAt)
            assertEquals(
                "the surviving stamp must be the flush's, not a later one",
                mintedStampsAfter("n1", before ?: 0L).single(),
                notes.current("n1")?.modifiedAt,
            )
        }

    /**
     * Plant UNDO_INPUT_NOT_FROZEN: set affected sessions' readOnly to false instead of true.
     * The real flush caller must also remain blocked until restore and adoption both complete.
     */
    @Test
    fun cleanUndoFreezesAffectedInputAndHoldsTheWriteLockUntilAdoption() = withOpenNote(body = "a #work b") { vm ->
        vm.onRenameTag("work", "job")
        advanceUntilIdle()
        assertEquals("a #job b", vm.buffer.text.toString())
        notes.writes.clear()

        val racer = CoroutineScope(dispatcher)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var flushed = false
        tags.afterRestore = {
            entered.complete(Unit)
            racer.launch(start = CoroutineStart.UNDISPATCHED) { vm.flush(); flushed = true }
            release.await()
        }

        vm.onUndo()
        assertTrue(entered.isCompleted)
        assertFalse("flush must wait until adoption", flushed)
        assertFalse("input must be frozen across suspended restore", requireNotNull(vm.editorSession).canEdit)
        release.complete(Unit)
        advanceUntilIdle()
        racer.cancel()

        assertTrue(flushed)
        assertTrue(requireNotNull(vm.editorSession).canEdit)
        assertEquals("a #work b", vm.buffer.text.toString())
        assertEquals("a #work b", notes.current("n1")?.body)
        assertEquals(1, tags.restores.size)
    }

    /**
     * The appended tag survives an unsaved buffer — but by a **chain, not a mechanism**, and this
     * pins the chain.
     *
     * `AddTagToNote` owns the append rule (trimEnd, own line, `TagScanner.token`), so unlike rename
     * and delete there is no `rewriteBuffer` call to reconcile the buffer. What saves it is two
     * separate things lining up: `flush()` leaves the buffer clean, and the sync collector then
     * adopts the appended body. Break either — make adoption conditional, move the flush — and the
     * tag is lost silently, with the write having succeeded.
     *
     * Written as a loss-pinning test first, on the assumption that a dirty buffer would shadow the
     * append. It does not, and finding that out is the reason the `ponytail:` note at the call site
     * now describes a chain rather than a defect.
     */
    @Test
    fun anAppendedTagSurvivesAnUnsavedBufferViaFlushAndAdoption() = withOpenNote { vm ->
        vm.type("dirty text with no tag")
        vm.onAddTagToNote("n1", "later")
        advanceUntilIdle()

        assertTrue(
            "the flush+adoption chain broke and the appended tag was lost: " +
                "buffer=${vm.buffer.text} room=${notes.current("n1")?.body}",
            "#later" in notes.current("n1")?.body.orEmpty(),
        )
        // And the buffer agrees, so the next save cannot overwrite the tag.
        assertTrue("#later" in vm.buffer.text.toString())
        assertTrue("the user's typing was discarded", "dirty text" in vm.buffer.text.toString())
    }

    // --- the two-writer race ------------------------------------------------

    /**
     * **The test rev 1's design would fail.**
     *
     * Adopt-when-clean blocks the adoption but leaves `#work` sitting in a dirty buffer, and the next
     * autosave then writes it back — `saveNoteWithTagsAndLinks` re-derives tags from that body and
     * `ensureTag` mints the row the rename just deleted. The buffer is rewritten with the same
     * `TagScanner` the transaction used, never discarded.
     *
     * `savedTags` is the fake's stand-in for `note_tags`; the real `ensureTag` resurrection is
     * `TagRewriteTest.aRenameSurvivesAnUnsavedEditorBuffer` on device.
     */
    @Test
    fun aDirtyBufferIsRewrittenByARenameAndTheOldTagIsNotResurrected() =
        withOpenNote(body = "a #work b") { vm ->
            vm.type("a #work b and more typing")
            vm.onRenameTag("work", "job")
            advanceUntilIdle()

            assertEquals("a #job b and more typing", vm.buffer.text.toString())
            assertEquals("a #job b and more typing", notes.current("n1")?.body)
            assertFalse(
                "the deleted tag came back through the editor buffer: ${notes.savedTags["n1"]}",
                "work" in notes.savedTags["n1"].orEmpty(),
            )
            assertTrue("job" in notes.savedTags["n1"].orEmpty())
        }

    @Test
    fun aDirtyBufferIsRewrittenByADelete() = withOpenNote(body = "a #work b") { vm ->
        vm.type("a #work b and more typing")
        vm.onDeleteTag("work")
        advanceUntilIdle()

        assertFalse("#work" in vm.buffer.text.toString())
        assertFalse("work" in notes.savedTags["n1"].orEmpty())
    }

    /** The user's own edit survives the rewrite — the rename must not cost them their typing. */
    @Test
    fun theRewritePreservesUnsavedTyping() = withOpenNote(body = "a #work b") { vm ->
        vm.type("a #work b PRECIOUS")
        vm.onRenameTag("work", "job")
        advanceUntilIdle()

        assertTrue("unsaved typing was discarded", "PRECIOUS" in vm.buffer.text.toString())
    }

    // --- adoption ------------------------------------------------------------

    @Test
    fun anExternalChangeIsAdoptedWhenTheBufferIsClean() = withOpenNote { vm ->
        notes.seed(note("n1", "changed from elsewhere"))
        advanceUntilIdle()

        assertEquals("changed from elsewhere", vm.buffer.text.toString())
    }

    @Test
    fun anExternalChangeIsIgnoredWhenTheBufferIsDirty() = withOpenNote { vm ->
        vm.type("mine")
        notes.seed(note("n1", "theirs"))
        advanceTimeBy(1)

        assertEquals("mine", vm.buffer.text.toString())
    }

    /** Plant UNDO_NO_EXPECTED_BODIES: pass null instead of pending.expectedBodies to UndoTagChange. */
    @Test
    fun undoRejectsAutosavedTypingEvenAfterTheAffectedEditorCloses() = withOpenNote(body = "a #work b") { vm ->
        notes.seed(note("n2", "unrelated"))
        vm.onRenameTag("work", "job")
        advanceUntilIdle()
        assertEquals("a #job b", vm.buffer.text.toString())

        val typed = "a #job b typed after the rename"
        vm.type(typed)
        advanceUntilIdle()
        assertEquals(typed, notes.current("n1")?.body)
        vm.onSelectNote("n2")
        advanceUntilIdle()
        vm.onUndo()
        advanceUntilIdle()

        assertTrue(tags.restores.isEmpty())
        assertEquals(OperationFailure.Change, vm.state.value.operationFailure)
        assertEquals(typed, notes.current("n1")?.body)
        assertEquals("unrelated", vm.buffer.text.toString())
    }

    /** Same live conflict guard must protect delete-tag Undo, not only rename. */
    @Test fun deleteTagUndoRejectsLaterTyping() = withOpenNote(body = "a #work b") { vm ->
        vm.onDeleteTag("work")
        advanceUntilIdle()
        val typed = vm.buffer.text.toString() + " later paragraph"
        vm.type(typed)
        vm.onUndo()
        advanceUntilIdle()
        assertTrue(tags.restores.isEmpty())
        assertEquals(OperationFailure.Change, vm.state.value.operationFailure)
        assertEquals(typed, vm.buffer.text.toString())
        assertEquals(typed, notes.current("n1")?.body)
    }

    /** Plant UNDO_ALL_SESSIONS: remove the snapshot.bodies membership filter from onUndo. */
    @Test fun undoOfAnotherNotesTagChangePreservesUnrelatedUnsavedTyping() = withOpenNote(body = "unrelated") { vm ->
        notes.seed(note("tagged", "tagged #work"), listOf("work"))
        vm.onRenameTag("work", "job")
        advanceUntilIdle()
        vm.type("unrelated with new typing")
        vm.onUndo()
        runCurrent()
        assertEquals("unrelated with new typing", vm.buffer.text.toString())
        advanceUntilIdle()
        assertEquals("unrelated with new typing", notes.current("n1")?.body)
        assertEquals("tagged #work", notes.current("tagged")?.body)
    }

    /** Plant OBSERVER_QUEUED_BODY: use the captured emission instead of refreshing under writeLock. */
    @Test fun queuedOldEmissionCannotReplaceCommittedBodyWhileNewEmissionIsDelayed() = withOpenNote(body = "initial #work") { vm ->
        val committing = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val observedNew = CompletableDeferred<Unit>()
        val releaseNewEmission = CompletableDeferred<Unit>()
        notes.beforeSave = { if (it.body.contains("#job")) { committing.complete(Unit); releaseCommit.await() } }
        notes.beforeObservedUpdate = { if (it?.body?.contains("#job") == true) { observedNew.complete(Unit); releaseNewEmission.await() } }
        vm.type("typed #work")
        vm.onRenameTag("work", "job")
        runCurrent()
        assertTrue(committing.isCompleted)
        releaseCommit.complete(Unit)
        runCurrent()
        assertTrue(observedNew.isCompleted)
        assertEquals("typed #job", vm.buffer.text.toString())
        vm.type("typed #job plus more")
        releaseNewEmission.complete(Unit)
        notes.beforeSave = null
        advanceUntilIdle()
        assertEquals("typed #job plus more", notes.current("n1")?.body)
    }

    /** Plant SELECT_CAPTURED_BODY: publish the row captured before acquiring the write lock. */
    @Test fun incomingSelectionUsesTheBodyCommittedWhileItWaitedForTheWriteLock() = withOpenNote(body = "unrelated") { vm ->
        notes.seed(note("n2", "incoming #work"), listOf("work"))
        val captured = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val committing = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Unit>()
        val releaseObserver = CompletableDeferred<Unit>()
        var held = false
        notes.beforeInitialObservation = { note ->
            if (note?.id == "n2" && !held) {
                held = true
                captured.complete(Unit)
                releaseRead.await()
            } else if (note?.id == "n2" && vm.editorSession?.noteId == "n2") {
                observed.complete(Unit)
                releaseObserver.await()
            }
        }
        notes.beforeSave = { note ->
            if (note.id == "n2" && note.body.contains("#job")) {
                committing.complete(Unit)
                releaseCommit.await()
            }
        }
        try {
            vm.onSelectNote("n2")
            runCurrent()
            assertTrue(captured.isCompleted)
            vm.onRenameTag("work", "job")
            runCurrent()
            assertTrue(committing.isCompleted)
            releaseRead.complete(Unit)
            runCurrent()
            assertEquals("n1", vm.state.value.selectedNoteId)
            releaseCommit.complete(Unit)
            runCurrent()
            assertTrue(observed.isCompleted)
            assertEquals("incoming #job", vm.buffer.text.toString())
            vm.type("incoming #job with more typing")
            releaseObserver.complete(Unit)
            notes.beforeSave = null
            advanceUntilIdle()
            assertEquals("incoming #job with more typing", notes.current("n2")?.body)
        } finally {
            notes.beforeInitialObservation = null
            notes.beforeSave = null
            releaseRead.complete(Unit)
            releaseCommit.complete(Unit)
            releaseObserver.complete(Unit)
        }
    }

    /** Plant SELECT_MISSING_PUBLISHED: publish the earlier row when the locked reread is null. */
    @Test fun vanishedIncomingSelectionPreservesTheEditorAndQueryAndReportsOpenFailure() = withOpenNote { vm ->
        notes.seed(note("n2", "second"))
        vm.onSearchChanged("hello")
        advanceTimeBy(200)
        runCurrent()
        val origin = requireNotNull(vm.editorSession)
        val captured = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var held = false
        notes.beforeInitialObservation = { note ->
            if (note?.id == "n2" && !held) {
                held = true
                captured.complete(Unit)
                releaseRead.await()
            }
        }
        try {
            vm.onSelectNote("n2")
            runCurrent()
            assertTrue(captured.isCompleted)
            notes.hardDelete("n2")
            releaseRead.complete(Unit)
            runCurrent()
            assertTrue(origin === vm.editorSession)
            assertEquals("n1", vm.state.value.selectedNoteId)
            assertEquals("hello", vm.state.value.search.query)
            assertEquals(OperationFailure.Open, vm.state.value.operationFailure)
            assertFalse(vm.state.value.saveFailed)
        } finally {
            notes.beforeInitialObservation = null
            releaseRead.complete(Unit)
        }
    }

    /** Plant SELECT_REVISION_AFTER_READ_MISSING: omit the revision check after the locked reread. */
    @Test fun supersededIncomingRereadCannotReplaceNewerNavigationOrLoseOutgoingTyping() = withOpenNote { vm ->
        notes.seed(note("n2", "second"))
        val origin = requireNotNull(vm.editorSession)
        vm.onEditorAttached(origin)
        val reading = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        var initial = true
        notes.beforeInitialObservation = { note ->
            if (note?.id == "n2") {
                if (initial) initial = false
                else if (vm.editorSession === origin) {
                    reading.complete(Unit)
                    releaseRead.await()
                }
            }
        }
        try {
            vm.onSelectNote("n2")
            runCurrent()
            assertTrue("incoming selection must reread before publication", reading.isCompleted)
            assertTrue(origin === vm.editorSession)
            vm.type("typing during incoming reread")
            vm.onClearSelection()
            runCurrent()
            releaseRead.complete(Unit)
            advanceUntilIdle()
            assertEquals(null, vm.state.value.selectedNoteId)
            assertEquals("typing during incoming reread", notes.current("n1")?.body)
        } finally {
            notes.beforeInitialObservation = null
            releaseRead.complete(Unit)
            vm.onEditorDetached(origin)
        }
    }

    /** Plant STATUS_CURRENT_SESSION: use editorSession instead of the supplied originating session. */
    @Test fun lateStatusCallbacksCannotChangeAnotherNote() = withOpenNote { vm ->
        val outgoing = requireNotNull(vm.editorSession)
        notes.seed(note("n2", "second"))
        vm.onSelectNote("n2")
        advanceUntilIdle()
        vm.onArchiveNote(outgoing)
        vm.onTrashNote(outgoing)
        vm.onRestoreNote(outgoing)
        advanceUntilIdle()
        assertFalse(requireNotNull(notes.current("n2")).isArchived)
        assertFalse(requireNotNull(notes.current("n2")).isTrashed)
        assertEquals("second", vm.buffer.text.toString())
    }

    // --- id/text atomicity ---------------------------------------------------

    @Test
    fun selectingAnotherNoteFlushesTheOutgoingOne() = withOpenNote { vm ->
        notes.seed(note("n2", "second"))
        vm.type("unsaved edit to n1")
        vm.onSelectNote("n2")
        advanceUntilIdle()

        assertEquals(listOf("unsaved edit to n1"), writesTo("n1"))
        assertEquals("second", vm.buffer.text.toString())
    }

    /**
     * Item 4 of the review: an in-flight save must never land on the newly selected note.
     *
     * `flatMapLatest` on the id is what makes this safe — a note switch cancels the collector, so an
     * emission produced while n1 was open cannot be delivered after the swap. The `id != loadedId`
     * guard in `save` is the second lock on the same door.
     */
    @Test
    fun anInFlightSaveCannotLandOnTheNewlySelectedNote() = withOpenNote { vm ->
        notes.seed(note("n2", "second"))
        vm.type("belongs to n1")
        advanceTimeBy(200) // inside the idle window: the save has not fired yet
        vm.onSelectNote("n2")
        advanceUntilIdle()

        assertEquals(listOf("belongs to n1"), writesTo("n1"))
        assertEquals(
            "n1's text was written onto n2: ${writesTo("n2")}",
            emptyList<String>(),
            writesTo("n2").filter { it == "belongs to n1" },
        )
    }

    /** A buffer with no note loaded has nothing to write to. */
    @Test
    fun nothingIsWrittenWhenNoNoteIsLoaded() = runTest {
        val vm = shell()
        val scope = CoroutineScope(dispatcher)
        scope.launch { vm.state.collect {} }
        advanceUntilIdle()
        notes.writes.clear()

        vm.type("orphan text")
        vm.flush()
        advanceUntilIdle()

        assertEquals(emptyList<Pair<String, String>>(), notes.writes)
        scope.cancel()
    }

    // --- the in-progress tag -------------------------------------------------

    /** Every tag list ever written for [id], flattened — not just the one that survived. */
    private fun tagsEverWrittenTo(id: String) =
        notes.writtenTags.filter { it.first == id }.flatMap { it.second }.distinct()

    /** Types [text] and leaves the caret inside it, at [caret], rather than at the end. */
    private fun DuckShellViewModel.typeWithCaret(text: String, caret: Int) {
        buffer.edit {
            replace(0, length, text)
            selection = TextRange(caret)
        }
        Snapshot.sendApplyNotifications()
    }

    /**
     * Types [text] and leaves a **range** selected, so `save` sees a non-collapsed selection.
     *
     * The text and the selection have to move in the **same** edit, and that is the whole reason
     * this exists rather than a bare `buffer.edit { selection = … }`. The autosave loop watches
     * `snapshotFlow { buffer.text }`, so a selection change on its own emits nothing and no save
     * runs at all — see [aRangeSelectionOverATagSuppressesNothing], which is the test that was
     * written that way and could not reach the guard it was named for.
     */
    private fun DuckShellViewModel.typeWithSelection(text: String, start: Int, end: Int) {
        buffer.edit {
            replace(0, length, text)
            selection = TextRange(start, end)
        }
        Snapshot.sendApplyNotifications()
    }

    /**
     * **The reported defect.** Typing `#work` one character at a time, pausing past the idle window
     * on each, used to mint `w`, `wo` and `wor` as real tag rows — each visible in the sidebar for
     * the moment it existed.
     *
     * Asserts against every tag list ever written, not the final one: a partial loses its only
     * reference on the next save and is reaped in the same transaction, so the resting state is
     * clean whether or not the fix is present. That is why the report and the database inspector
     * looked like they contradicted each other.
     */
    @Test
    fun typingATagOneCharacterAtATimeNeverMintsAPartial() = withOpenNote(body = "") { vm ->
        "#work".forEachIndexed { i, _ ->
            vm.type("#work".substring(0, i + 1))
            advanceTimeBy(DuckShellViewModel.IDLE_MS + 1) // fire the debounce on every prefix
        }
        advanceUntilIdle()

        assertEquals(
            "a partial tag was minted: ${notes.writtenTags}",
            emptyList<String>(),
            tagsEverWrittenTo("n1"),
        )
        // The body is still durable throughout — suppression drops the tag, never the text.
        assertEquals("#work", notes.current("n1")?.body)
    }

    /**
     * The control on the other side. Suppression that never releases would be indistinguishable from
     * "tags are broken", and this is the ordinary way a tag finishes: one more character.
     */
    @Test
    fun aTagFollowedByASpaceIsMintedByTheVeryNextSave() = withOpenNote(body = "") { vm ->
        vm.type("#work ")
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()

        assertEquals(listOf("work"), notes.savedTags["n1"])
    }

    /**
     * A tag typed and left with the caret parked on it is minted by [DuckShellViewModel.flush] —
     * `ON_STOP`, a note switch, or any intent that rewrites bodies.
     *
     * **The text does not change between the two writes**, so the `lastWritten` gate would swallow
     * this one; `oweFullWrite` is what lets it through. Without it the tag would appear only after
     * some later, unrelated edit.
     */
    @Test
    fun aTagLeftUnderTheCaretIsMintedByTheFlush() = withOpenNote(body = "") { vm ->
        vm.type("#work")
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()
        assertEquals("precondition: the autosave suppressed it", emptyList<String>(), notes.savedTags["n1"])

        vm.flush()
        advanceUntilIdle()

        assertEquals(listOf("work"), notes.savedTags["n1"])
    }

    /**
     * ...and that flush does **not** age the note. The keystrokes were already stamped by the write
     * that suppressed the tag; stamping again would move `modifiedAt` by however long the caret sat
     * parked, for no new edit. Same rule Phase 7 applies when a rename declines to restamp.
     *
     * One *minted* stamp, not one write — the completion write lands either way, and only the clock
     * distinguishes them.
     */
    @Test
    fun mintingTheTagOnFlushDoesNotRestampModifiedAt() = withOpenNote(body = "") { vm ->
        val baseline = notes.current("n1")?.modifiedAt ?: 0L
        vm.type("#work")
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()
        val afterTyping = mintedStampsAfter("n1", baseline)
        assertEquals("precondition: typing restamps once", 1, afterTyping.size)

        vm.flush()
        advanceUntilIdle()

        assertEquals(
            "the tag completion restamped a second time for one edit",
            afterTyping,
            mintedStampsAfter("n1", baseline),
        )
    }

    /**
     * **`oweFullWrite` is per-note.** Left standing across a note switch, the next note's first
     * flush bypasses its own `lastWritten` gate and writes a note the user only opened.
     *
     * Filter departure must pay the outgoing debt without making the next clean note owe a write.
     */
    @Test
    fun anOwedTagWriteDoesNotFollowTheUserToTheNextNote() = withOpenNote(body = "") { vm ->
        notes.seed(note("n2", "second"))
        vm.type("#work")
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()
        assertTrue("precondition: the write was suppressed", notes.savedTags["n1"].isNullOrEmpty())

        vm.onFilter(ShellFilter.All)
        advanceUntilIdle()
        assertEquals(listOf("work"), notes.savedTags["n1"])
        val baseline = notes.current("n2")?.modifiedAt ?: 0L
        vm.onSelectNote("n2")
        advanceUntilIdle()
        notes.writes.clear()

        vm.flush()
        advanceUntilIdle()

        assertEquals("n2 was written by a debt n1 ran up", emptyList<String>(), writesTo("n2"))
        assertEquals(emptyList<Long>(), mintedStampsAfter("n2", baseline))
    }

    /** Only the tag at the caret is dropped. A second tag elsewhere in the body is finished. */
    @Test
    fun anotherTagInTheSameBodyIsStillMinted() = withOpenNote(body = "") { vm ->
        vm.type("#work and #home")
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()

        assertEquals(listOf("work"), notes.savedTags["n1"])
    }

    /**
     * A range selection is someone selecting, not someone typing: nothing is in progress.
     *
     * ## What this replaces, and why the old one could not fail
     *
     * `save` suppresses only when `suppressInProgressTag && selection.collapsed`, and this test is
     * named for that second conjunct. The previous version never reached it, by three separate
     * routes — deleting `&& selection.collapsed` left the whole suite green:
     *
     * 1. it moved the selection with a bare `buffer.edit { selection = … }`, which changes no text,
     *    so `snapshotFlow { buffer.text }` never emitted and **no save ran at all**;
     * 2. the `type("#work!")` that followed minted the tag because the typed `!` put the caret at 6
     *    against a span ending at 5 — arithmetic, not any selection rule;
     * 3. and the closing `flush()` passes `suppressInProgressTag = false`, so `caret` is null
     *    whatever the selection is.
     *
     * So the text and the selection now move in **one** edit, via [typeWithSelection]. The offset is
     * held fixed across both halves and only the collapsed-ness changes, which is what makes the
     * conjunct the single variable.
     *
     * The case it now covers is the one nothing covered: a range whose `start` lands exactly on a
     * **finished** tag's span end. Without the guard `ParseTags` drops the span whose `end == caret`
     * and a completed tag is silently never minted.
     */
    @Test
    fun aRangeSelectionOverATagSuppressesNothing() = withOpenNote(body = "") { vm ->
        // A COLLAPSED caret at offset 5 — the end of `#work` — suppresses. The control for the half
        // below, and the reason the offset rather than the text is what has to stay fixed.
        vm.typeWithCaret("#work more", caret = 5)
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()
        assertEquals(
            "precondition: a collapsed caret at the span end suppresses",
            emptyList<String>(),
            notes.savedTags["n1"],
        )

        // The same offset 5, now the START of a range. Only `selection.collapsed` differs.
        vm.typeWithSelection("#work more!", start = 5, end = 11)
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()

        assertEquals(
            "a range selection starting at a finished tag's span end suppressed it — selecting is " +
                "not typing, and there is nothing in progress to wait for",
            listOf("work"),
            notes.savedTags["n1"],
        )
    }

    /** The caret suppresses; it never moves the text. */
    @Test
    fun suppressionNeverChangesTheBodyThatIsWritten() = withOpenNote(body = "") { vm ->
        vm.typeWithCaret("#work in progress", caret = 5)
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()

        assertEquals(listOf("#work in progress"), writesTo("n1"))
        assertEquals(emptyList<String>(), tagsEverWrittenTo("n1"))
    }

    // --- Phase 8c: the format toolbar is the third writer, and needs no new path ----

    /**
     * **A toolbar press is an ordinary edit, and this is what "ordinary" has to mean.**
     *
     * The toolbar does not go through the ViewModel at all — it calls `TextFieldState.edit`, the same
     * function the keyboard drives. So no new write path, no `flush()`, no lock, and no entry point
     * on [DuckShellViewModel]: the debounce that already watches the buffer picks it up, and
     * [DuckShellViewModel.save] takes no payload precisely so it reads whatever is there.
     *
     * It calls the **real** `applyFormat`, which is `internal` for that reason. Re-typing its four
     * lines here would give a test that keeps passing while the toolbar rots — the Misdirected shape
     * this suite's own audit turned up twice.
     *
     * Routing it through `rewriteBuffer` instead is impossible rather than merely discouraged:
     * that function is `private` to the ViewModel, so the compiler holds this line and no test needs
     * to. What this asserts is the half a compiler cannot — that the edit reaches **Room**.
     */
    @Test
    fun aToolbarFormatReachesRoomThroughTheOrdinaryDebounce() = withOpenNote(body = "hello") { vm ->
        vm.buffer.edit { selection = TextRange(0, 5) }
        Snapshot.sendApplyNotifications()

        vm.buffer.applyFormat(FormatAction.BOLD)
        Snapshot.sendApplyNotifications()
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()

        assertEquals(listOf("**hello**"), writesTo("n1"))
    }

    /** And it costs one write, not one per touched line or one plus a flush. */
    @Test
    fun aToolbarFormatCostsExactlyOneSave() = withOpenNote(body = "one\ntwo") { vm ->
        // The note's own stamp, not the raw clock counter: `nowSource` holds the value the clock will
        // hand out *next*, so a save minting exactly it fails a strict `>` and reports zero saves.
        val baseline = notes.current("n1")?.modifiedAt ?: 0L
        vm.buffer.edit { selection = TextRange(0, 7) }
        Snapshot.sendApplyNotifications()

        vm.buffer.applyFormat(FormatAction.BULLET)
        Snapshot.sendApplyNotifications()
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()

        assertEquals(listOf("- one\n- two"), writesTo("n1"))
        assertEquals(1, mintedStampsAfter("n1", baseline).size)
    }

    /**
     * The task overlay is the **third** caller of `state.edit` — keyboard, toolbar, checkbox — and
     * deliberately not a third write path. Same argument as the two toolbar tests above, and the
     * same reason it drives the real `toggleTask`: a retyped copy would keep passing while the
     * production one rotted.
     *
     * It also asserts the caret, which the toolbar tests do not, because *the caret must not move*
     * is half of what makes a checkbox tap a checkbox tap rather than a caret placement.
     *
     * **The caret is asserted both before and after the debounce.** Before proves `toggleTask` itself
     * never moves it. After is what used to be a separate pinned defect —
     * `aSavedBufferIsReAdoptedAndTheCaretJumpsToTheEnd`, now deleted — where `syncBuffer` re-adopted
     * the note Room echoed back and `adopt` re-clamped the caret to the end on every autosave, not
     * just a real external change. `adopt` now skips the re-clamp when nothing actually changed; this
     * is the test that would catch a regression of that guard.
     */
    @Test
    fun aTaskToggleReachesRoomAndLeavesTheCaret() = withOpenNote(body = "- [ ] milk\n\ntail") { vm ->
        val baseline = notes.current("n1")?.modifiedAt ?: 0L
        val caret = "- [ ] milk\n\nta".length
        vm.buffer.edit { selection = TextRange(caret) }
        Snapshot.sendApplyNotifications()

        vm.buffer.toggleTask(boxesOf(vm.buffer.text.toString(), caret).single())
        assertEquals("a checkbox tap must not move the caret", caret, vm.buffer.selection.start)

        Snapshot.sendApplyNotifications()
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()

        assertEquals(listOf("- [x] milk\n\ntail"), writesTo("n1"))
        assertEquals(1, mintedStampsAfter("n1", baseline).size)
        assertEquals("the caret must survive the autosave echo too", caret, vm.buffer.selection.start)
    }

    /** The boxes the editor would show for [body] with the caret at [caret]. */
    private fun boxesOf(body: String, caret: Int) =
        MarkdownTextCache.rescan(ScanResult.Empty, body).tokens.let { tokens ->
            SpanPlanner.taskBoxes(body, tokens, SpanPlanner.plan(tokens, caret, caret))
        }

    // --- following a wiki link (9d) -------------------------------------------

    /**
     * **Following a link flushes first, so the tap cannot lose the last keystrokes.**
     *
     * A tap navigates away from a buffer that may hold up to `MAX_WAIT_MS` of unwritten typing. The
     * debounce alone would not have fired, so without the flush those words are gone — and the user's
     * gesture was "open that note", not "discard this one". Removing `flush()` from `onOpenWikiLink`
     * is the plant; the navigation still works, which is what makes this invisible without the test.
     *
     * **The link must RESOLVE for this test to isolate the flush**, and the first draft got that
     * wrong. On the *create* path `OpenWikiLink` re-saves the source to relink it, reading the live
     * buffer — so the typed text reaches Room through the relink even with no flush at all, and the
     * plant comes back green. Seeding the target removes the relink save, leaving the flush as the
     * only thing that can write, which is the property being claimed.
     */
    @Test
    fun followingALinkFlushesTheBufferFirst() = withOpenNote { vm ->
        // The local `note` helper sets title = id, so the id IS the title the link resolves by.
        notes.seed(note("Pond", "the pond note"))
        vm.type("unsaved words and [[Pond]]")

        vm.onOpenWikiLink("Pond")
        advanceUntilIdle()

        assertEquals(
            "the buffer's text must reach Room before the selection moves",
            listOf("unsaved words and [[Pond]]"),
            writesTo("n1"),
        )
    }

    /**
     * The flush happens **before** the body is handed over for relinking, so the created note's
     * backlink is derived from the text the user can see rather than the last debounced write.
     *
     * Reading `buffer` before the flush would relink a stale body — the link the user just typed
     * would not be among the source's rows, which is the same empty-panel failure the relink exists
     * to prevent, arrived at from the other side.
     */
    @Test
    fun followingALinkRelinksTheSourceFromTheFlushedBody() = withOpenNote { vm ->
        vm.type("unsaved words and [[Pond]]")

        vm.onOpenWikiLink("Pond")
        advanceUntilIdle()

        assertEquals(listOf("Pond"), notes.savedLinks["n1"])
    }

    /** And the selection actually moves to the created note. */
    @Test
    fun followingAnUnresolvedLinkOpensTheCreatedNote() = withOpenNote { vm ->
        vm.type("see [[Pond]]")

        vm.onOpenWikiLink("Pond")
        advanceUntilIdle()

        val opened = vm.state.value.selectedNoteId
        assertEquals("Pond", notes.current(requireNotNull(opened))?.title)
    }

    /** Plant PICKER_KEEP_CANCELLED_OWNER: stop clearing pickerSession on a null result. */
    @Test fun pickerCancellationAndResultConsumeTheirOwnerExactlyOnce() = withOpenNote { vm ->
        val origin = requireNotNull(vm.editorSession)
        assertTrue(vm.onImagePickerOpened(origin))
        assertFalse("a pending launch cannot be superseded", vm.onImagePickerOpened(origin))
        vm.onImagePickerResult(null, "image/png")
        vm.onImagePickerResult("content://cancelled", "image/png")
        advanceUntilIdle()
        assertTrue(attachments.all().isEmpty())
        assertTrue(vm.onImagePickerOpened(origin))
        vm.onImagePickerResult("content://picked", "image/png")
        advanceUntilIdle()
        assertEquals("n1", attachments.all().single().noteId)
        vm.onImagePickerResult("content://duplicate", "image/png")
        advanceUntilIdle()
        assertEquals(1, attachments.all().size)
    }

    /** Plant PICKER_CURRENT_SELECTION: pass editorSession instead of the captured origin to onAttachImages. */
    @Test fun pickerResultAfterNavigationNeverAttachesToTheNewSelection() = withOpenNote { vm ->
        val origin = requireNotNull(vm.editorSession)
        assertTrue(vm.onImagePickerOpened(origin))
        notes.seed(note("n2", "second"))
        vm.onSelectNote("n2")
        advanceUntilIdle()
        assertTrue(origin.closed)
        assertFalse(vm.onImagePickerOpened(requireNotNull(vm.editorSession)))
        vm.onImagePickerResult("content://old-owner", "image/png")
        advanceUntilIdle()
        assertTrue(attachments.all().isEmpty())
        assertEquals("hello", notes.current("n1")?.body)
        assertEquals("second", notes.current("n2")?.body)
        assertTrue(vm.onImagePickerOpened(requireNotNull(vm.editorSession)))
        vm.onImagePickerResult("content://new-owner", "image/png")
        advanceUntilIdle()
        assertEquals("n2", attachments.all().single().noteId)
    }

    /** Plant SESSION_REUSE_BUFFER: publish the outgoing TextFieldState as the incoming session. */
    @Test fun aDetachedOutgoingEditorSavesLateTypingOnlyToItsOwnNote() = withOpenNote { vm ->
        notes.seed(note("n2", "second"))
        val outgoing = requireNotNull(vm.editorSession)
        vm.onEditorAttached(outgoing)
        vm.onSelectNote("n2")
        advanceUntilIdle()
        assertEquals("n2", vm.state.value.selectedNoteId)
        assertTrue(outgoing.buffer !== vm.buffer)
        outgoing.buffer.setTextAndPlaceCursorAtEnd("late input for first")
        Snapshot.sendApplyNotifications()
        vm.onEditorDetached(outgoing)
        advanceUntilIdle()
        assertEquals("late input for first", notes.current("n1")?.body)
        assertEquals("second", notes.current("n2")?.body)
        assertTrue(outgoing.closed)
        vm.onAttachImages(outgoing, listOf("content://late" to "image/png"))
        advanceUntilIdle()
        assertTrue(attachments.all().isEmpty())
    }

    /** Plant RETIRED_DROP_FAILURE: close the retired session before flush. */
    @Test fun aFailedFinalOutgoingSaveRetainsTextAndNoteSpecificRetry() = withOpenNote { vm ->
        notes.seed(note("n2", "second"))
        val outgoing = requireNotNull(vm.editorSession)
        vm.onEditorAttached(outgoing)
        vm.onSelectNote("n2")
        advanceUntilIdle()
        outgoing.buffer.setTextAndPlaceCursorAtEnd("recover outgoing")
        Snapshot.sendApplyNotifications()
        notes.beforeSave = { if (it.id == "n1") throw java.io.IOException("controlled") }
        vm.onEditorDetached(outgoing)
        advanceUntilIdle()
        assertTrue(vm.state.value.saveFailed)
        assertFalse(outgoing.closed)
        assertEquals("n2", vm.state.value.selectedNoteId)
        notes.beforeSave = null
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("recover outgoing", notes.current("n1")?.body)
        assertEquals("second", notes.current("n2")?.body)
        assertFalse(vm.state.value.saveFailed)
        assertTrue(outgoing.closed)
    }

    /** Plant CREATE_NO_BUSY_GUARD: remove the synchronous creating check. */
    @Test fun repeatedNewNoteTapsCreateOneBlankAndRetryNeverRecreatesIt() = withOpenNote { vm ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        notes.beforeSave = { if (it.id != "n1") { entered.complete(Unit); release.await() } }
        vm.onSearchChanged("hello")
        advanceUntilIdle()
        vm.onNewNote()
        assertTrue(entered.isCompleted)
        repeat(3) { vm.onNewNote() }
        vm.type("typing during creation")
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, notes.all().size)
        val created = notes.all().single { it.id != "n1" }
        assertEquals("", created.body)
        assertEquals("Untitled", created.title)
        assertEquals(created.id, vm.state.value.selectedNoteId)
        assertEquals("typing during creation", notes.current("n1")?.body)
        assertEquals("", vm.state.value.search.query)
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals(2, notes.all().size)
    }

    @Test fun creationFailurePreservesContextAndDoesNotReplayFromRetry() = withOpenNote { vm ->
        vm.onSearchChanged("hello")
        advanceUntilIdle()
        notes.beforeSave = { if (it.id != "n1") throw java.io.IOException("controlled create failure") }
        vm.onNewNote()
        advanceUntilIdle()
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals("hello", vm.state.value.search.query)
        assertEquals(OperationFailure.Create, vm.state.value.operationFailure)
        notes.beforeSave = null
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals(1, notes.all().size)
    }

    /** Plant CREATE_FORGET_COMMITTED: stop retaining the UUID returned by the blank insertion. */
    @Test fun postInsertionSaveFailureRetryOpensTheSameBlankWithoutCreatingAgain() = assertPostInsertionFailure(retry = true)

    @Test fun postInsertionSaveFailureNewFinishesTheSameCreation() = assertPostInsertionFailure(retry = false)

    private fun assertPostInsertionFailure(retry: Boolean) = withOpenNote { vm ->
        vm.onSearchChanged("hello")
        advanceUntilIdle()
        val inserted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        notes.beforeSave = {
            if (it.id == "n1") throw java.io.IOException("controlled outgoing failure")
            inserted.complete(Unit)
            release.await()
        }
        vm.onNewNote()
        assertTrue(inserted.isCompleted)
        vm.type("late outgoing words")
        release.complete(Unit)
        advanceUntilIdle()
        val created = notes.all().single { it.id != "n1" }
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals("hello", vm.state.value.search.query)
        assertTrue(vm.state.value.saveFailed)
        notes.beforeSave = null
        if (retry) vm.onRetryNavigation() else vm.onNewNote()
        advanceUntilIdle()
        assertEquals(2, notes.all().size)
        assertEquals(created.id, vm.state.value.selectedNoteId)
        assertEquals("late outgoing words", notes.current("n1")?.body)
        assertEquals("", vm.state.value.search.query)
        assertFalse(vm.state.value.saveFailed)
    }

    /** Plant OPEN_AS_SAVE_FAILURE: set failedSession to the origin for an action exception. */
    @Test fun wikiCreationFailureIsAnOperationFailureAndTheNextTapCanSucceed() = withOpenNote { vm ->
        notes.beforeSave = { if (it.id != "n1") throw java.io.IOException("controlled target creation failure") }
        vm.onOpenWikiLink("Pond")
        advanceUntilIdle()
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertFalse("target failure is not a source save failure", vm.state.value.saveFailed)
        assertEquals(OperationFailure.Open, vm.state.value.operationFailure)
        notes.beforeSave = null
        vm.onOpenWikiLink("Pond")
        advanceUntilIdle()
        assertEquals("Pond", notes.current(requireNotNull(vm.state.value.selectedNoteId))?.body)
        assertEquals(null, vm.state.value.operationFailure)
    }

    @Test fun supersededInsertionKeepsSelectionAndManualSelectionConsumesItsCommittedUuid() = withOpenNote { vm ->
        notes.seed(note("n2", "second"))
        val release = CompletableDeferred<Unit>()
        notes.beforeSave = { if (it.id !in setOf("n1", "n2")) release.await() }
        vm.onNewNote()
        vm.onSelectNote("n2")
        advanceUntilIdle()
        assertEquals("n2", vm.state.value.selectedNoteId)
        release.complete(Unit)
        advanceUntilIdle()
        val created = notes.all().single { it.id !in setOf("n1", "n2") }
        assertEquals("n2", vm.state.value.selectedNoteId)
        vm.onSelectNote(created.id)
        advanceUntilIdle()
        assertEquals(3, notes.all().size)
        assertEquals(created.id, vm.state.value.selectedNoteId)
        vm.onNewNote()
        advanceUntilIdle()
        assertEquals(4, notes.all().size)
        assertNotEquals(created.id, vm.state.value.selectedNoteId)
    }

    @Test fun trashedPendingRetryPreservesContextAndNeverRecreatesOrRestores() = withOpenNote { vm ->
        val release = CompletableDeferred<Unit>()
        notes.beforeSave = {
            if (it.id == "n1") throw java.io.IOException("controlled outgoing failure")
            release.await()
        }
        vm.onSearchChanged("hello")
        advanceUntilIdle()
        vm.onNewNote()
        vm.type("late outgoing words")
        release.complete(Unit)
        advanceUntilIdle()
        val created = notes.all().single { it.id != "n1" }
        notes.seed(created.copy(isTrashed = true))
        notes.beforeSave = null
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals("hello", vm.state.value.search.query)
        assertTrue(requireNotNull(notes.current(created.id)).isTrashed)
        assertEquals(2, notes.all().size)
        vm.onNewNote()
        advanceUntilIdle()
        val fresh = requireNotNull(vm.state.value.selectedNoteId)
        assertNotEquals(created.id, fresh)
        assertEquals(3, notes.all().size)
    }

    /** Plant CREATION_PENDING_NEW_TRASH_ALLOWED: allow New to reopen a trashed pending UUID. */
    @Test fun newAgainstATrashedPendingCreationPreservesContextBeforeANewExplicitAttempt() = withOpenNote { vm ->
        val release = CompletableDeferred<Unit>()
        notes.beforeSave = {
            if (it.id == "n1") throw java.io.IOException("controlled outgoing failure")
            release.await()
        }
        vm.onSearchChanged("hello")
        advanceUntilIdle()
        vm.onNewNote()
        vm.type("late outgoing words")
        release.complete(Unit)
        advanceUntilIdle()
        val created = notes.all().single { it.id != "n1" }
        notes.seed(created.copy(isTrashed = true))
        notes.beforeSave = null
        vm.onNewNote()
        advanceUntilIdle()
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals("hello", vm.state.value.search.query)
        assertTrue(requireNotNull(notes.current(created.id)).isTrashed)
        assertEquals(2, notes.all().size)
        assertEquals(OperationFailure.Create, vm.state.value.operationFailure)
        vm.onNewNote()
        advanceUntilIdle()
        assertNotEquals(created.id, vm.state.value.selectedNoteId)
        assertEquals(3, notes.all().size)
    }

    /** Plant SEARCH_DELAY_201: change the production delay literal from200 to201. */
    @Test fun basicSearchWaitsExactly200AndIncludesArchiveButExcludesTrash() = withOpenNote { vm ->
        notes.seed(note("archive", "needle archived").copy(isArchived = true))
        notes.seed(note("trash", "needle trashed").copy(isTrashed = true))
        vm.onFilter(ShellFilter.Archive)
        advanceUntilIdle()
        vm.onSearchChanged("needle")
        advanceTimeBy(199)
        runCurrent()
        assertEquals(SearchPhase.Pending, vm.state.value.search.phase)
        assertTrue(searchCalls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("archive"), vm.state.value.notes.map { it.id })
        assertEquals(SearchPhase.Results, vm.state.value.search.phase)
        vm.onSearchChanged("")
        runCurrent()
        assertEquals(ShellFilter.Archive, vm.state.value.filter)
        assertEquals(listOf("archive"), vm.state.value.notes.map { it.id })
    }

    /** Plant SEARCH_STALE_PUBLISH: omit the revision comparison before publishing results. */
    @Test fun supersededSearchCancelsImmediatelyAndCannotPublishALateResult() = withOpenNote { vm ->
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        searchHandler = { query ->
            if (query.text == "first") {
                try { release.await() } catch (_: CancellationException) {
                    cancelled.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                }
                listOf(note("stale", "first"))
            } else listOf(note("fresh", "second"))
        }
        vm.onSearchChanged("first")
        advanceTimeBy(200)
        runCurrent()
        vm.onSearchChanged("second")
        runCurrent()
        assertTrue(cancelled.isCompleted)
        assertEquals(SearchPhase.Pending, vm.state.value.search.phase)
        release.complete(Unit)
        runCurrent()
        assertEquals("second", vm.state.value.search.query)
        assertEquals(SearchPhase.Pending, vm.state.value.search.phase)
        assertTrue("a cancelled query cannot flash stale results during the next debounce", vm.state.value.notes.isEmpty())
        advanceUntilIdle()
        assertEquals("second", vm.state.value.search.query)
        assertEquals(listOf("fresh"), vm.state.value.notes.map { it.id })
    }

    @Test fun searchFailureRefreshAndFailedOutgoingNavigationKeepTheirOwnState() = withOpenNote { vm ->
        searchHandler = { throw java.io.IOException("controlled search failure") }
        vm.onSearchChanged("hello")
        advanceUntilIdle()
        assertEquals(SearchPhase.Failed, vm.state.value.search.phase)
        assertFalse(vm.state.value.saveFailed)
        searchHandler = { notes.all().filterNot { it.isTrashed } }
        vm.onRetrySearch()
        advanceUntilIdle()
        assertEquals(SearchPhase.Results, vm.state.value.search.phase)
        notes.seed(note("n2", "changed library"))
        advanceUntilIdle()
        assertEquals(2, vm.state.value.notes.size)
        vm.type("unsaved")
        notes.beforeSave = { throw java.io.IOException("controlled outgoing failure") }
        vm.onSelectNote("n2")
        advanceUntilIdle()
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals("hello", vm.state.value.search.query)
        assertEquals("unsaved", vm.buffer.text.toString())
        assertTrue(vm.state.value.saveFailed)
    }

    /** STATUS_RETRY_KEEPS_OLD_SELECTION also invalidates navigation that fails after status entry. */
    @Test fun failedPendingNavigationCannotRepopulateRetryAfterNewerTrash() = withOpenNote { vm ->
        notes.seed(note("n2", "other note"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val origin = requireNotNull(vm.editorSession)
        var attempts = 0
        notes.beforeSave = {
            if (it.id == "n1") {
                attempts++
                if (attempts == 1) { entered.complete(Unit); release.await() }
                throw java.io.IOException("controlled pending-navigation and status flush failure")
            }
        }
        vm.type("pending body for both commands")
        vm.onSelectNote("n2")
        assertTrue("the earlier navigation must be suspended in its real body write", entered.isCompleted)
        vm.onTrashNote(origin)
        release.complete(Unit)
        runCurrent()
        assertEquals("both commands must attempt their own body flush", 2, attempts)
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertTrue(vm.state.value.saveFailed)
        assertTrue(origin.canEdit)
        assertFalse(requireNotNull(notes.current("n1")).isTrashed)
        assertEquals("hello", notes.current("n1")?.body)
        notes.beforeSave = null
        vm.type("latest after both failures")
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("Retry after a newer failed status must not replay an older pending navigation", "n1", vm.state.value.selectedNoteId)
        assertEquals("latest after both failures", notes.current("n1")?.body)
        assertFalse(requireNotNull(notes.current("n1")).isTrashed)
        assertEquals("other note", notes.current("n2")?.body)
        assertFalse(vm.state.value.saveFailed)
    }

    /** The newest valid command wins even when the older navigation's outgoing save succeeds. */
    @Test fun newerArchiveSupersedesAnOlderPendingSuccessfulSelection() = withOpenNote { vm ->
        notes.seed(note("n2", "other note"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        notes.beforeSave = {
            if (it.id == "n1") {
                writes++
                if (writes == 1) { entered.complete(Unit); release.await() }
            }
        }
        vm.type("accepted before newer Archive")
        vm.onSelectNote("n2")
        assertTrue(entered.isCompleted)
        vm.onArchiveNote()
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue("the newer Archive must actually commit", requireNotNull(notes.current("n1")).isArchived)
        assertEquals("accepted before newer Archive", notes.current("n1")?.body)
        assertEquals("a newer status command must supersede older pending navigation", "n1", vm.state.value.selectedNoteId)
        assertFalse(vm.state.value.saveFailed)
    }

    /** Plant STATUS_FAILURE_CANCELS_NEWER_NAVIGATION: invalidate navigation only after status fails. */
    @Test fun newerSelectionDuringAnOlderFailedStatusStillOpensItsTarget() = withOpenNote { vm ->
        notes.seed(note("n2", "other note"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        notes.beforeSave = {
            if (it.id == "n1") {
                attempts++
                if (attempts == 1) {
                    entered.complete(Unit)
                    release.await()
                    throw java.io.IOException("controlled one-shot older status body failure")
                }
            }
        }
        vm.type("accepted while status is pending")
        vm.onArchiveNote()
        assertTrue("the older status must really hold its body write", entered.isCompleted)
        vm.onSelectNote("n2")
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("status failure followed by newer navigation must retry the body once", 2, attempts)
        assertEquals("accepted while status is pending", notes.current("n1")?.body)
        assertFalse(requireNotNull(notes.current("n1")).isArchived)
        assertEquals("an older failed status must not cancel newer explicit navigation", "n2", vm.state.value.selectedNoteId)
        assertFalse(vm.state.value.saveFailed)
    }

    /** A stale callback cannot supersede a valid pending navigation of the current session. */
    @Test fun staleStatusCallbackPreservesTheCurrentPendingSelection() = withOpenNote { vm ->
        val stale = requireNotNull(vm.editorSession)
        notes.seed(note("n2", "other note"))
        vm.onSelectNote("n2")
        advanceUntilIdle()
        assertTrue(stale !== vm.editorSession)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        notes.beforeSave = {
            if (it.id == "n2") { entered.complete(Unit); release.await() }
        }
        vm.type("accepted current note before navigation")
        vm.onSelectNote("n1")
        assertTrue(entered.isCompleted)
        vm.onArchiveNote(stale)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals("a stale status callback must preserve the current pending navigation", "n1", vm.state.value.selectedNoteId)
        assertEquals("accepted current note before navigation", notes.current("n2")?.body)
        assertFalse(requireNotNull(notes.current("n1")).isArchived)
    }

    /** Plant PRIVATE_FLUSH_FAILURE_CLASSIFICATION_INVERTED: treat cancellation as the save failure. */
    @Test fun failedArchiveFlushPublishesNoteSpecificRetryWithoutReplayingArchive() = assertStatusFlushFailureRecovers(
        action = { it.onArchiveNote() },
        assertApplied = { assertTrue(it.isArchived); assertFalse(it.isTrashed) },
    )

    @Test fun failedTrashFlushPublishesNoteSpecificRetryWithoutReplayingTrash() = assertStatusFlushFailureRecovers(
        action = { it.onTrashNote() },
        assertApplied = { assertTrue(it.isTrashed); assertFalse(it.isArchived) },
    )

    private fun assertStatusFlushFailureRecovers(
        action: (DuckShellViewModel) -> Unit,
        assertApplied: (Note) -> Unit,
    ) = withOpenNote { vm ->
        val origin = requireNotNull(vm.editorSession)
        val started = testScheduler.currentTime
        var attempts = 0
        notes.beforeSave = { attempts++; throw java.io.IOException("controlled status body flush failure") }
        vm.type("pending status text")
        action(vm)
        runCurrent()
        assertEquals("status must attempt its own flush before autosave", 1, attempts)
        assertEquals(started, testScheduler.currentTime)
        assertTrue("failed status body flush must publish note-specific save Retry immediately", vm.state.value.saveFailed)
        assertEquals("n1", vm.state.value.failedNoteTitle)
        assertEquals(OperationFailure.Change, vm.state.value.operationFailure)
        assertTrue(origin === vm.editorSession)
        assertTrue("a rejected status change must restore editing", origin.canEdit)
        assertEquals("hello", notes.current("n1")?.body)
        assertEquals("pending status text", origin.buffer.text.toString())
        assertFalse(requireNotNull(notes.current("n1")).isArchived)
        assertFalse(requireNotNull(notes.current("n1")).isTrashed)
        advanceTimeBy(399)
        runCurrent()
        assertEquals("autosave must not vouch for the status failure warning", 1, attempts)
        assertTrue("the originating save warning must persist before autosave", vm.state.value.saveFailed)
        assertEquals("n1", vm.state.value.failedNoteTitle)
        notes.beforeSave = null
        vm.type("latest accepted status text")
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("latest accepted status text", notes.current("n1")?.body)
        assertEquals("latest accepted status text", origin.buffer.text.toString())
        assertEquals("n1", vm.state.value.selectedNoteId)
        assertFalse(vm.state.value.saveFailed)
        assertEquals(null, vm.state.value.failedNoteTitle)
        assertFalse("Retry must not replay Archive", requireNotNull(notes.current("n1")).isArchived)
        assertFalse("Retry must not replay Trash", requireNotNull(notes.current("n1")).isTrashed)
        assertEquals(listOf("latest accepted status text"), writesTo("n1"))
        action(vm)
        advanceUntilIdle()
        assertApplied(requireNotNull(notes.current("n1")))
        assertEquals("an explicit repeat must commit exactly one status change", 1,
            notes.writes.count { it.id == "n1" && (it.isArchived || it.isTrashed) })
    }

    /** Plant STATUS_RETRY_KEEPS_OLD_SELECTION: omit clearing Retry after validating the status owner. */
    @Test fun failedStatusFlushRetiresAnOlderFailedSelection() = withOpenNote { vm ->
        notes.seed(note("n2", "other note"))
        notes.beforeSave = { throw java.io.IOException("controlled outgoing save failure") }
        vm.type("pending status after failed selection")
        vm.onSelectNote("n2")
        runCurrent()
        assertTrue("the earlier selection must really be waiting for its failed save", vm.state.value.saveFailed)
        assertEquals("n1", vm.state.value.selectedNoteId)
        vm.onArchiveNote()
        runCurrent()
        assertEquals(OperationFailure.Change, vm.state.value.operationFailure)
        notes.beforeSave = null
        vm.type("latest status recovery text")
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("status Retry must retire an older failed selection", "n1", vm.state.value.selectedNoteId)
        assertEquals("latest status recovery text", notes.current("n1")?.body)
        assertEquals("other note", notes.current("n2")?.body)
        assertFalse(requireNotNull(notes.current("n1")).isArchived)
        assertFalse(vm.state.value.saveFailed)
    }

    /** Plant STATUS_RETRY_CLEARED_BEFORE_OWNER_CHECK: let a stale callback erase the valid current Retry. */
    @Test fun staleStatusCallbackPreservesTheCurrentFailedSelectionRetry() = withOpenNote { vm ->
        val stale = requireNotNull(vm.editorSession)
        notes.seed(note("n2", "other note"))
        vm.onSelectNote("n2")
        advanceUntilIdle()
        assertTrue(stale !== vm.editorSession)
        notes.beforeSave = { throw java.io.IOException("controlled current outgoing failure") }
        vm.type("pending current note")
        vm.onSelectNote("n1")
        runCurrent()
        assertTrue(vm.state.value.saveFailed)
        assertEquals("n2", vm.state.value.selectedNoteId)
        vm.onArchiveNote(stale)
        notes.beforeSave = null
        vm.type("latest current note")
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("a stale status callback must preserve the current selection Retry", "n1", vm.state.value.selectedNoteId)
        assertEquals("latest current note", notes.current("n2")?.body)
        assertFalse(requireNotNull(notes.current("n1")).isArchived)
    }

    /** Same private-flush classification plant: a cancelled operation must not publish a save failure. */
    @Test fun cancelledTrashPreflushRestoresEditingWithoutSaveWarning() = withOpenNote { vm ->
        val origin = requireNotNull(vm.editorSession)
        var operationJob: Job? = null
        var attempts = 0
        notes.beforeSave = {
            attempts++
            operationJob = currentCoroutineContext()[Job]
            throw CancellationException("controlled status body flush cancellation")
        }
        vm.type("pending cancelled trash text")
        vm.onTrashNote(origin)
        runCurrent()
        assertEquals(1, attempts)
        assertTrue("cancelled status flush must propagate cancellation", operationJob?.isCancelled == true)
        assertTrue("cancelled Trash preflush must restore editing", origin.canEdit)
        assertFalse("cancelled status body flush must not publish a save warning", vm.state.value.saveFailed)
        assertEquals(null, vm.state.value.failedNoteTitle)
        assertEquals(null, vm.state.value.operationFailure)
        assertEquals("hello", notes.current("n1")?.body)
        assertEquals("pending cancelled trash text", origin.buffer.text.toString())
        assertFalse(requireNotNull(notes.current("n1")).isTrashed)
        notes.beforeSave = null
        vm.type("latest after cancellation")
        vm.onTrashNote(origin)
        advanceUntilIdle()
        assertEquals("latest after cancellation", notes.current("n1")?.body)
        assertTrue(requireNotNull(notes.current("n1")).isTrashed)
    }

    /** Private-flush classification plant also covers accepted edits after the status transaction. */
    @Test fun failedPostArchiveFlushRetainsLatestTextAndRetryPreservesCommittedArchive() = withOpenNote { vm ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val origin = requireNotNull(vm.editorSession)
        val started = testScheduler.currentTime
        var lateAttempts = 0
        notes.beforeSave = {
            if (it.isArchived && it.body == "first accepted body") {
                entered.complete(Unit)
                release.await()
            }
            if (it.body == "latest during archive") {
                lateAttempts++
                throw java.io.IOException("controlled post-status body flush failure")
            }
        }
        vm.type("first accepted body")
        vm.onArchiveNote(origin)
        assertTrue("the status transaction must really suspend after its first body flush", entered.isCompleted)
        assertEquals("first accepted body", notes.current("n1")?.body)
        assertFalse(requireNotNull(notes.current("n1")).isArchived)
        assertTrue(origin.canEdit)
        vm.type("latest during archive")
        release.complete(Unit)
        runCurrent()
        assertEquals(started, testScheduler.currentTime)
        assertEquals(1, lateAttempts)
        assertTrue("the Archive transaction must really commit before the failed second flush", requireNotNull(notes.current("n1")).isArchived)
        assertEquals("first accepted body", notes.current("n1")?.body)
        assertEquals("latest during archive", origin.buffer.text.toString())
        assertTrue("failed post-status flush must publish note-specific save Retry", vm.state.value.saveFailed)
        assertEquals("first accepted body", vm.state.value.failedNoteTitle)
        assertTrue(origin.canEdit)
        notes.beforeSave = null
        vm.type("latest accepted after Archive")
        vm.onRetryNavigation()
        advanceUntilIdle()
        assertEquals("latest accepted after Archive", notes.current("n1")?.body)
        assertEquals("latest accepted after Archive", origin.buffer.text.toString())
        assertTrue("Retry must preserve the already committed Archive rather than toggling it", requireNotNull(notes.current("n1")).isArchived)
        assertFalse(vm.state.value.saveFailed)
        assertEquals("n1", vm.state.value.selectedNoteId)
    }

    /** Plant STATUS_FAILURE_STAYS_FROZEN: keep the transient freeze after queued failures. */
    @Test fun queuedFailedTrashRequestsRestoreEditablePersistedState() = assertQueuedTrashFailureRecovers(java.io.IOException("controlled trash failure"))

    /** Plant STATUS_CANCELLATION_STAYS_FROZEN: keep the transient freeze on cancellation. */
    @Test fun queuedCancelledTrashRequestsRestoreEditablePersistedState() = assertQueuedTrashFailureRecovers(CancellationException("controlled trash cancellation"))

    private fun assertQueuedTrashFailureRecovers(failure: Exception) = withOpenNote { vm ->
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        notes.beforeSave = {
            if (it.isTrashed) {
                attempts++
                if (attempts == 1) { entered.complete(Unit); release.await() }
                throw failure
            }
        }
        val session = requireNotNull(vm.editorSession)
        vm.onTrashNote(session)
        assertTrue(entered.isCompleted)
        assertFalse(session.canEdit)
        vm.onTrashNote(session)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertFalse(requireNotNull(notes.current("n1")).isTrashed)
        assertTrue("Failed queued Trash must restore editing from persisted note state", session.canEdit)
        notes.beforeSave = null
        vm.type("editable after failed trash")
        vm.flush()
        assertEquals("editable after failed trash", notes.current("n1")?.body)
        vm.onTrashNote(session)
        advanceUntilIdle()
        assertTrue(requireNotNull(notes.current("n1")).isTrashed)
        assertFalse(session.canEdit)
    }

    /** Plant STATUS_QUEUED_TRASH_NOT_FROZEN: omit the freeze after acquiring the writer lock. */
    @Test fun queuedTrashRefreezesInputAfterThePreviousRequestFails() = withOpenNote { vm ->
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        var attempts = 0
        notes.beforeSave = {
            if (it.isTrashed) {
                attempts++
                if (attempts == 1) {
                    releaseFirst.await()
                    throw java.io.IOException("controlled first trash failure")
                }
                secondEntered.complete(Unit)
                releaseSecond.await()
            }
        }
        val session = requireNotNull(vm.editorSession)
        assertTrue(session.canEdit)
        vm.onTrashNote(session)
        assertEquals(1, attempts)
        vm.onTrashNote(session)
        releaseFirst.complete(Unit)
        assertTrue(secondEntered.isCompleted)
        assertFalse(requireNotNull(notes.current("n1")).isTrashed)
        assertFalse("Queued Trash must freeze input throughout its suspended write", session.canEdit)
        releaseSecond.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertTrue(requireNotNull(notes.current("n1")).isTrashed)
        assertFalse(session.canEdit)
    }

    @Test fun restorePreservesArchiveAndTrashPreventsSessionMutations() = withOpenNote { vm ->
        vm.onArchiveNote()
        advanceUntilIdle()
        assertTrue(notes.current("n1")?.isArchived == true)
        vm.onTrashNote()
        advanceUntilIdle()
        assertTrue(vm.state.value.selectedNoteTrashed)
        assertFalse(requireNotNull(vm.editorSession).canEdit)
        vm.onAttachImage("content://trash", "image/png")
        advanceUntilIdle()
        assertTrue(attachments.all().isEmpty())
        vm.onRestoreNote()
        advanceUntilIdle()
        assertFalse(vm.state.value.selectedNoteTrashed)
        assertTrue(vm.state.value.selectedNoteArchived)
        assertTrue(requireNotNull(vm.editorSession).canEdit)
    }

    /** Plant ATTACHMENT_STALE_BODY: read the body before copying instead of invoking the live provider. */
    @Test fun typingDuringImageCopyAndCommitStaysInTheOriginatingSession() = withOpenNote { vm ->
        val copying = CompletableDeferred<Unit>()
        val releaseCopy = CompletableDeferred<Unit>()
        val committing = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        attachStorage.beforeStore = { copying.complete(Unit); releaseCopy.await() }
        var attachmentTransactionBody = ""
        notes.beforeSave = {
            if (app.duck.notes.domain.markdown.MarkdownScanner.attachmentIdsIn(it.body).isNotEmpty()) {
                attachmentTransactionBody = it.body
                committing.complete(Unit)
                releaseCommit.await()
            }
        }
        vm.onAttachImage("content://picked", "image/png")
        assertTrue(copying.isCompleted)
        vm.type("typed during copy")
        releaseCopy.complete(Unit)
        assertTrue(committing.isCompleted)
        assertTrue("the transaction itself must include typing during copy", attachmentTransactionBody.startsWith("typed during copy"))
        vm.type("typed during commit")
        releaseCommit.complete(Unit)
        advanceUntilIdle()
        vm.flush()
        val saved = requireNotNull(notes.current("n1"))
        assertTrue(saved.body.startsWith("typed during commit"))
        assertEquals(attachments.all().map { it.id }.toSet(), app.duck.notes.domain.markdown.MarkdownScanner.attachmentIdsIn(saved.body))
        assertEquals(1, attachments.all().size)
    }
}
