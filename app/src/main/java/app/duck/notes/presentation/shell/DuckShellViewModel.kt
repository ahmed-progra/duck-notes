package app.duck.notes.presentation.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.duck.notes.domain.Clock
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.TagCount
import app.duck.notes.domain.model.TagNode
import app.duck.notes.domain.usecase.AddTagResult
import app.duck.notes.domain.usecase.AddTagToNote
import app.duck.notes.domain.usecase.DeleteTag
import app.duck.notes.domain.usecase.ObserveNote
import app.duck.notes.domain.usecase.ObserveNotes
import app.duck.notes.domain.usecase.ObserveTagCounts
import app.duck.notes.domain.usecase.ObserveTagTree
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.PinTag
import app.duck.notes.domain.usecase.RenameTag
import app.duck.notes.domain.usecase.SetTagIcon
import app.duck.notes.domain.usecase.UndoTagChange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    observeTagCounts: ObserveTagCounts,
    private val parseTags: ParseTags,
    private val pinTag: PinTag,
    private val renameTag: RenameTag,
    private val deleteTag: DeleteTag,
    private val setTagIcon: SetTagIcon,
    private val addTagToNote: AddTagToNote,
    private val undoTagChange: UndoTagChange,
    private val clock: Clock,
) : ViewModel() {

    private val filter: Flow<ShellFilter> =
        handle.getStateFlow<String?>(KEY_FILTER, null).map(ShellFilter::fromKey)

    private val selectedId: StateFlow<String?> = handle.getStateFlow(KEY_SELECTED, null)

    private val expanded: Flow<Set<String>> =
        handle.getStateFlow(KEY_EXPANDED, ArrayList<String>()).map { it.toSet() }

    private val tree: Flow<List<TagNode>> = observeTagTree()

    private val counts: Flow<Map<String, TagCount>> = observeTagCounts()

    /**
     * The undo snapshot, and **the one piece of shell state deliberately not in [handle]**.
     *
     * Everything else lives there so it survives process death; this cannot. A [BodySnapshot] holds
     * N full note bodies, and `SavedStateHandle` is written to the saved-instance `Bundle`, which
     * shares a **1MB per-process Binder buffer** — the same limit Phase 8's note on the editor's
     * saver is about. A 200-note rename would put the whole corpus through it and land a
     * `TransactionTooLargeException`.
     *
     * So undo is explicitly transient: it lives as long as the Snackbar and dies with the process.
     * A durable undo is an operation log, which is Phase 8's undo/redo stack or nothing.
     */
    private var pendingUndo: BodySnapshot? = null

    private val undo = MutableStateFlow<TagPrompt?>(null)

    /**
     * Notes for the current filter. **No longer joined with [tree]** — a tag filter resolves by
     * *path* straight to a query, so the panes no longer have to wait for the tree to load before
     * the list can populate, and the ViewModel's `findByPath` walk is gone with it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val notes: Flow<List<Note>> = filter.flatMapLatest(::notesFor)

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

    /** Three flows bundled into one, because `combine` tops out at five. */
    private val tagState: Flow<TagState> = combine(tree, counts, undo, ::TagState)

    val state: StateFlow<ShellUiState> =
        combine(filter, notes, selection, expanded, tagState) { f, n, sel, exp, tg ->
            ShellUiState(
                filter = f,
                notes = n,
                selectedNoteId = sel.id,
                selectedNoteBody = sel.body,
                pinnedTags = tg.tree.pinned(tg.counts),
                tagRows = tg.tree.flatten(exp, tg.counts),
                // Derived from the body already in hand, not queried. The chip row and the
                // note_tags rows therefore cannot disagree — they come from one scanner.
                selectedNoteTags = parseTags(sel.body),
                allTagPaths = tg.tree.tagPaths(),
                undo = tg.undo,
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

    // --- tag actions ---------------------------------------------------------

    fun onPinTag(tagId: String, pinned: Boolean) {
        viewModelScope.launch { pinTag(tagId, pinned) }
    }

    fun onSetTagIcon(tagId: String, iconKey: String?) {
        viewModelScope.launch { setTagIcon(tagId, iconKey) }
    }

    /**
     * Renames, then offers undo.
     *
     * If the filter was sitting on the tag being renamed it is **moved to the new path**, not reset
     * to All: the user is still looking at the same set of notes, and dropping them back to
     * everything would be the shell losing their place as a side effect of a rename. A renamed
     * descendant follows the same splice the bodies did.
     */
    fun onRenameTag(from: String, to: String) {
        viewModelScope.launch {
            val snapshot = renameTag(from, to) ?: return@launch
            followFilter(from) { path -> to + path.removePrefix(from) }
            offerUndo(snapshot, TagPrompt.Kind.Renamed, to)
        }
    }

    /** Deleting the tag the list is filtered by leaves nothing to show, so the filter goes home. */
    fun onDeleteTag(path: String) {
        viewModelScope.launch {
            val snapshot = deleteTag(path)
            followFilter(path) { null }
            offerUndo(snapshot, TagPrompt.Kind.Deleted, path)
        }
    }

    /**
     * The one outcome that has to be said out loud is [AddTagResult.NotWritable].
     *
     * `AddTagToNote` exists because a body ending in an unclosed fence swallows the appended tag and
     * the menu item appears to do nothing. Detecting that and then discarding the result would
     * reproduce the same silence one layer up. `AlreadyPresent` stays quiet on purpose — the note
     * already carries the tag, which is what the user asked for.
     */
    fun onAddTagToNote(noteId: String, name: String) {
        viewModelScope.launch {
            if (addTagToNote(noteId, name) == AddTagResult.NotWritable) {
                pendingUndo = null
                undo.value = TagPrompt(TagPrompt.Kind.AddFailed, name)
            }
        }
    }

    /** The tags on a note, scanned on demand from the body already in [state]. */
    fun tagsOfNote(noteId: String): List<String> =
        state.value.notes.find { it.id == noteId }?.let { parseTags(it.body) }.orEmpty()

    fun onUndo() {
        viewModelScope.launch {
            pendingUndo?.let { undoTagChange(it) }
            pendingUndo = null
            undo.value = null
        }
    }

    /** The Snackbar has been shown and dismissed; drop the bodies it was holding. */
    fun onUndoShown() {
        pendingUndo = null
        undo.value = null
    }

    private fun offerUndo(snapshot: BodySnapshot, kind: TagPrompt.Kind, name: String) {
        pendingUndo = snapshot
        undo.value = TagPrompt(kind, name, snapshot.noteCount)
    }

    /**
     * Moves the active filter when the tag it names is renamed or deleted.
     *
     * Applies to descendants too — filtering `projects/duck/ui` while `projects` is renamed has to
     * follow, or the sidebar keeps naming a path that no longer exists and the list silently empties.
     */
    private inline fun followFilter(path: String, next: (String) -> String?) {
        val current = filterKeyPath() ?: return
        if (current != path && !current.startsWith("$path/")) return
        handle[KEY_FILTER] = next(current)?.let { ShellFilter.Tag(it).key } ?: ShellFilter.All.key
    }

    private fun filterKeyPath(): String? =
        (ShellFilter.fromKey(handle[KEY_FILTER]) as? ShellFilter.Tag)?.path

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
    private fun notesFor(filter: ShellFilter): Flow<List<Note>> = when (filter) {
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
        is ShellFilter.Tag -> observeNotes.byTagPath(filter.path)
    }

    /** Local midnight, from the injected [Clock] so the Today row is deterministic under test. */
    private fun startOfToday(): Long = ZoneId.systemDefault().let { zone ->
        Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
    }

    private data class Selection(val id: String? = null, val body: String = "")

    private data class TagState(
        val tree: List<TagNode>,
        val counts: Map<String, TagCount>,
        val undo: TagPrompt?,
    )

    private companion object {
        const val KEY_FILTER = "shell.filter"
        const val KEY_SELECTED = "shell.selectedNoteId"
        const val KEY_EXPANDED = "shell.expandedTagPaths"

        /** Outlives a rotation, so Room's flows are not torn down and rebuilt for one. */
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
