package app.duck.notes.presentation.shell

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.duck.notes.di.AutosaveDispatcher
import app.duck.notes.domain.Clock
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.markdown.TagScanner
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.model.BodySnapshot
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.NoteLink
import app.duck.notes.domain.model.TagCount
import app.duck.notes.domain.model.TagNode
import app.duck.notes.domain.usecase.AddTagResult
import app.duck.notes.domain.usecase.AddTagToNote
import app.duck.notes.domain.usecase.AttachImage
import app.duck.notes.domain.usecase.DeleteAttachment
import app.duck.notes.domain.usecase.DeleteTag
import app.duck.notes.domain.usecase.GetBacklinks
import app.duck.notes.domain.usecase.ObserveAttachments
import app.duck.notes.domain.usecase.ObserveNote
import app.duck.notes.domain.usecase.ObserveNotes
import app.duck.notes.domain.usecase.ObserveTagCounts
import app.duck.notes.domain.usecase.ObserveTagTree
import app.duck.notes.domain.usecase.OpenWikiLink
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.PinTag
import app.duck.notes.domain.usecase.RenameTag
import app.duck.notes.domain.usecase.SaveNote
import app.duck.notes.domain.usecase.SetTagIcon
import app.duck.notes.domain.usecase.UndoTagChange
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

