package app.duck.notes.presentation.shell

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.duck.notes.domain.model.Note
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

/** Identity and text travel together, including while an outgoing editor is still composed. */
@Stable
class EditorSession internal constructor(note: Note) {
    val noteId = note.id
    val buffer = TextFieldState(note.body)
    internal var lastWritten = note.body
    internal var oweFullWrite = false
    internal var note = note
    internal var editors = 0
    internal var retired = false
    internal var missing = false
    internal var detached = CompletableDeferred<Unit>()
    internal var autosave: Job? = null
    internal var closed by mutableStateOf(false)
    internal var readOnly by mutableStateOf(note.isTrashed)
    val canEdit: Boolean get() = !closed && !readOnly
}
