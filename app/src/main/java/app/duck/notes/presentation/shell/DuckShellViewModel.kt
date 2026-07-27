package app.duck.notes.presentation.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.duck.notes.domain.Clock
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.TagNode
import app.duck.notes.domain.usecase.ObserveNote
import app.duck.notes.domain.usecase.ObserveNotes
import app.duck.notes.domain.usecase.ObserveTagTree
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * The shell's one state holder, and the first ViewModel in the project.
 *
 * **Every piece of shell state lives in [handle], and none of it in `rememberSaveable`.** That is
 * not belt-and-braces, it is the fix for a specific bug: on Compact the sidebar composes inside a
 * `ModalDrawerSheet` and on Expanded inside a `Row`, so crossing the 600dp breakpoint changes the
 * composition position `rememberSaveable` keys on. The saver would find no entry and
 * [expandedTagPaths] would silently reset — a resize losing state that a rotation preserves, which
 * is the confusing direction. A `SavedStateHandle` entry has no composition key at all, so it
 * survives a rotation (the `ViewModelStore` is retained), a resize (same) and process death (the
 * handle is written to the saved-instance bundle).
 *
 * The editor is **not** wired here. Phase 6 hands it a `String`; autosave, undo and the save path
 * are Phase 8's, and pulling them forward is what giving the editor its own ViewModel would do.
 */
@HiltViewModel
class DuckShellViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val observeNotes: ObserveNotes,
    private val observeNote: ObserveNote,
    observeTagTree: ObserveTagTree,
    private val clock: Clock,
) : ViewModel() {

    private val filter: Flow<ShellFilter> =
        handle.getStateFlow<String?>(KEY_FILTER, null).map(ShellFilter::fromKey)

    private val selectedId: StateFlow<String?> = handle.getStateFlow(KEY_SELECTED, null)

    private val expanded: Flow<Set<String>> =
        handle.getStateFlow(KEY_EXPANDED, ArrayList<String>()).map { it.toSet() }

    private val tree: Flow<List<TagNode>> = observeTagTree()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val notes: Flow<List<Note>> =
        combine(filter, tree) { f, t -> f to t }.flatMapLatest { (f, t) -> notesFor(f, t) }

    /**
     * The selected note's body, followed independently of [notes].
     *
     * Not read out of the list: selecting a note and then switching the sidebar filter leaves the
     * editor open on a note the list no longer contains, which is correct. A null emission — the
     * note was trashed or purged from under us — collapses to "nothing selected" rather than
     * stranding the editor on a dead id.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val selection: Flow<Selection> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(Selection())
        else observeNote(id).map { note -> note?.let { Selection(id, it.body) } ?: Selection() }
    }

    val state: StateFlow<ShellUiState> =
        combine(filter, notes, selection, expanded, tree) { f, n, sel, exp, t ->
            ShellUiState(
                filter = f,
                notes = n,
                selectedNoteId = sel.id,
                selectedNoteBody = sel.body,
                pinnedTags = t.pinned(),
                tagRows = t.flatten(exp),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShellUiState())

    // --- intents -------------------------------------------------------------

    /** Changing the filter clears the selection: the open note is usually not in the new list. */
    fun onFilter(next: ShellFilter) {
        handle[KEY_FILTER] = next.key
        handle[KEY_SELECTED] = null
    }

    fun onSelectNote(id: String) {
        handle[KEY_SELECTED] = id
    }

    fun onClearSelection() {
        handle[KEY_SELECTED] = null
    }

    fun onToggleTag(path: String) {
        val current = handle.get<ArrayList<String>>(KEY_EXPANDED).orEmpty()
        handle[KEY_EXPANDED] = ArrayList(if (path in current) current - path else current + path)
    }

    // --- filter → flow -------------------------------------------------------

    /**
     * Five of the seven rows are a predicate over the active list, so they cost no query. [Untagged]
     * and [Trash] each needed one, because `observeNotes` hides trashed rows by contract and knows
     * nothing about tag membership.
     *
     * A tag filter resolves its path through [tree] rather than storing a tag id: the id is a UUID
     * minted on first use, the path is what the user typed, and the path is what the sidebar row is
     * keyed by. An unresolvable path — the tag lost its last note while selected — yields an empty
     * list rather than falling back to All, so the sidebar keeps saying what is being filtered.
     */
    private fun notesFor(filter: ShellFilter, tree: List<TagNode>): Flow<List<Note>> = when (filter) {
        ShellFilter.All -> observeNotes().map { list -> list.filterNot(Note::isArchived) }
        ShellFilter.Untagged -> observeNotes.untagged()
        ShellFilter.Todo -> observeNotes().map { list ->
            list.filter { MarkdownScanner.hasOpenTask(it.body) }
        }
        ShellFilter.Today -> startOfToday().let { midnight ->
            observeNotes().map { list -> list.filter { it.modifiedAt >= midnight } }
        }
        ShellFilter.Locked -> observeNotes().map { list -> list.filter(Note::isLocked) }
        ShellFilter.Archive -> observeNotes().map { list -> list.filter(Note::isArchived) }
        ShellFilter.Trash -> observeNotes.trashed()
        is ShellFilter.Tag ->
            tree.findByPath(filter.path)?.tag?.let { observeNotes(it.id) } ?: flowOf(emptyList())
    }

    /** Local midnight, from the injected [Clock] so the Today row is deterministic under test. */
    private fun startOfToday(): Long = ZoneId.systemDefault().let { zone ->
        Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
    }

    private data class Selection(val id: String? = null, val body: String = "")

    private companion object {
        const val KEY_FILTER = "shell.filter"
        const val KEY_SELECTED = "shell.selectedNoteId"
        const val KEY_EXPANDED = "shell.expandedTagPaths"

        /** Outlives a rotation, so Room's flows are not torn down and rebuilt for one. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Depth-first search for the node at [path]. The tree is tiny; a map would be more code than gain. */
private fun List<TagNode>.findByPath(path: String): TagNode? {
    for (node in this) {
        if (node.path == path) return node
        node.children.findByPath(path)?.let { return it }
    }
    return null
}