import app.duck.notes.domain.markdown.MarkdownFormat
import app.duck.notes.domain.model.SearchQuery
import app.duck.notes.domain.usecase.SearchNotes
import app.duck.notes.domain.usecase.ArchiveNote
import app.duck.notes.domain.usecase.DeleteNote
import app.duck.notes.domain.usecase.RestoreNote
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/** Owns per-note editor sessions; text never changes identity during a suspended operation. */
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
    private val attachImage: AttachImage,
    private val deleteAttachment: DeleteAttachment,
    private val openWikiLink: OpenWikiLink,
    observeAttachments: ObserveAttachments,
    getBacklinks: GetBacklinks,
    private val searchNotes: SearchNotes,
    private val archiveNote: ArchiveNote,
    private val deleteNote: DeleteNote,
    private val restoreNote: RestoreNote,
    private val clock: Clock,
    @AutosaveDispatcher private val autosaveDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val filter = handle.getStateFlow<String?>(KEY_FILTER, null).map(ShellFilter::fromKey)
    private val selectedId = handle.getStateFlow<String?>(KEY_SELECTED, null)
    private val expanded = handle.getStateFlow(KEY_EXPANDED, ArrayList<String>()).map { it.toSet() }
    private val selection = MutableStateFlow(Selection())
    private val retired = mutableSetOf<EditorSession>()
    private val writeLock = Mutex()
    private val emptyBuffer = TextFieldState()
    /** Compatibility for non-UI callers. Production rendering uses ShellUiState.editorSession. */
    val buffer: TextFieldState get() = selection.value.session?.buffer ?: emptyBuffer
    internal val editorSession: EditorSession? get() = selection.value.session
    private var navigationRevision = 0L
    private var retryNavigation: (() -> Unit)? = null
    private val failedSession = MutableStateFlow<EditorSession?>(null)
    private val operationFailure = MutableStateFlow<OperationFailure?>(null)
    private val creating = MutableStateFlow(false)
    private var pendingCreatedId: String? = null
    private var pendingUndo: PendingUndo? = null
    private var pickerSession: EditorSession? = null
    private val undo = MutableStateFlow<TagPrompt?>(null)
    private val search = MutableStateFlow(SearchState())
    private var searchJob: Job? = null
    private var searchRevision = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    private val notes = filter.flatMapLatest(::notesFor)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val collapsedSections = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(emptySet())
        else handle.getStateFlow(foldedKey(id), ArrayList<String>()).map { it.toSet() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val selectedAttachments = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(Owned<List<Attachment>>(null, emptyList()))
        else observeAttachments(id).map { Owned(id, it) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val backlinks = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(Owned<List<NoteLink>>(null, emptyList()))
        else getBacklinks(id).map { Owned(id, it) }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val noteTitles = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(Owned<List<String>>(null, emptyList()))
        else observeNotes.titles(id).map { Owned(id, it) }
    }
    private val tagState = combine(observeTagTree(), observeTagCounts(), undo, ::TagState)
    private val linkState = combine(backlinks, handle.getStateFlow(KEY_INFO_OPEN, false), noteTitles, ::LinkState)
    private val editorState = combine(filter, notes, selection, expanded, collapsedSections, ::EditorState)
    private val operations = combine(failedSession, operationFailure, creating, search, ::OperationState)
    val state: StateFlow<ShellUiState> = combine(
        editorState, tagState, selectedAttachments, linkState, operations,
    ) { editor, tags, attachments, link, ops ->
        val session = editor.selection.session
        val note = editor.selection.note
        ShellUiState(
            filter = editor.filter,
            notes = if (ops.search.query.isBlank()) editor.notes else ops.search.notes,
            selectedNoteId = session?.noteId,
            selectedNoteBody = note?.body.orEmpty(),
            editorSession = session,
            selectedNoteArchived = note?.isArchived == true,
            selectedNoteTrashed = note?.isTrashed == true,
            pinnedTags = tags.tree.pinned(tags.counts),
            tagRows = tags.tree.flatten(editor.expanded, tags.counts),
            selectedNoteTags = parseTags(note?.body.orEmpty()),
            selectedNoteAttachments = attachments.value.takeIf { attachments.id == session?.noteId }.orEmpty(),
            allTagPaths = tags.tree.tagPaths(),
            collapsedSections = editor.collapsedSections,
            selectedNoteBacklinks = link.backlinks.value.takeIf { link.backlinks.id == session?.noteId }.orEmpty(),
            infoOpen = link.infoOpen,
            selectedNoteCreatedAt = note?.createdAt,
            selectedNoteModifiedAt = note?.modifiedAt,
            allNoteTitles = link.titles.value.takeIf { link.titles.id == session?.noteId }.orEmpty(),
            undo = tags.undo,
            saveFailed = ops.failed != null,
            failedNoteTitle = ops.failed?.note?.title,
            operationFailure = ops.failure,
            creatingNote = ops.creating,
            search = ops.search,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ShellUiState())

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            selectedId.flatMapLatest { id ->
                if (id == null) flowOf(null to null)
                else observeNote(id).map { id to it }
            }.collect { (requested, _) ->
                writeLock.withLock {
                    if (requested != selectedId.value) return@withLock
                    // An emission can queue behind a body rewrite. Read again while owning the
                    // writer lock so an older emission cannot replace the committed editor text.
                    val note = requested?.let { observeNote(it).first() }
                    val current = editorSession
                    if (current == null && note != null) {
                        publish(note)
                    } else if (current != null && current.noteId == requested && note != null) {
                        current.note = note
                        current.readOnly = note.isTrashed
                        if (current.buffer.text.toString() == current.lastWritten) adopt(current, note.body, true)
                        selection.value = Selection(current, note)
                    } else if (current != null && current.noteId == requested && note == null) {
                        if (current.buffer.text.toString() == current.lastWritten) publish(null)
                        else {
                            current.missing = true
                            current.readOnly = true
                            failedSession.value = current
                        }
                    } else if (current == null && note == null && requested != null) {
                        handle[KEY_SELECTED] = null
                    }
                }
            }
        }
        viewModelScope.launch {
            observeNotes().distinctUntilChanged().collect {
                if (search.value.query.isNotBlank()) scheduleSearch(search.value.query)
            }
        }
    }

    private fun publish(note: Note?) {
        if (note != null && note.id == pendingCreatedId) {
            pendingCreatedId = null
            operationFailure.value = null
        }
        val outgoing = editorSession
        if (note != null && outgoing?.noteId == note.id) {
            outgoing.note = note
            outgoing.readOnly = note.isTrashed
            selection.value = Selection(outgoing, note)
            return
        }
        outgoing?.let {
            it.retired = true
            if (it.editors == 0) close(it) else retired.add(it)
        }
        val next = note?.let(::EditorSession)
        selection.value = Selection(next, note)
        handle[KEY_SELECTED] = note?.id
        if (next != null) next.autosave = viewModelScope.launch(autosaveDispatcher) {
            snapshotFlow { next.buffer.text }.cappedDebounce().collect {
                try {
                    writeLock.withLock { save(next, true) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { failedSession.value = next }
            }
        }
    }

    fun onEditorAttached(session: EditorSession) {
        if (!session.closed) {
            if (session.editors == 0) session.detached = kotlinx.coroutines.CompletableDeferred()
            session.editors++
        }
    }

    fun onEditorDetached(session: EditorSession) {
        session.editors = (session.editors - 1).coerceAtLeast(0)
        if (session.editors != 0) return
        session.detached.complete(Unit)
        if (session.retired) viewModelScope.launch {
            try {
                writeLock.withLock {
                    flush(session)
                    close(session)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failedSession.value = session }
        }
    }

    private fun close(session: EditorSession) {
        session.closed = true
        session.autosave?.cancel()
        retired.remove(session)
    }

    /** Called under writeLock; the session remains the owner across every suspension. */
    private suspend fun save(session: EditorSession, suppressInProgressTag: Boolean) {
        if (session.closed) return
        check(!session.missing) { "The originating note is no longer available" }
        val text = session.buffer.text.toString()
        val selection = session.buffer.selection
        val caret = selection.start.takeIf { suppressInProgressTag && selection.collapsed }
        val owed = session.oweFullWrite && !suppressInProgressTag
        if (text != session.lastWritten || owed) {
            val note = saveNote(session.noteId, text, caret, restamp = !(owed && text == session.lastWritten))
            session.note = note
            session.lastWritten = text
            session.oweFullWrite = caret != null && parseTags(text, caret) != parseTags(text)
        }
        if (session.buffer.text.toString() == session.lastWritten && !session.oweFullWrite && failedSession.value === session) {
            failedSession.value = null
            retryNavigation = null
        }
    }

    private suspend fun flush(session: EditorSession?) {
        if (session == null || session.closed) return
        try {
            do { save(session, false) }
            while (session.buffer.text.toString() != session.lastWritten || session.oweFullWrite)
        } catch (failure: Exception) {
            if (failure !is CancellationException) failedSession.value = session
            throw failure
        }
    }

    suspend fun flush() = writeLock.withLock {
        (retired.toList() + listOfNotNull(editorSession)).forEach { session ->
            try { flush(session) } catch (failure: Exception) {
                if (failure !is CancellationException) failedSession.value = session
                throw failure
            }
        }
    }

    private suspend fun settleRetired() {
        retired.toList().forEach { session ->
            if (session.editors > 0) session.detached.await()
            writeLock.withLock {
                try { flush(session); close(session) }
                catch (failure: Exception) {
                    if (failure !is CancellationException) failedSession.value = session
                    throw failure
                }
            }
        }
    }

    fun onStop() {
        viewModelScope.launch {
            try { flush() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* flush retains the failed session for Retry. */ }
        }
    }

    private fun adopt(session: EditorSession, body: String, keepTagDebt: Boolean = false) {
        session.lastWritten = body
        if (!keepTagDebt) session.oweFullWrite = false
        if (body == session.buffer.text.toString()) return
        session.buffer.setTextAndPlaceCursorAtEnd(body)
        session.buffer.undoState.clearHistory()
    }

    private fun rewriteBuffer(session: EditorSession, transform: (String) -> String) {
        if (session.closed) return
        val current = session.buffer.text.toString()
        val next = transform(current)
        session.lastWritten = transform(session.lastWritten)
        if (next == current) return
        session.buffer.edit {
            val caret = selection.start
            replace(0, length, next)
            selection = TextRange(caret.coerceAtMost(length))
        }
        session.buffer.undoState.clearHistory()
    }

    private fun navigate(replay: Boolean = true, action: suspend (Long, EditorSession?) -> Unit) {
        val revision = ++navigationRevision
        val origin = editorSession
        retryNavigation = null
        viewModelScope.launch {
            try {
                settleRetired()
                flush()
                if (revision != navigationRevision || origin !== editorSession) return@launch
                action(revision, origin)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == navigationRevision) {
                    if (failedSession.value != null && replay) retryNavigation = { navigate(action = action) }
                    if (failedSession.value == null) operationFailure.value = OperationFailure.Open
                }
            }
        }
    }

    private suspend fun select(note: Note?, revision: Long, origin: EditorSession?, nextFilter: ShellFilter? = null) {
        writeLock.withLock {
            if (revision != navigationRevision || origin !== editorSession) return@withLock
            try { flush(origin) } catch (failure: Exception) {
                if (failure !is CancellationException) failedSession.value = origin
                throw failure
            }
            val current = note?.let { observeNote(it.id).first() }
            if (revision != navigationRevision) return@withLock
            check(note == null || current != null) { "The selected note is no longer available" }
            nextFilter?.let { handle[KEY_FILTER] = it.key; onSearchChanged("") }
            publish(current)
        }
    }

    fun onFilter(next: ShellFilter) = navigate { revision, origin -> select(null, revision, origin, next) }
    fun onSelectNote(id: String) = navigate { revision, origin ->
        val note = observeNote(id).first() ?: return@navigate
        select(note, revision, origin)
    }
    fun onClearSelection() = navigate { revision, origin -> select(null, revision, origin) }
    fun onRetryNavigation() {
        val retry = retryNavigation
        if (retry != null) retry() else navigate { _, _ -> }
    }

    fun onNewNote() {
        if (creating.value) return
        creating.value = true
        val revision = ++navigationRevision
        val origin = editorSession
        retryNavigation = null
        operationFailure.value = null
        viewModelScope.launch {
            try {
                settleRetired()
                flush()
                if (revision != navigationRevision) return@launch
                val created = pendingCreatedId?.let { id ->
                    observeNote(id).first()?.takeUnless { it.isTrashed } ?: run {
                        pendingCreatedId = null
                        error("The created note is no longer available")
                    }
                } ?: saveNote(null, "").also { pendingCreatedId = it.id }
                select(created, revision, origin, ShellFilter.All)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == navigationRevision) {
                    operationFailure.value = OperationFailure.Create
                    val id = pendingCreatedId
                    if (id != null && failedSession.value != null) retryNavigation = {
                        navigate { nextRevision, nextOrigin ->
                            val created = observeNote(id).first()?.takeUnless { it.isTrashed }
                            if (created == null) {
                                if (pendingCreatedId == id) pendingCreatedId = null
                                error("The created note is no longer available")
                            }
                            select(created, nextRevision, nextOrigin, ShellFilter.All)
                        }
                    }
                }
            }
            finally { creating.value = false }
        }
    }

    fun onOpenWikiLink(title: String) = onOpenWikiLink(editorSession, title)
    fun onOpenWikiLink(origin: EditorSession?, title: String) {
        if (origin == null || origin !== editorSession || !origin.canEdit) return
        operationFailure.value = null
        navigate(replay = false) { revision, _ ->
            val target = openWikiLink(title)
            val note = observeNote(target).first() ?: return@navigate
            select(note, revision, origin)
        }
    }

    fun onSearchChanged(query: String) = scheduleSearch(query)
    fun onRetrySearch() = scheduleSearch(search.value.query)
    private fun scheduleSearch(query: String) {
        val revision = ++searchRevision
        searchJob?.cancel()
        search.value = SearchState(query, if (query.isBlank()) SearchPhase.Idle else SearchPhase.Pending)
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(200)
            try {
                val matches = searchNotes(SearchQuery(query))
                if (revision == searchRevision) search.value = SearchState(query, SearchPhase.Results, matches)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == searchRevision) search.value = SearchState(query, SearchPhase.Failed)
            }
        }
    }
    fun onDismissOperationFailure() { operationFailure.value = null }

    fun onArchiveNote(origin: EditorSession? = editorSession) = changeStatus(origin) { archiveNote(it.noteId, !it.note.isArchived) }
    fun onTrashNote(origin: EditorSession? = editorSession) = changeStatus(origin, readOnly = true) { deleteNote(it.noteId) }
    fun onRestoreNote(origin: EditorSession? = editorSession) = changeStatus(origin) { restoreNote(it.noteId) }
    private fun changeStatus(origin: EditorSession?, readOnly: Boolean = false, action: suspend (EditorSession) -> Unit) {
        val session = origin ?: return
        if (session.closed || session !== editorSession) return
        ++navigationRevision
        retryNavigation = null
        if (readOnly) session.readOnly = true
        viewModelScope.launch {
            writeLock.withLock {
                if (readOnly) session.readOnly = true
                try {
                    flush(session)
                    action(session)
                    val note = observeNote(session.noteId).first() ?: return@withLock
                    session.note = note
                    session.readOnly = note.isTrashed
                    flush(session)
                    if (session === editorSession) selection.value = Selection(session, session.note)
                } catch (cancelled: CancellationException) { session.readOnly = session.note.isTrashed; throw cancelled }
                catch (_: Exception) {
                    session.readOnly = session.note.isTrashed
                    operationFailure.value = OperationFailure.Change
                }
            }
        }
    }

    fun onToggleInfo() { handle[KEY_INFO_OPEN] = !(handle.get<Boolean>(KEY_INFO_OPEN) ?: false) }
    fun onToggleTag(path: String) {
        val current = handle.get<ArrayList<String>>(KEY_EXPANDED).orEmpty()
        handle[KEY_EXPANDED] = ArrayList(if (path in current) current - path else current + path)
    }
    fun onToggleSection(key: String) = onToggleSection(editorSession, key)
    fun onToggleSection(session: EditorSession?, key: String) {
        if (session == null || session.closed) return
        val stateKey = foldedKey(session.noteId)
        handle.keys().toList().filter { it.startsWith(FOLD_PREFIX) && it != stateKey }.forEach { handle.remove<Any>(it) }
        val current = handle.get<ArrayList<String>>(stateKey).orEmpty().toSet()
        handle[stateKey] = ArrayList(if (key in current) current - key else current + key)
    }
    fun onPinTag(tagId: String, pinned: Boolean) = tagMetadata { pinTag(tagId, pinned) }
    fun onSetTagIcon(tagId: String, iconKey: String?) = tagMetadata { setTagIcon(tagId, iconKey) }
    private fun tagMetadata(action: suspend () -> Unit) {
        viewModelScope.launch {
            try { action() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operationFailure.value = OperationFailure.Change }
        }
    }
    private fun mutate(session: EditorSession? = editorSession, action: suspend () -> Unit) {
        val revision = navigationRevision
        if (session != null && session === editorSession) retryNavigation = null
        viewModelScope.launch {
            try {
                writeLock.withLock {
                    try { flush(session) } catch (failure: Exception) {
                        if (failure !is CancellationException) {
                            failedSession.value = session
                            if (session != null && session === editorSession && revision == navigationRevision) {
                                ++navigationRevision
                                retryNavigation = null
                            }
                        }
                        throw failure
                    }
                    if (session?.closed == true) return@withLock
                    action()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (failedSession.value == null) operationFailure.value = OperationFailure.Change }
        }
    }
    fun onRenameTag(from: String, to: String) = mutate {
        val snapshot = renameTag(from, to) ?: return@mutate
        (retired.toList() + listOfNotNull(editorSession)).forEach { rewriteBuffer(it) { text -> TagScanner.rename(text, from, to) } }
        followFilter(from) { path -> to + path.removePrefix(from) }
        offerUndo(snapshot, TagPrompt.Kind.Renamed, to)
    }
    fun onDeleteTag(path: String) = mutate {
        val snapshot = deleteTag(path)
        (retired.toList() + listOfNotNull(editorSession)).forEach { rewriteBuffer(it) { text -> TagScanner.remove(text, path) } }
        followFilter(path) { null }
        offerUndo(snapshot, TagPrompt.Kind.Deleted, path)
    }
    fun onAddTagToNote(noteId: String, name: String) {
        val origin = editorSession?.takeIf { it.noteId == noteId }
        if (origin != null && !origin.canEdit) return
        mutate(origin) {
            if (addTagToNote(noteId, name) == AddTagResult.NotWritable) {
                pendingUndo = null
                undo.value = TagPrompt(TagPrompt.Kind.AddFailed, name)
            } else if (origin != null) rewriteBuffer(origin) { AddTagToNote.append(it, name) }
        }
    }
    fun onImagePickerOpened(origin: EditorSession?): Boolean {
        if (pickerSession != null || origin == null || origin !== editorSession || !origin.canEdit) return false
        pickerSession = origin
        return true
    }
    fun onImagePickerResult(sourceUri: String?, mime: String) {
        val origin = pickerSession
        pickerSession = null
        if (sourceUri != null) onAttachImages(origin, listOf(sourceUri to mime))
    }
    fun onAttachImage(sourceUri: String, mime: String) = onAttachImages(listOf(sourceUri to mime))
    fun onAttachImages(images: List<Pair<String, String>>) = onAttachImages(editorSession, images)
    fun onAttachImages(origin: EditorSession?, images: List<Pair<String, String>>) {
        if (origin == null || !origin.canEdit || images.isEmpty()) return
        mutate(origin) {
            images.forEach { (uri, mime) ->
                var written = ""
                val attachment = attachImage(origin.noteId, uri, mime, body = {
                    origin.buffer.text.toString().also { written = it }
                }) ?: throw IllegalStateException("Image import failed")
                val current = origin.buffer.text.toString()
                origin.lastWritten = MarkdownFormat.appendImage(written, attachment.id)
                val next = MarkdownFormat.appendImage(current, attachment.id)
                origin.buffer.setTextAndPlaceCursorAtEnd(next)
                origin.buffer.undoState.clearHistory()
            }
        }
    }
    fun onDeleteAttachment(id: String) = onDeleteAttachment(editorSession, id)
    fun onDeleteAttachment(origin: EditorSession?, id: String) {
        if (origin == null || !origin.canEdit) return
        mutate(origin) {
            deleteAttachment(id, origin.noteId)
            rewriteBuffer(origin) { MarkdownScanner.removeImageRef(it, id) }
        }
    }
    fun tagsOfNote(noteId: String): List<String> = state.value.notes.find { it.id == noteId }?.let { parseTags(it.body) }.orEmpty()
    fun onUndo() {
        viewModelScope.launch {
            val pending = pendingUndo ?: return@launch
            pendingUndo = null
            undo.value = null
            try {
                writeLock.withLock {
                    val affected = (retired.toList() + listOfNotNull(editorSession))
                        .filter { it.noteId in pending.snapshot.bodies }
                    check(affected.all { it.buffer.text.toString() == pending.expectedBodies[it.noteId] }) {
                        "Note changed after the tag operation"
                    }
                    // Freeze affected inputs across the suspended transaction and adoption, not
                    // just during the preflight. Later keystrokes must not be overwritten either.
                    val readOnly = affected.associateWith { it.readOnly }
                    try {
                        affected.forEach { it.readOnly = true }
                        undoTagChange(pending.snapshot, pending.expectedBodies)
                        affected.forEach { session ->
                            observeNote(session.noteId).first()?.let { adopt(session, it.body) }
                        }
                    } finally {
                        readOnly.forEach { (session, previous) -> session.readOnly = previous }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { operationFailure.value = OperationFailure.Change }
        }
    }
    fun onUndoShown() { pendingUndo = null; undo.value = null }
    /** Called under writeLock immediately after the tag operation's committed body rewrite. */
    private suspend fun offerUndo(snapshot: BodySnapshot, kind: TagPrompt.Kind, name: String) {
        val expected = snapshot.bodies.keys.associateWith { id ->
            checkNotNull(observeNote(id).first()).body
        }
        pendingUndo = PendingUndo(snapshot, expected)
        undo.value = TagPrompt(kind, name, snapshot.noteCount)
    }
    private inline fun followFilter(path: String, next: (String) -> String?) {
        val current = (ShellFilter.fromKey(handle[KEY_FILTER]) as? ShellFilter.Tag)?.path ?: return
        if (current == path || current.startsWith("$path/")) handle[KEY_FILTER] = next(current)?.let { ShellFilter.Tag(it).key } ?: ShellFilter.All.key
    }
    private fun notesFor(filter: ShellFilter): Flow<List<Note>> = when (filter) {
        ShellFilter.All -> observeNotes().map { it.filterNot(Note::isArchived) }
        ShellFilter.Untagged -> observeNotes.untagged()
        ShellFilter.Todo -> observeNotes().map { it.filter { note -> MarkdownScanner.hasOpenTask(note.body) } }
        ShellFilter.Today -> startOfToday().let { midnight -> observeNotes().map { it.filter { note -> note.modifiedAt >= midnight } } }
        ShellFilter.Locked -> observeNotes().map { it.filter(Note::isLocked) }
        ShellFilter.Archive -> observeNotes().map { it.filter(Note::isArchived) }
        ShellFilter.Trash -> observeNotes.trashed()
        is ShellFilter.Tag -> observeNotes.byTagPath(filter.path)
    }
    private fun startOfToday(): Long = ZoneId.systemDefault().let { zone -> Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli() }
    private data class PendingUndo(val snapshot: BodySnapshot, val expectedBodies: Map<String, String>)
    private data class Selection(val session: EditorSession? = null, val note: Note? = null)
    private data class Owned<T>(val id: String?, val value: T)
    private data class EditorState(val filter: ShellFilter, val notes: List<Note>, val selection: Selection, val expanded: Set<String>, val collapsedSections: Set<String>)
    private data class TagState(val tree: List<TagNode>, val counts: Map<String, TagCount>, val undo: TagPrompt?)
    private data class LinkState(val backlinks: Owned<List<NoteLink>>, val infoOpen: Boolean, val titles: Owned<List<String>>)
    private data class OperationState(val failed: EditorSession?, val failure: OperationFailure?, val creating: Boolean, val search: SearchState)
    companion object {
        private const val KEY_FILTER = "shell.filter"
        private const val KEY_SELECTED = "shell.selectedNoteId"
        private const val KEY_EXPANDED = "shell.expandedTagPaths"
        private const val KEY_INFO_OPEN = "shell.infoOpen"
        private const val FOLD_PREFIX = "shell.foldedSections."
        private fun foldedKey(noteId: String) = "$FOLD_PREFIX$noteId"
        private const val STOP_TIMEOUT_MS = 5_000L
        const val IDLE_MS = 400L
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
@OptIn(ExperimentalCoroutinesApi::class)
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
