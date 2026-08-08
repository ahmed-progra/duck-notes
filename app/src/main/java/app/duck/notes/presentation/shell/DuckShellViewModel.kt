package app.duck.notes.presentation.shell

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.duck.notes.domain.Clock
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.markdown.TagScanner
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
import app.duck.notes.domain.usecase.SaveNote
import app.duck.notes.domain.usecase.SetTagIcon
import app.duck.notes.domain.usecase.UndoTagChange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
 * ## Phase 8a folded the editor's buffer in here
 *
 * Phase 6 handed the editor a `String` and said autosave was Phase 8's. This is that. [buffer] lives
 * here rather than in a second ViewModel for one concrete reason: **all four operations that rewrite
 * note bodies from outside the editor are intents on this class** — [onRenameTag], [onDeleteTag],
 * [onAddTagToNote] and [onUndo]. Keeping the buffer here makes "flush before you write" a suspend
 * call in the same coroutine. Split across two Hilt-scoped ViewModels it would need a shared
 * coordinator object with exactly one caller, which the GLOBAL CONTRACT forbids.
 *
 * [buffer] is a plain field, deliberately **not** in [handle]: a `TextFieldState` holds the whole
 * note body, and that is precisely what must stay out of the saved-instance `Bundle`. It survives a
 * rotation and a 600dp resize because the `ViewModelStore` is retained; it does not survive process
 * death, and does not need to, because the body was in Room at most [MAX_WAIT_MS] ago.
 */
