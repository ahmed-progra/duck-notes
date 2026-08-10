package app.duck.notes.presentation.shell

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
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
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import app.duck.notes.domain.usecase.PinTag
import app.duck.notes.domain.usecase.RenameTag
import app.duck.notes.domain.usecase.SaveNote
import app.duck.notes.domain.usecase.SetTagIcon
import app.duck.notes.domain.usecase.UndoTagChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Shell state survives process death.
 *
 * The mechanism is that **every** piece of it lives in `SavedStateHandle` and none in
 * `rememberSaveable`. This test is the reason that rule is worth stating: a `rememberSaveable` for
 * the expanded tag set would pass a rotation and fail a **resize**, because crossing 600dp moves the
 * sidebar between a `ModalDrawerSheet` and a `Row` and changes the composition position the saver
 * keys on. A `SavedStateHandle` entry has no composition key, so there is nothing to invalidate.
 *
 * Process death is simulated the way the framework actually does it: build a ViewModel, act on it,
 * take the handle's contents, and construct a **second** ViewModel from a handle seeded with them.
 * A restored handle is a plain map of what was written — which is exactly why only Bundle-safe types
 * (a `String`, an `ArrayList<String>`) are stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellStateRestorationTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val notes = FakeNoteRepository()
    private val attachments = FakeAttachmentRepository()
    private val attachStorage = FakeAttachmentStorage()
    private val tags = FakeTagRepository(
        listOf(
            Tag(id = "projects", name = "projects"),
            Tag(id = "projects/duck", name = "projects/duck"),
        ),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val parseTags = ParseTags()

    private fun shell(handle: SavedStateHandle) = DuckShellViewModel(
        handle = handle,
        observeNotes = ObserveNotes(notes),
        observeNote = ObserveNote(notes),
        saveNote = SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), Clock { 0L }),
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
            SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), Clock { 0L }),
        ),
        undoTagChange = UndoTagChange(tags),
        attachImage = AttachImage(
            attachStorage, attachments, notes,
            SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), Clock { 0L }), Clock { 0L },
        ),
        deleteAttachment = DeleteAttachment(
            attachStorage, attachments, notes,
            SaveNote(notes, DeriveTitle(), parseTags, ResolveWikiLinks(), Clock { 0L }),
        ),
        observeAttachments = ObserveAttachments(attachments),
        getBacklinks = GetBacklinks(FakeLinkRepository()),
        clock = Clock { 0L },
    )

    @Test
    fun filterSelectionAndExpansionAllSurviveProcessDeath() = runTest {
        notes.seed(
            Note(id = "n1", title = "n1", body = "kept", createdAt = 0L, modifiedAt = 1L),
            tags = listOf("projects/duck"),
        )

        val first = SavedStateHandle()
        val before = shell(first)
        val scope = CoroutineScope(dispatcher)
        scope.launch { before.state.collect {} }
        advanceUntilIdle()

        before.onFilter(ShellFilter.Tag("projects/duck"))
        before.onSelectNote("n1")
        before.onToggleTag("projects")
        before.onToggleSection("1:0:Heading")
        advanceUntilIdle()
        scope.cancel()

        // The process dies here. Everything that survives is what the handle holds.
        val after = shell(SavedStateHandle(first.keys().associateWith { first.get<Any?>(it) }))
        val restored = CoroutineScope(dispatcher)
        restored.launch { after.state.collect {} }
        advanceUntilIdle()

        assertEquals(ShellFilter.Tag("projects/duck"), after.state.value.filter)
        assertEquals("n1", after.state.value.selectedNoteId)
        assertEquals("kept", after.state.value.selectedNoteBody)
        assertEquals(
            listOf("projects", "projects/duck"),
            after.state.value.tagRows.map { it.path },
        )
        assertEquals(setOf("1:0:Heading"), after.state.value.collapsedSections)
        restored.cancel()
    }

    /**
     * The handle carries the note **id**, which is what makes restore "re-query Room" rather than
     * "deserialise a body".
     *
     * This asserts only what it can see. It is **not** the saver guard, and an earlier version of it
     * pretended to be: `rememberSaveable` writes to the composition's `SaveableStateRegistry`, which
     * the Activity collects — never to `SavedStateHandle`. Reintroducing the saver into
     * `MarkdownEditorScreen` left this test green, because nothing here composes anything.
     * `EditorSaverSourceTest` is the guard that can actually fail.
     */
    @Test
    fun theHandleCarriesTheNoteIdSoRestoreCanRequery() = runTest {
        notes.seed(Note(id = "n1", title = "n1", body = "body", createdAt = 0L, modifiedAt = 1L))

        val handle = SavedStateHandle()
        val vm = shell(handle)
        val scope = CoroutineScope(dispatcher)
        scope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onSelectNote("n1")
        advanceUntilIdle()

        assertEquals("n1", handle.get<String>("shell.selectedNoteId"))
        scope.cancel()
    }

    /**
     * A selection whose note vanished while the process was dead restores as "nothing selected".
     *
     * The handle still holds the dead id — it is a dumb map and cannot know. `ObserveNote` emitting
     * null is what turns that into an empty editor rather than a stranded one.
     */
    @Test
    fun aSelectionRestoredOntoADeletedNoteClearsItself() = runTest {
        notes.seed(Note(id = "gone", title = "gone", body = "x", createdAt = 0L, modifiedAt = 1L))

        val handle = SavedStateHandle()
        val before = shell(handle)
        val scope = CoroutineScope(dispatcher)
        scope.launch { before.state.collect {} }
        advanceUntilIdle()
        before.onSelectNote("gone")
        advanceUntilIdle()
        scope.cancel()

        notes.hardDelete("gone")

        val after = shell(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
        val restored = CoroutineScope(dispatcher)
        restored.launch { after.state.collect {} }
        advanceUntilIdle()

        assertEquals(null, after.state.value.selectedNoteId)
        assertEquals("", after.state.value.selectedNoteBody)
        restored.cancel()
    }

    /**
     * **The other Bundle channel**, and the one a source scan cannot see.
     *
     * `EditorSaverSourceTest` bans `TextFieldState`'s saver textually, which covers the composition's
     * `SaveableStateRegistry`. It says nothing about this one: a plain `handle["body"] = text` inside
     * the ViewModel puts the whole note in the saved-instance `Bundle` without naming a saver, and an
     * audit listed exactly that as a plant the scan waves through.
     *
     * So this asserts the property at runtime rather than in source, which is the only way it can be
     * seen — and it is what [MAX_HANDLE_VALUE] was named for. The constant sat unused here, residue
     * of a removed guard, while the only live `512` was a bare literal in `AutosaveDurabilityTest`
     * with neither the name nor the reason. `AutosaveDurabilityTest` still runs the device half over
     * real Room; this runs the same claim on every JVM build.
     *
     * **Both halves of the buffer's life are driven**, because they reach the handle by different
     * code: the load, and then a keystroke plus a `flush()`. The precondition assert is what stops it
     * being vacuous — a handle with nothing large in it proves nothing unless something large
     * actually went through the ViewModel first.
     */
    @Test
    fun noNoteBodyReachesTheSavedStateHandle() = runTest {
        val body = "lorem ipsum dolor sit amet ".repeat(2_000)
        notes.seed(Note(id = "big", title = "big", body = body, createdAt = 0L, modifiedAt = 1L))

        val handle = SavedStateHandle()
        val vm = shell(handle)
        val scope = CoroutineScope(dispatcher)
        scope.launch { vm.state.collect {} }
        advanceUntilIdle()
        vm.onSelectNote("big")
        advanceUntilIdle()

        vm.buffer.setTextAndPlaceCursorAtEnd("$body edited")
        Snapshot.sendApplyNotifications()
        vm.flush()
        advanceUntilIdle()

        assertEquals(
            "precondition: a body this size must actually have flowed through the ViewModel, or " +
                "the handle being small below says nothing",
            "$body edited",
            vm.buffer.text.toString(),
        )
        val oversized = handle.keys()
            .filter { handle.get<Any?>(it).toString().length > MAX_HANDLE_VALUE }
        assertEquals(
            "the note body reached the saved-instance Bundle via SavedStateHandle: $oversized. " +
                "The handle carries the note id; restore is 'read the id, re-query Room'.",
            emptyList<String>(),
            oversized,
        )

        // The general net. Per-value size cannot see a key FAMILY that grows: 8c-c's fold keys were
        // each a couple of short strings and there was no bound on how many of them existed, so
        // every one passed the check above while the Bundle grew without limit.
        val total = handle.keys().sumOf { handle.get<Any?>(it).toString().length }
        assertTrue(
            "the handle totals $total chars across ${handle.keys().size} keys, over $MAX_HANDLE_TOTAL " +
                "— something in the ViewModel is writing a key family that scales with use. The " +
                "Bundle shares a 1MB Binder buffer; 8a's invariant is that it holds filter, note id " +
                "and expanded tag paths.",
            total <= MAX_HANDLE_TOTAL,
        )
        scope.cancel()
    }

    /**
     * **At most one note's folds are ever in the handle.**
     *
     * 8c-c wrote `shell.foldedSections.<noteId>` and never removed it, so a key accumulated per note
     * folded, for the life of the process, inside the saved-instance `Bundle`. That is the same 1MB
     * Binder buffer Phase 8a dropped the editor's saver to protect, and 8a's invariant is explicit
     * that the `Bundle` carries the filter, the note id and the expanded tag paths — nothing that
     * scales with how much of the app you have touched.
     *
     * This asserts the **structural** bound rather than a size threshold: exactly one fold key, and
     * it belongs to the open note. [noNoteBodyReachesTheSavedStateHandle]'s total-size assertion is
     * the general net underneath it; this is the exact statement.
     *
     * The second `assertEquals` is not decoration — a sweep that removed *every* fold key, including
     * the one just written, would satisfy a count of one only by accident of ordering. Naming the
     * surviving key pins which one survived.
     */
    @Test
    fun theHandleHoldsFoldStateForAtMostOneNote() = runTest {
        notes.seed(Note(id = "n1", title = "n1", body = "# A\nbody", createdAt = 0L, modifiedAt = 2L))
        notes.seed(Note(id = "n2", title = "n2", body = "# B\nbody", createdAt = 0L, modifiedAt = 1L))

        val handle = SavedStateHandle()
        val vm = shell(handle)
        val scope = CoroutineScope(dispatcher)
        scope.launch { vm.state.collect {} }
        advanceUntilIdle()

        vm.onSelectNote("n1")
        advanceUntilIdle()
        vm.onToggleSection("1:0:A")
        vm.onSelectNote("n2")
        advanceUntilIdle()
        vm.onToggleSection("1:0:B")
        advanceUntilIdle()

        val foldKeys = handle.keys().filter { it.startsWith("shell.foldedSections.") }
        assertEquals(
            "one fold key per note folded accumulates in the saved-instance Bundle: $foldKeys",
            1,
            foldKeys.size,
        )
        assertEquals(
            "the surviving fold key must be the OPEN note's, not whichever survived by ordering",
            listOf("shell.foldedSections.n2"),
            foldKeys,
        )
        scope.cancel()
    }

    private companion object {
        /** Comfortably above any id, filter key or tag path; far below any real note body. */
        const val MAX_HANDLE_VALUE = 512

        /**
         * Every value in the handle, added up. Catches a key *family* that grows without any single
         * value getting large — which is exactly how 8c-c's fold keys escaped [MAX_HANDLE_VALUE],
         * since each held two or three short strings and there was simply no limit on how many.
         *
         * Generous on purpose: the point is to catch unbounded growth, not to police the filter key.
         */
        const val MAX_HANDLE_TOTAL = 2_048
    }
}
