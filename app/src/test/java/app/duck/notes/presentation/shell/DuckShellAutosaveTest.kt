package app.duck.notes.presentation.shell

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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
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
    private val attachments = FakeAttachmentRepository()
    private val attachStorage = FakeAttachmentStorage()
    private val parseTags = ParseTags()

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
        block(vm)
        scope.cancel()
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
    fun onStopFlushes() = withOpenNote { vm ->
        vm.type("typed then backgrounded")
        vm.onStop()
        advanceUntilIdle()

        assertEquals(listOf("typed then backgrounded"), writesTo("n1"))
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

    /**
     * Undo is the one intent that must **not** flush, and this pins the reason.
     *
     * The other three flush so the transaction reads current text. Undo's contract is the opposite —
     * discard what happened since the snapshot — so flushing first writes the user's typing to Room
     * and then restores the snapshot straight over it. The typing would be destroyed *on disk*, and
     * `SaveNote` would restamp `modifiedAt` on the way past, for a write that lives two statements.
     *
     * The typing is still lost from the buffer; `adopt` is unconditional and that is undo working.
     * What this asserts is that Room never held it.
     */
    @Test
    fun undoDoesNotFlushTheTypingItIsAboutToDiscard() = withOpenNote(body = "a #work b") { vm ->
        vm.onRenameTag("work", "job")
        advanceUntilIdle()
        notes.writes.clear()

        vm.type("a #job b typed while the snackbar was up")
        vm.onUndo()
        advanceUntilIdle()

        assertEquals(1, tags.restores.size)
        assertFalse(
            "undo flushed the typing to Room before restoring over it: ${writesTo("n1")}",
            writesTo("n1").any { "typed while the snackbar was up" in it },
        )
        assertEquals("a #work b", vm.buffer.text.toString())
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
     * A debounced save already in flight cannot clobber the body undo just restored.
     *
     * Removing the flush from [DuckShellViewModel.onUndo] removed the only thing that was draining
     * the pending save as a side effect. Left alone, that save fires in the gap between the restore
     * transaction and the adopt: it reads a buffer still holding the user's typing, against a
     * `lastWritten` still holding the pre-restore value, so the gate passes it and it overwrites the
     * restored body — and the adopt then faithfully adopts the clobbered text back out of Room.
     *
     * ## Why the obvious version of this test is worthless
     *
     * Leaving a save pending and calling `onUndo()` **passes with or without the fix.** Under
     * `UnconfinedTestDispatcher` the entire undo coroutine runs to completion before any virtual-time
     * advance releases the pending save, so the two never interleave and the race is never attempted.
     * That version was written first and verified to pass against the unfixed code — which is the only
     * reason it was caught.
     *
     * Advancing virtual time from inside the window does not work either — `advanceTimeBy` called
     * from a coroutine already running on the scheduler will not place the collector there.
     *
     * So the racing call is **invoked directly**, on its own coroutine, via
     * [FakeTagRepository.afterRestore]. `flush()` is not a stand-in: it is the same `save(loadedId)`
     * the debounce collector calls, taking the same lock and passing the same gate, so this exercises
     * the real path rather than a model of it. What the hook supplies is only the *timing* the
     * dispatcher will not.
     *
     * It must be a separate coroutine: `Mutex` is not reentrant, and calling `flush()` on `onUndo`'s
     * own coroutine while it holds the lock would deadlock rather than race.
     *
     * With the lock held across restore + adopt the racing save blocks, then finds
     * `buffer == lastWritten` once `adopt` has run, and writes nothing. Without it, it writes the
     * typing over the restore and the adopt hands the clobbered text back to the buffer.
     */
    @Test
    fun aPendingSaveCannotClobberTheBodyUndoRestored() = withOpenNote(body = "a #work b") { vm ->
        vm.onRenameTag("work", "job")
        advanceUntilIdle()
        assertEquals("a #job b", vm.buffer.text.toString())
        notes.writes.clear()

        // Fire a save in the gap between the restore and the adopt — the only place it is dangerous,
        // and the only place an unconfined dispatcher will not put one by itself.
        // UNDISPATCHED is load-bearing. A plain `launch` is *queued* here, so it runs only after
        // `onUndo` has already adopted — the flush then sees a clean buffer and the race never
        // happens. Verified: with a plain launch this test passes against the unfixed code too.
        // UNDISPATCHED starts the body synchronously and runs it up to its first suspension, which is
        // the mutex — precisely where a real debounced save would find itself.
        val racer = CoroutineScope(dispatcher)
        tags.afterRestore = {
            racer.launch(start = CoroutineStart.UNDISPATCHED) { vm.flush() }
        }

        vm.type("a #job b typed after the rename")
        advanceTimeBy(200)
        vm.onUndo()
        advanceUntilIdle()
        racer.cancel()

        assertEquals("the restored body lost to a pending save", "a #work b", vm.buffer.text.toString())
        assertEquals("the restored body lost to a pending save", "a #work b", notes.current("n1")?.body)
        assertFalse(
            "the pending save wrote the typing over the restore: ${writesTo("n1")}",
            writesTo("n1").any { "typed after the rename" in it },
        )
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

    /**
     * Undo is the one place adopt-when-clean is overridden, deliberately.
     *
     * A restore writes N exact previous bodies back; there is no transform to apply to a dirty
     * buffer, and "put it back" is a command the user just pressed rather than something happening
     * beside them. Pinned so the asymmetry cannot drift silently into consistency.
     */
    @Test
    fun undoAdoptsEvenWhenTheBufferIsDirty() = withOpenNote(body = "a #work b") { vm ->
        vm.onRenameTag("work", "job")
        advanceUntilIdle()
        assertEquals("a #job b", vm.buffer.text.toString())

        // Type after the rename, then undo. The restore puts the pre-rename body back, and it wins
        // over the unsaved keystrokes — the one place adopt-when-clean is overridden.
        vm.type("a #job b typed after the rename")
        vm.onUndo()
        advanceUntilIdle()

        assertEquals("a #work b", vm.buffer.text.toString())
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
     * Reached through [DuckShellViewModel.onFilter], which clears the selection **without** flushing
     * — `onSelectNote` flushes first and so pays the debt on the way out, which is exactly why the
     * leak is invisible from the obvious path. Same cascade shape as keying the autosave loop on
     * `selectedId` instead of `loadedId`.
     */
    @Test
    fun anOwedTagWriteDoesNotFollowTheUserToTheNextNote() = withOpenNote(body = "") { vm ->
        notes.seed(note("n2", "second"))
        vm.type("#work")
        advanceTimeBy(DuckShellViewModel.IDLE_MS + 1)
        advanceUntilIdle()
        assertTrue("precondition: the write was suppressed", notes.savedTags["n1"].isNullOrEmpty())

        vm.onFilter(ShellFilter.All) // clears the selection, and does not flush
        advanceUntilIdle()
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
}