@HiltViewModel
class DuckShellViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val observeNotes: ObserveNotes,
    private val observeNote: ObserveNote,
    private val saveNote: SaveNote,
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

    /** Fold state is keyed by note id, so switching notes cannot leak a heading collapse. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val collapsedSections: Flow<Set<String>> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptySet())
        else handle.getStateFlow(foldedKey(id), ArrayList<String>()).map { it.toSet() }
    }

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
     *
     * A `StateFlow`, not a cold `Flow`, since 8a. Two consumers now read it — [state]'s `combine` and
     * the buffer-sync collector in `init` — and sharing means **one** `observeNote` query rather than
     * two subscriptions to the same row.
     *
     * The buffer collector never unsubscribes, so `WhileSubscribed` never actually times out for this
     * one flow. That is the deliberate cost of keeping the open note's body live: adopting an
     * external change (a tag rename that rewrote it) is only possible while something is watching.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val selection: StateFlow<Selection> = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(Selection())
        else observeNote(id).map { note -> note?.let { Selection(id, it.body) } ?: Selection() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Selection())

    /** Three flows bundled into one, because `combine` tops out at five. */
    private val tagState: Flow<TagState> = combine(tree, counts, undo, ::TagState)

    private val editorState: Flow<EditorState> =
        combine(filter, notes, selection, expanded, collapsedSections) { f, n, sel, exp, collapsed ->
            EditorState(f, n, sel, exp, collapsed)
        }

    val state: StateFlow<ShellUiState> =
        combine(editorState, tagState) { editor, tg ->
            ShellUiState(
                filter = editor.filter,
                notes = editor.notes,
                selectedNoteId = editor.selection.id,
                selectedNoteBody = editor.selection.body,
                pinnedTags = tg.tree.pinned(tg.counts),
                tagRows = tg.tree.flatten(editor.expanded, tg.counts),
                // Derived from the body already in hand, not queried. The chip row and the
                // note_tags rows therefore cannot disagree — they come from one scanner.
                selectedNoteTags = parseTags(editor.selection.body),
                allTagPaths = tg.tree.tagPaths(),
                collapsedSections = editor.collapsedSections,
                undo = tg.undo,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShellUiState())

    // --- the editor buffer, and the one write path ---------------------------

    /** The open note's text. Hoisted out of the composable in 8a — see the class KDoc. */
    val buffer = TextFieldState()

    /**
     * Which note's text [buffer] currently holds. **Not** [selectedId], which moves the instant a row
     * is tapped, while this moves only once the body has actually been loaded.
     *
     * A `StateFlow` because the autosave loop keys on it. Keying on [selectedId] instead looks
     * equivalent and is not: the loop would start the moment a row was tapped, observe the buffer
     * still holding the *previous* note (or, on a fresh ViewModel, nothing), and write that. The
     * empty body then becomes [lastWritten], every later external change reads as "buffer is dirty",
     * and adoption stops happening at all. Six tests in `DuckShellAutosaveTest` failed on exactly
     * that cascade before the key moved here.
     */
    private val loadedId = MutableStateFlow<String?>(null)

    /**
     * The exact string last written to Room, or last loaded from it. The gate on **every** save path,
     * not just [flush]: without it, opening a note would immediately write it straight back and
     * restamp `modifiedAt` for nothing, reordering the list on a read.
     */
    private var lastWritten: String? = null

    /**
     * Whether the last write dropped a tag that was still being typed, and therefore owes one more
     * write to mint it.
     *
     * Completing a tag changes no character — `#work` is the same string whether the caret sits on
     * it or not — so the [lastWritten] gate would swallow the write that finally records it, and the
     * tag would appear only after the *next* edit. This flag is what lets [flush] through.
     *
     * **Only a different note retires it — never an [adopt] of our own text.** The ordinary case of
     * adopting is Room echoing back the very write that took on the debt, so clearing it there means
     * the flush that should mint the tag finds nothing owed and the tag never appears at all. That
     * is why [adopt] takes `keepTagDebt`, and it was a real failure, not a hypothetical one:
     * `aTagLeftUnderTheCaretIsMintedByTheFlush` caught it.
     *
     * **And it must not outlive [loadedId].** Left standing across a note switch, the next note's
     * first flush bypasses its own [lastWritten] gate and writes a note the user only opened. The
     * path there is [onFilter], which clears the selection *without* flushing — [onSelectNote]
     * flushes first and so pays the debt on the way out, which is exactly why the leak is invisible
     * from the obvious route. Same cascade shape that put the autosave loop on [loadedId] instead of
     * [selectedId].
     */
    private var oweFullWrite = false

    /**
     * Serialises the three entrants to [save] — the debounce, the max-wait, and [flush].
     *
     * `flush()` at the top of an intent does not stop a debounced save that is already in flight, so
     * without this a rename could interleave with the write it was meant to follow.
     */
    private val writeLock = Mutex()

    init {
        // Buffer sync. Keyed on the note id inside `sync`, so this handles both "a different note was
        // selected" and "the open note's body changed underneath us".
        viewModelScope.launch { selection.collect(::syncBuffer) }

        // Autosave. flatMapLatest on the id is what makes the (id, text) pairing atomic: a note
        // switch CANCELS the collector, so an emission produced while note A was open can never be
        // delivered after the swap and land on note B. The id travels with the text rather than being
        // read again at write time, which is the race a lock alone does not close.
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            loadedId.flatMapLatest { id ->
                if (id == null) emptyFlow() else snapshotFlow { buffer.text }.cappedDebounce().map { id }
            }.collect { save(it, suppressInProgressTag = true) }
        }
    }

    /**
     * The only write path for a note body in this class. Idempotent: a redundant trigger costs one
     * string comparison, not a write.
     *
     * **Takes no text — it reads [buffer] under the lock.** The emission that triggered this is a
     * signal that something changed, never the payload. Trusting the payload is a real defect, not a
     * theoretical one: `adopt` and [rewriteBuffer] mutate the buffer without anything calling
     * `Snapshot.sendApplyNotifications`, so `snapshotFlow` has not re-emitted and the value still in
     * flight is the text from *before* the rewrite. A rename would then be reverted by the very next
     * debounce — `aDirtyBufferIsRewrittenByARenameAndTheOldTagIsNotResurrected` caught exactly that,
     * with the buffer correct and Room wrong.
     */
    private suspend fun save(id: String, suppressInProgressTag: Boolean) = writeLock.withLock {
        // Adjacent, and in this order: there is no suspension point between them, so `adopt` and
        // [rewriteBuffer] cannot interleave and the caret cannot be stale against the text it
        // indexes. Keep them together if this method grows.
        val text = buffer.text.toString()
        val selection = buffer.selection
        // A non-collapsed selection is someone selecting, not someone typing — nothing is in
        // progress, so nothing is suppressed. Held by
        // `DuckShellAutosaveTest.aRangeSelectionOverATagSuppressesNothing`, which drives a range
        // whose `start` lands on a finished tag's span end; without `collapsed` that tag is dropped.
        // Naming it here because the test that was named for this conjunct spent a phase unable to
        // reach it — a selection change alone emits nothing, so no save runs at all.
        val caret = selection.start.takeIf { suppressInProgressTag && selection.collapsed }

        // The buffer holds [loadedId]'s text, which is NOT always the selected id: between a row
        // being tapped and its body arriving they differ, and the buffer still holds the old note —
        // or, on a fresh ViewModel, nothing at all. Writing then would save an empty body over the
        // incoming note. `aSelectionRestoredOntoADeletedNoteClearsItself` caught exactly that: a
        // selection restored onto a hard-deleted note re-created it from an empty buffer.
        if (id != loadedId.value) return@withLock

        // Unchanged text still owes a write when the last one suppressed a tag — completing `#work`
        // changes no character, so this gate would otherwise swallow the write that mints it.
        val owed = oweFullWrite && !suppressInProgressTag
        if (text == lastWritten && !owed) return@withLock

        // An owed write carries no new keystrokes, only the tag, so it must not age the note.
        saveNote(id, text, caret, restamp = !(owed && text == lastWritten))
        lastWritten = text
        oweFullWrite = caret != null && parseTags(text, caret) != parseTags(text)
    }

    /**
     * Write now, without waiting for the debounce. Called before anything that rewrites bodies, and
     * on `ON_STOP` so an orderly departure loses nothing.
     *
     * Suspends until the write lands, so callers can rely on Room being current when it returns.
     */
    suspend fun flush() {
        // Suppresses nothing: the user is leaving, or handing control to an intent that rewrites the
        // body. There is no next keystroke to wait for, so a tag typed and left is minted here.
        save(loadedId.value ?: return, suppressInProgressTag = false)
    }

    /**
     * `ON_STOP` — the hook that turns "≤2s of typing at risk" into "nothing at risk" for every
     * departure the user can cause from the UI: home, recents, screen off, task switch.
     *
     * **[viewModelScope], deliberately.** A `rememberCoroutineScope()` is cancelled when the
     * composable leaves the composition, which is exactly what is happening at `ON_STOP` — the flush
     * would be cancelled by the event that triggered it. `viewModelScope` outlives the composition.
     *
     * Nothing recovers a kill that fires no `ON_STOP` at all — a crash, `am force-stop`, a kernel OOM
     * — short of a Room write per keystroke. That residual is bounded by [MAX_WAIT_MS] and is stated
     * in the phase's amended success bar rather than papered over.
     */
    fun onStop() {
        viewModelScope.launch { flush() }
    }

    /**
     * Reconciles [buffer] with what Room says the open note holds.
     *
     * Three cases, and the third is the one that matters:
     * - **A different note** (or none): load it. [lastWritten] is set *before* the buffer is written,
     *   or `snapshotFlow` fires against a stale gate and the load writes itself straight back.
     * - **Same note, buffer clean**: an external change — adopt it.
     * - **Same note, buffer dirty**: ignore. The user's unsaved keystrokes win, and the intent that
     *   caused the external change has already applied the same transform to the buffer via
     *   [rewriteBuffer]. Adopting here would discard what they typed.
     *
     * **Holds [writeLock].** [loadedId], [lastWritten], [oweFullWrite] and the buffer describe one
     * note between them, so they have to move together — a save landing mid-swap would otherwise see
     * a mixture of the two. [onUndo] already holds the lock across its own [adopt] and acquires
     * nothing new, so this introduces no nesting.
     */
    private suspend fun syncBuffer(sel: Selection) = writeLock.withLock {
        if (sel.id == loadedId.value) {
            if (buffer.text.toString() == lastWritten) adopt(sel.body, keepTagDebt = true)
            return@withLock
        }
        adopt(sel.body)
        // Last, deliberately: this is what starts the autosave loop for the new note, and it must not
        // start until the buffer and the gate below both hold that note's text.
        loadedId.value = sel.id
    }

    /**
     * @param keepTagDebt true only when adopting **the same note's** body. [oweFullWrite] describes a
     *   tag still owed for the text in the buffer, and the ordinary case of adopting here is Room
     *   echoing back the very write that took on the debt — clearing it there means the flush that
     *   should mint the tag finds nothing owed and the tag never appears at all. A different note
     *   retires the debt, because the text it referred to is gone.
     *
     * **A Phase 8a defect, found by 8c-b, fixed here.** `syncBuffer`'s "same note, buffer clean"
     * branch reaches this with [body] equal to what [buffer] already holds — Room echoing back the
     * write that just landed — on every successful autosave. Adopting unconditionally therefore
     * re-clamped the caret to the end of the note after every save, not just on a real external
     * change. The guard below is the whole fix: nothing to adopt, so nothing moves.
     */
    private fun adopt(body: String, keepTagDebt: Boolean = false) {
        lastWritten = body
        if (!keepTagDebt) oweFullWrite = false
        if (body == buffer.text.toString()) return // nothing to adopt; do not move the caret
        buffer.setTextAndPlaceCursorAtEnd(body)
        // Undo must not walk backwards across a change the user did not make: the result would be
        // text that is neither the pre- nor the post-change body.
        buffer.undoState.clearHistory()
    }

    /**
     * Applies a body transform to the **live** buffer, so an external rewrite is not lost to unsaved
     * typing — and, more importantly, so the next autosave does not write the pre-rewrite text back.
     *
     * Adopt-when-clean alone is not enough. It blocks the adoption but leaves the old tag sitting in
     * a dirty buffer, and the following save then re-derives tags from it and `ensureTag` **mints the
     * deleted row again**. The buffer is rewritten, never discarded.
     *
     * [lastWritten] gets the same transform, because Room's copy underwent exactly this one: a buffer
     * that was clean before stays clean, and one that was dirty stays dirty by the user's own edits.
     *
     * ponytail: whole-buffer replace, so the caret is re-clamped rather than offset-mapped — it
     * drifts by one length delta per occurrence before it (`work`->`job` is 1 char each). Exact
     * mapping would need `TagScanner` to return edit positions, for a drift after an operation
     * started in the sidebar rather than the editor. Revisit if 8b's toolbar needs real offset maps.
     */
    private fun rewriteBuffer(transform: (String) -> String) {
        val current = buffer.text.toString()
        val next = transform(current)
        lastWritten = lastWritten?.let(transform)
        if (next == current) return
        buffer.edit {
            val caret = selection.start
            replace(0, length, next)
            selection = TextRange(caret.coerceAtMost(length))
        }
        buffer.undoState.clearHistory()
    }

    // --- intents -------------------------------------------------------------

    /** Changing the filter clears the selection: the open note is usually not in the new list. */
    fun onFilter(next: ShellFilter) {
        handle[KEY_FILTER] = next.key
        handle[KEY_SELECTED] = null
    }

    /** Flushes the outgoing note **before** the id moves, or its last keystrokes are lost. */
    fun onSelectNote(id: String) {
        viewModelScope.launch {
            flush()
            handle[KEY_SELECTED] = id
        }
    }

    fun onClearSelection() {
        viewModelScope.launch {
            flush()
            handle[KEY_SELECTED] = null
        }
    }

    fun onToggleTag(path: String) {
        val current = handle.get<ArrayList<String>>(KEY_EXPANDED).orEmpty()
        handle[KEY_EXPANDED] = ArrayList(if (path in current) current - path else current + path)
    }

    /**
     * Persists a heading fold for the currently selected note in the same saved-state channel as
     * shell UI, **and keeps only the open note's folds**.
     *
     * The sweep is not tidiness. Without it one `shell.foldedSections.<id>` key accumulates per note
     * folded, for the life of the process, and [handle] is written into the saved-instance `Bundle`
     * — the 1MB per-process Binder buffer that Phase 8a's saver drop exists to protect. Unbounded
     * growth there regresses 8a's own invariant, that the `Bundle` carries the filter, the note id
     * and the expanded tag paths and nothing that scales with content.
     *
     * **Evicting rather than consolidating into one map-valued key** is deliberate: a map still
     * gains an entry per note and puts the same bytes through the same transaction, so it relocates
     * the growth instead of bounding it. This bounds it structurally at one key, which is a property
     * a test can state exactly rather than a threshold someone has to defend.
     *
     * **Swept on write, not on selection change.** [onFilter] clears the selection without flushing
     * — the leak path 8a-fix documents — so anything hung off the transition inherits that hole.
     * Keying off the write cannot miss.
     *
     * Cost, stated rather than hidden: folding a section in note A, moving to B and coming back
     * finds A expanded. Fold state is cosmetic and it never survived a cold launch either way.
     */
    fun onToggleSection(key: String) {
        val id = selectedId.value ?: return
        val stateKey = foldedKey(id)
        // toList() first: handle.keys() is a live view, and removing through it would throw.
        handle.keys().toList()
            .filter { it.startsWith(FOLD_PREFIX) && it != stateKey }
            .forEach { handle.remove<Any>(it) }
        val current = handle.get<ArrayList<String>>(stateKey).orEmpty().toSet()
        handle[stateKey] = ArrayList(if (key in current) current - key else current + key)
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
            // Flush first, so the transaction reads the text the user can see rather than whatever
            // the last debounce happened to leave in Room.
            flush()
            val snapshot = renameTag(from, to) ?: return@launch
            // Then the same rewrite on the live buffer. No membership check is needed: TagScanner
            // returns the body unchanged when it does not carry the tag.
            rewriteBuffer { TagScanner.rename(it, from, to) }
            followFilter(from) { path -> to + path.removePrefix(from) }
            offerUndo(snapshot, TagPrompt.Kind.Renamed, to)
        }
    }

    /** Deleting the tag the list is filtered by leaves nothing to show, so the filter goes home. */
    fun onDeleteTag(path: String) {
        viewModelScope.launch {
            flush()
            val snapshot = deleteTag(path)
            rewriteBuffer { TagScanner.remove(it, path) }
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
            // Flush first, so the append lands on the text the user can see. Unlike rename and
            // delete there is no matching [rewriteBuffer] call, and that asymmetry is deliberate:
            // the append rule (trimEnd, own line, TagScanner.token) lives inside AddTagToNote, and
            // re-applying it here would be a second implementation of tag syntax — the one thing
            // this codebase does not allow.
            //
            // ponytail: the append therefore survives only because flush() leaves the buffer clean
            // and the sync collector then adopts the appended body — NOT because anything reconciles
            // the two. `anAppendedTagSurvivesAnUnsavedBufferViaFlushAndAdoption` pins that chain, and
            // it is a chain rather than a mechanism: if a future change makes adoption conditional,
            // or moves the flush, the tag is silently lost. Ceiling: keystrokes landing *between* the
            // flush and the adoption still shadow it. Upgrade path is `TagScanner.append`, which
            // makes the append a transform so `rewriteBuffer` handles it like the other two — worth
            // doing the moment a second caller wants it, not before.
            flush()
            if (addTagToNote(noteId, name) == AddTagResult.NotWritable) {
                pendingUndo = null
                undo.value = TagPrompt(TagPrompt.Kind.AddFailed, name)
            }
        }
    }

    /** The tags on a note, scanned on demand from the body already in [state]. */
    fun tagsOfNote(noteId: String): List<String> =
        state.value.notes.find { it.id == noteId }?.let { parseTags(it.body) }.orEmpty()

    /**
     * Restores the snapshot, then **adopts the restored body unconditionally** — the one place that
     * overrides adopt-when-clean, deliberately.
     *
     * A restore is not expressible as a [TagScanner] transform: it writes N exact previous bodies
     * back. There is nothing to apply to a dirty buffer. And "put it back" is an explicit command
     * the user just pressed, which is a different thing from a rename happening beside them — so the
     * restored text wins, and typing done between the Snackbar appearing and Undo being pressed is
     * discarded.
     */
    fun onUndo() {
        viewModelScope.launch {
            // NO flush, and this is the one intent that must not have one. The other three flush so
            // the transaction reads current text; undo's contract is the opposite — *discard what
            // happened since the snapshot*. Flushing first writes the user's typing to Room and then
            // restores the snapshot straight over it, so the typing is destroyed **on disk**, not
            // merely in the buffer, and `SaveNote` restamps `modifiedAt` on the way past for a write
            // whose entire lifetime is two statements.
            //
            // The typing is still lost from the buffer, because `adopt` below is unconditional. That
            // is undo doing its job. What changes is that Room is never polluted with a body the user
            // asked to revert, and the note returns to exactly its pre-operation state.
            val snapshot = pendingUndo
            pendingUndo = null
            undo.value = null
            if (snapshot == null) return@launch

            // The restore and the adopt are ONE critical section, and removing the flush is exactly
            // why. The flush used to drain the pending debounced save as a side effect; without it,
            // that save can fire in the gap between the restore transaction and the adopt — reading a
            // buffer that still holds the user's typing, against a [lastWritten] that is still the
            // pre-restore value, so the gate waves it through and it overwrites the restored body.
            // The adopt that follows would then read Room and faithfully adopt the clobbered text.
            //
            // Holding the lock across both makes the blocked save harmless rather than merely
            // unlikely: it resumes after [adopt] has set [lastWritten] to the restored body, sees
            // buffer == lastWritten, and writes nothing. Cancelling the pending emission instead
            // would need new machinery to reach inside `cappedDebounce`; this reuses the lock that
            // already serialises flush against the debounce, and is correct by construction rather
            // than by timing.
            writeLock.withLock {
                undoTagChange(snapshot)
                loadedId.value?.let { id -> observeNote(id).first()?.let { adopt(it.body) } }
            }
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

    private data class EditorState(
        val filter: ShellFilter,
        val notes: List<Note>,
        val selection: Selection,
        val expanded: Set<String>,
        val collapsedSections: Set<String>,
    )

    private data class TagState(
        val tree: List<TagNode>,
        val counts: Map<String, TagCount>,
        val undo: TagPrompt?,
    )

    // Not private since 8a: `AutosaveDurabilityTest` waits out [MAX_WAIT_MS] on a real clock, and a
    // test that hardcoded 2000 would keep passing after the constant moved.
    companion object {
        private const val KEY_FILTER = "shell.filter"
        private const val KEY_SELECTED = "shell.selectedNoteId"
        private const val KEY_EXPANDED = "shell.expandedTagPaths"

        /**
         * One prefix, two consumers — [foldedKey] mints them and [onToggleSection]'s sweep reaps
         * them. Separate literals would drift, and the failure mode of a drifted sweep is silence.
         */
        private const val FOLD_PREFIX = "shell.foldedSections."

        private fun foldedKey(noteId: String) = "$FOLD_PREFIX$noteId"

        /** Outlives a rotation, so Room's flows are not torn down and rebuilt for one. */
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Save this long after the last keystroke. The spec's number. */
        const val IDLE_MS = 400L

        /**
         * And save at least this often *while* typing continues.
         *
         * A debounce fires when typing **stops**, so a continuous typist never triggers one and the
         * unsaved window is unbounded, not 400ms — 60 seconds of steady typing is 60 seconds at
         * risk. This is the ceiling that makes the number finite, and it is what the phase's amended
         * success bar quotes.
         */
        const val MAX_WAIT_MS = 2_000L
    }
}

/**
 * Debounce with a ceiling: emits [DuckShellViewModel.IDLE_MS] after the last change, **or**
 * [DuckShellViewModel.MAX_WAIT_MS] after the first unsaved one, whichever comes first.
 *
 * The ceiling is not a refinement of the debounce, it is what makes the loss window finite. A
 * debounce fires when typing *stops*; someone typing continuously never stops, so a plain
 * `debounce(400)` would never fire at all and the unsaved window would be unbounded.
 *
 * **Hand-rolled rather than `merge(debounce(400), sample(2000))`, for two reasons.** `sample`
 * schedules a *perpetual* ticker, so it never lets the scheduler go idle — under
 * `kotlinx-coroutines-test` that hangs `advanceUntilIdle()` forever, which is most of the shell's
 * existing suite. And `sample` ticks on a fixed grid from subscription, so a change landing just
 * before a tick is saved 1ms later while the ceiling it is supposed to enforce silently resets.
 * This measures the ceiling from the first unsaved change, which is what the bar actually claims.
 *
 * Between saves it parks on `receive()` with nothing scheduled, so the scheduler goes idle.
 *
 * ## Top-level and `internal` so a test can drive it
 *
 * It was `private` inside [DuckShellViewModel], and the guard for it —
 * `theCeilingFiresWhileAPlainDebounceNeverDoes` — could not reach it, so it built its own
 * `debounce(400)` and asserted *that* never fired. True of `kotlinx.coroutines` and true of nothing
 * in this repo: replacing this operator with a plain debounce left that test green, which is the
 * regression it was cited to catch. CLAUDE.md's Guards rule now forbids the shape.
 *
 * Hoisting rather than merely widening the visibility, because it touches no instance state — only
 * the two companion constants — so it was already a top-level function wearing a costume. Same
 * argument that made `applyFormat` `internal` for `DuckShellAutosaveTest`.
 */
internal fun Flow<CharSequence>.cappedDebounce(): Flow<String> = flow {
    coroutineScope {
        val changes = produce(capacity = Channel.CONFLATED) {
            this@cappedDebounce.collect { send(it.toString()) }
        }
        while (true) {
            // Parks here indefinitely while nothing is typed. No timer, so the scheduler idles.
            var latest = changes.receive()
            withTimeoutOrNull(DuckShellViewModel.MAX_WAIT_MS) {
                while (true) {
                    latest = withTimeoutOrNull(DuckShellViewModel.IDLE_MS) { changes.receive() }
                        ?: return@withTimeoutOrNull
                }
            }
            emit(latest)
        }
    }
}
