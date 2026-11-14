package app.duck.notes.presentation.transfer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.duck.notes.domain.model.PreparedDocument
import app.duck.notes.domain.repository.NoteTransferRepository
import app.duck.notes.presentation.shell.DuckShellViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TransferKind { Import, Export, Share }
enum class TransferStep { Preparing, Reading, Importing, Writing }
enum class TransferNotice { Cancelled, Written, Imported, PrepareFailed, ImportFailed, WriteFailed, Unavailable, Interrupted }
data class TransferRequest(val id: String, val kind: TransferKind, val document: PreparedDocument? = null,
    val uri: String? = null, val launched: Boolean = false, val returned: Boolean = false)
data class TransferUiState(val step: TransferStep? = null, val notice: TransferNotice? = null, val imported: Int = 0, val duplicates: Int = 0, val canRetry: Boolean = false, val cleanupFailed: Boolean = false)

/** Registering pickers is safe before unlock: the repository and Room remain behind Provider. */
@HiltViewModel
class TransferViewModel @Inject constructor(
    private val repository: Provider<NoteTransferRepository>,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val mutableRequest = MutableStateFlow(restoreRequest())
    val request = mutableRequest.asStateFlow()
    private val mutableUi = MutableStateFlow(TransferUiState(
        notice = if (handle.get<String>(WORK) != null) TransferNotice.Interrupted else handle.get<String>(NOTICE)?.let { name -> TransferNotice.entries.firstOrNull { it.name == name } },
        imported = handle[IMPORTED] ?: 0, duplicates = handle[DUPLICATES] ?: 0))
    val ui = mutableUi.asStateFlow()
    private var work: Job? = null
    private var retry: ExportChoice? = null
    init { if (handle.get<String>(WORK) != null) { handle[NOTICE] = TransferNotice.Interrupted.name; handle.remove<String>(WORK) } }

    fun importNotes() {
        if (busy()) return
        dismissNotice()
        setRequest(TransferRequest(UUID.randomUUID().toString(), TransferKind.Import))
    }

    fun export(shell: DuckShellViewModel, noteId: String?, share: Boolean) {
        if (busy()) return
        retry = ExportChoice(noteId, share)
        run(TransferStep.Preparing, TransferNotice.PrepareFailed) {
            val port = repository.get()
            val document = shell.withNotesLocked {
                port.prepareExport(noteId?.let(::listOf) ?: shell.currentCollectionIdsForTransfer())
            }
            var retained = false
            try {
                if (share) {
                    val uri = port.sharePrepared(document)
                    setRequest(TransferRequest(UUID.randomUUID().toString(), TransferKind.Share, document, uri))
                } else {
                    setRequest(TransferRequest(UUID.randomUUID().toString(), TransferKind.Export, document))
                    retained = true
                }
            } finally { if (!retained) cleanup(port, document.id) }
        }
    }

    fun retryExport(shell: DuckShellViewModel) { retry?.let { export(shell, it.noteId, it.share) } }
    fun markLaunched(id: String) { request.value?.takeIf { it.id == id }?.let { setRequest(it.copy(launched = true)) } }
    fun shareLaunched(id: String) { if (request.value?.id == id) setRequest(null) }

    /** Callbacks queue a URI only. Reading, writing and passphrase prompts require the unlocked UI. */
    fun pickerReturned(kind: TransferKind, uri: String?) {
        val pending = request.value
        if (pending == null) { notice(TransferNotice.Interrupted); return }
        if (pending.kind != kind || !pending.launched || pending.returned) return
        setRequest(pending.copy(uri = uri, returned = true))
    }

    fun launchFailed(id: String) {
        val pending = request.value?.takeIf { it.id == id } ?: return
        setRequest(null)
        run(TransferStep.Preparing, TransferNotice.Unavailable) {
            pending.document?.takeIf { pending.kind == TransferKind.Export }?.let { cleanup(repository.get(), it.id) }
            notice(TransferNotice.Unavailable)
        }
    }

    fun processReturned(shell: DuckShellViewModel) {
        val pending = request.value?.takeIf { it.returned } ?: return
        if (work?.isActive == true) return
        setRequest(null)
        val writing = pending.kind == TransferKind.Export
        run(if (writing) TransferStep.Writing else TransferStep.Reading, if (writing) TransferNotice.WriteFailed else TransferNotice.ImportFailed) {
            if (pending.uri == null) {
                pending.document?.let { cleanup(repository.get(), it.id) }
                notice(TransferNotice.Cancelled)
            } else if (writing) {
                val port = repository.get()
                val document = checkNotNull(pending.document)
                try { port.writePrepared(document.id, pending.uri); notice(TransferNotice.Written) }
                finally { cleanup(port, document.id) }
            } else {
                val port = repository.get()
                val id = port.prepareImport(pending.uri)
                try {
                    mutableUi.value = mutableUi.value.copy(step = TransferStep.Importing)
                    handle[WORK] = TransferStep.Importing.name
                    val result = shell.withNotesLocked { port.importPrepared(id) }
                    handle[IMPORTED] = result.addedNoteIds.size
                    handle[DUPLICATES] = result.duplicateTitles
                    mutableUi.value = mutableUi.value.copy(imported = result.addedNoteIds.size, duplicates = result.duplicateTitles)
                    notice(TransferNotice.Imported)
                } finally { cleanup(port, id) }
            }
        }
    }

    fun cancel() { if (ui.value.step != TransferStep.Importing) work?.cancel() }
    fun dismissNotice() { handle.remove<String>(NOTICE); mutableUi.value = mutableUi.value.copy(notice = null, canRetry = false, cleanupFailed = false) }
    private suspend fun cleanup(port: NoteTransferRepository, id: String) = withContext(NonCancellable) {
        try { port.discardPrepared(id) }
        catch (_: Exception) {
            android.util.Log.e("DuckStorage", "Transfer cleanup unavailable")
            mutableUi.value = mutableUi.value.copy(cleanupFailed = true)
        }
    }
    private fun busy() = work?.isActive == true || request.value != null
    private fun notice(value: TransferNotice) {
        handle[NOTICE] = value.name
        mutableUi.value = mutableUi.value.copy(notice = value, canRetry = value == TransferNotice.PrepareFailed && retry != null)
    }
    private fun run(step: TransferStep, failed: TransferNotice, action: suspend () -> Unit) {
        if (work?.isActive == true) return
        dismissNotice()
        work = viewModelScope.launch {
            mutableUi.value = mutableUi.value.copy(step = step)
            handle[WORK] = step.name
            try { action() }
            catch (cancelled: CancellationException) {
                notice(when (mutableUi.value.step) { TransferStep.Writing -> TransferNotice.WriteFailed; TransferStep.Importing -> TransferNotice.Interrupted; else -> TransferNotice.Cancelled })
                throw cancelled
            }
            catch (_: Exception) { notice(failed) }
            finally { mutableUi.value = mutableUi.value.copy(step = null); handle.remove<String>(WORK) }
        }
    }
    private fun setRequest(value: TransferRequest?) {
        mutableRequest.value = value
        handle[REQUEST] = value?.let { arrayListOf(it.id, it.kind.name, it.document?.id.orEmpty(), it.document?.displayName.orEmpty(), it.document?.mime.orEmpty(), it.document?.bytes?.toString().orEmpty(), it.uri.orEmpty(), it.launched.toString(), it.returned.toString()) }
    }
    private fun restoreRequest(): TransferRequest? = runCatching {
        val values = handle.get<ArrayList<String>>(REQUEST) ?: return null
        if (values.size != 9) return null
        val kind = TransferKind.entries.firstOrNull { it.name == values[1] } ?: return null
        val document = if (kind == TransferKind.Import) null else PreparedDocument(values[2], values[3], values[4], values[5].toLong())
        TransferRequest(values[0], kind, document, values[6].ifEmpty { null }, values[7].toBooleanStrict(), values[8].toBooleanStrict())
    }.getOrNull()
    private data class ExportChoice(val noteId: String?, val share: Boolean)
    private companion object {
        const val REQUEST = "transfer.request"
        const val NOTICE = "transfer.notice"
        const val WORK = "transfer.work"
        const val IMPORTED = "transfer.imported"
        const val DUPLICATES = "transfer.duplicates"
    }
}
