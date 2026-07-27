package app.duck.notes.presentation.shell

import androidx.lifecycle.SavedStateHandle
import app.duck.notes.domain.Clock
import app.duck.notes.domain.fake.FakeNoteRepository
import app.duck.notes.domain.fake.FakeTagRepository
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.Tag
import app.duck.notes.domain.usecase.ObserveNote
import app.duck.notes.domain.usecase.ObserveNotes
import app.duck.notes.domain.usecase.ObserveTagTree
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The shell ViewModel against fake repositories.
 *
 * Every assertion reads `state.value` after a collector has been attached and the scheduler drained:
 * `state` is a `WhileSubscribed` `StateFlow`, so with nothing collecting it never leaves its initial
 * value and every test would pass vacuously. [withShell] is what makes that impossible to forget.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuckShellViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val notes = FakeNoteRepository()
    private val tags = FakeTagRepository(
        listOf(
            Tag(id = "work", name = "work"),
            Tag(id = "projects/duck", name = "projects/duck", isPinned = true),
        ),
    )

    /** Fixed noon on 2024-11-30 local, so the Today row has a midnight to compare against. */
    private val now = 1_733_000_000_000L
    private val midnight = Instant.ofEpochMilli(now)
        .atZone(ZoneId.systemDefault()).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun note(
        id: String,
        modifiedAt: Long = now,
        body: String = "body of $id",
        archived: Boolean = false,
        trashed: Boolean = false,
        locked: Boolean = false,
    ) = Note(
        id = id,
        title = id,
        body = body,
        createdAt = 0L,
        modifiedAt = modifiedAt,
        isArchived = archived,
        isTrashed = trashed,
        isLocked = locked,
        trashedAt = if (trashed) modifiedAt else null,
    )

    private fun shell(handle: SavedStateHandle = SavedStateHandle()) = DuckShellViewModel(
        handle = handle,
        observeNotes = ObserveNotes(notes),
        observeNote = ObserveNote(notes),
        observeTagTree = ObserveTagTree(tags),
        clock = Clock { now },
    )

    /** Attaches a collector so `state` actually runs, then drains the scheduler between actions. */
    private fun runShell(
        handle: SavedStateHandle = SavedStateHandle(),
        body: suspend (DuckShellViewModel, suspend () -> Unit) -> Unit,
    ) = runTest {
        val vm = shell(handle)
        val scope = CoroutineScope(dispatcher)
        scope.launch { vm.state.collect {} }
        val settle: suspend () -> Unit = { advanceUntilIdle() }
        settle()
        body(vm, settle)
        scope.cancel()
    }

    // --- defaults ------------------------------------------------------------

    @Test
    fun defaultsToAllWithNothingSelected() = runShell { vm, _ ->
        assertEquals(ShellFilter.All, vm.state.value.filter)
        assertNull(vm.state.value.selectedNoteId)
    }

    @Test
    fun allHidesArchivedNotes() = runShell { vm, settle ->
        notes.seed(note("live"))
        notes.seed(note("filed", archived = true))
        settle()

        assertEquals(listOf("live"), vm.state.value.notes.map { it.id })
    }

    // --- selection -----------------------------------------------------------

    @Test
    fun selectingANoteLoadsItsBody() = runShell { vm, settle ->
        notes.seed(note("n1", body = "# hello"))
        settle()

        vm.onSelectNote("n1")
        settle()

        assertEquals("n1", vm.state.value.selectedNoteId)
        assertEquals("# hello", vm.state.value.selectedNoteBody)
    }

    @Test
    fun changingTheFilterClearsTheSelection() = runShell { vm, settle ->
        notes.seed(note("n1"))
        settle()
        vm.onSelectNote("n1")
        settle()

        vm.onFilter(ShellFilter.Archive)
        settle()

        assertNull("the open note is usually not in the new list", vm.state.value.selectedNoteId)
        assertEquals("", vm.state.value.selectedNoteBody)
    }

    /**
     * A note deleted from under the editor collapses to "nothing selected".
     *
     * The alternative is stranding the editor on a dead id, showing the last body it saw with no way
     * to notice the note is gone. `ObserveNote` emits null precisely so this is detectable.
     */
    @Test
    fun aHardDeletedSelectionClearsRatherThanStranding() = runShell { vm, settle ->
        notes.seed(note("doomed"))
        settle()
        vm.onSelectNote("doomed")
        settle()
        assertEquals("doomed", vm.state.value.selectedNoteId)

        notes.hardDelete("doomed")
        settle()

        assertNull(vm.state.value.selectedNoteId)
    }

    // --- the seven rows ------------------------------------------------------

    @Test
    fun trashShowsOnlyTrashedNotes() = runShell { vm, settle ->
        notes.seed(note("live"))
        notes.seed(note("binned", trashed = true))
        settle()

        vm.onFilter(ShellFilter.Trash)
        settle()

        assertEquals(listOf("binned"), vm.state.value.notes.map { it.id })
    }

    @Test
    fun untaggedShowsOnlyNotesWithNoTag() = runShell { vm, settle ->
        notes.seed(note("tagged"), tags = listOf("work"))
        notes.seed(note("bare"))
        settle()

        vm.onFilter(ShellFilter.Untagged)
        settle()

        assertEquals(listOf("bare"), vm.state.value.notes.map { it.id })
    }

    @Test
    fun todayComparesAgainstLocalMidnightFromTheClock() = runShell { vm, settle ->
        notes.seed(note("today", modifiedAt = midnight + 1))
        notes.seed(note("yesterday", modifiedAt = midnight - 1))
        settle()

        vm.onFilter(ShellFilter.Today)
        settle()

        assertEquals(listOf("today"), vm.state.value.notes.map { it.id })
    }

    @Test
    fun lockedIsEmptyUntilPhase12ButFiltersCorrectly() = runShell { vm, settle ->
        notes.seed(note("open"))
        settle()
        vm.onFilter(ShellFilter.Locked)
        settle()
        assertEquals(emptyList<String>(), vm.state.value.notes.map { it.id })

        // Not a stub: give it a locked note and the same row finds it.
        notes.seed(note("sealed", locked = true))
        settle()
        assertEquals(listOf("sealed"), vm.state.value.notes.map { it.id })
    }

    @Test
    fun todoUsesTheScannerSoFencedTasksDoNotCount() = runShell { vm, settle ->
        notes.seed(note("real", body = "- [ ] buy milk"))
        notes.seed(note("done", body = "- [x] finished"))
        notes.seed(note("fenced", body = "```\n- [ ] not a task\n```"))
        settle()

        vm.onFilter(ShellFilter.Todo)
        settle()

        assertEquals(listOf("real"), vm.state.value.notes.map { it.id })
    }

    // --- tags ----------------------------------------------------------------

    @Test
    fun aTagFilterResolvesItsPathToTheTagId() = runShell { vm, settle ->
        notes.seed(note("w"), tags = listOf("work"))
        notes.seed(note("other"), tags = listOf("projects/duck"))
        settle()

        vm.onFilter(ShellFilter.Tag("work"))
        settle()

        assertEquals(listOf("w"), vm.state.value.notes.map { it.id })
    }

    /** An unresolvable path stays on the tag rather than silently falling back to All. */
    @Test
    fun anUnknownTagPathShowsNothingRatherThanEverything() = runShell { vm, settle ->
        notes.seed(note("a"), tags = listOf("work"))
        settle()

        vm.onFilter(ShellFilter.Tag("deleted/tag"))
        settle()

        assertEquals(ShellFilter.Tag("deleted/tag"), vm.state.value.filter)
        assertEquals(emptyList<String>(), vm.state.value.notes.map { it.id })
    }

    @Test
    fun togglingATagExpandsThenCollapsesIt() = runShell { vm, settle ->
        notes.seed(note("a"), tags = listOf("projects/duck"))
        settle()
        assertEquals(listOf("work", "projects"), vm.state.value.tagRows.map { it.path })

        vm.onToggleTag("projects")
        settle()
        assertEquals(listOf("work", "projects", "projects/duck"), vm.state.value.tagRows.map { it.path })

        vm.onToggleTag("projects")
        settle()
        assertEquals(listOf("work", "projects"), vm.state.value.tagRows.map { it.path })
    }

    @Test
    fun pinnedTagsAppearRegardlessOfExpansion() = runShell { vm, settle ->
        notes.seed(note("a"), tags = listOf("projects/duck"))
        settle()

        // `projects` is collapsed, so projects/duck is absent from tagRows — but it is pinned, and
        // the pinned section is derived from the tree rather than from the flattened rows.
        assertEquals(listOf("work", "projects"), vm.state.value.tagRows.map { it.path })
        assertEquals(listOf("projects/duck"), vm.state.value.pinnedTags.map { it.path })
    }
}
