package app.duck.notes.presentation.transfer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.text.input.TextFieldState
import app.duck.notes.domain.model.PreparedDocument
import app.duck.notes.domain.repository.NoteTransferRepository
import app.duck.notes.presentation.shell.DuckShellViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TransferKind { Import, Export, Share, Backup, Restore }
enum class TransferStep { Preparing, Reading, Importing, Writing, Unlocking, Restoring }
enum class TransferNotice { Cancelled, Written, Imported, PrepareFailed, ImportFailed, WriteFailed, Unavailable, Interrupted, BackupWritten, Restored, RestoreFailed }
enum class BackupPrompt { Create, Restore }
enum class BackupPasswordError { TooShort, TooLong, Mismatch, UnlockFailed }
data class TransferRequest(val id: String, val kind: TransferKind, val document: PreparedDocument? = null,
    val uri: String? = null, val launched: Boolean = false, val returned: Boolean = false)
data class TransferUiState(val step: TransferStep? = null, val notice: TransferNotice? = null, val imported: Int = 0, val duplicates: Int = 0, val canRetry: Boolean = false, val cleanupFailed: Boolean = false,
    val prompt: BackupPrompt? = null, val passwordError: BackupPasswordError? = null, val skipped: Int = 0)

/** Registering pickers is safe before unlock: the repository and Room remain behind Provider. */
@HiltViewModel
class TransferViewModel @Inject constructor(
    private val repository: Provider<NoteTransferRepository>,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val portOwner = lazy { repository.get() }
    private val transferPort by portOwner
    private val mutableRequest = MutableStateFlow(restoreRequest())
    val request = mutableRequest.asStateFlow()
    private val mutableUi = MutableStateFlow(TransferUiState(
        notice = if (handle.get<String>(WORK) != null) TransferNotice.Interrupted else handle.get<String>(NOTICE)?.let { name -> TransferNotice.entries.firstOrNull { it.name == name } },
        imported = handle[IMPORTED] ?: 0, duplicates = handle[DUPLICATES] ?: 0, skipped = handle[SKIPPED] ?: 0))
    val ui = mutableUi.asStateFlow()
    private var work: Job? = null
    private var retry: ExportChoice? = null
    private var retryBackup = false
    private var restoreInput: String? = null
    private var secretWork = false
    // These states belong to the ViewModel, never rememberSaveable or SavedStateHandle.
    val passphrase = TextFieldState()
    val confirmation = TextFieldState()
    init { if (handle.get<String>(WORK) != null) { handle[NOTICE] = TransferNotice.Interrupted.name; handle.remove<String>(WORK) } }

    fun importNotes() {
        if (busy()) return
        dismissNotice()
        setRequest(TransferRequest(UUID.randomUUID().toString(), TransferKind.Import))
    }

    fun export(shell: DuckShellViewModel, noteId: String?, share: Boolean) {
        if (busy()) return
        retryBackup = false
        retry = ExportChoice(noteId, share)
        run(TransferStep.Preparing, TransferNotice.PrepareFailed) {
            val port = transferPort
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

    fun retryExport(shell: DuckShellViewModel) {
        if (retryBackup) requestBackup() else retry?.let { export(shell, it.noteId, it.share) }
    }

    fun requestBackup() {
        if (busy()) return
        dismissNotice()
        clearSecrets()
        retryBackup = true
        mutableUi.value = mutableUi.value.copy(prompt = BackupPrompt.Create)
    }

    fun requestRestore() {
        if (busy()) return
        dismissNotice()
        clearSecrets()
        setRequest(TransferRequest(UUID.randomUUID().toString(), TransferKind.Restore))
    }

    fun submitPassphrase(shell: DuckShellViewModel) {
        val prompt = ui.value.prompt ?: return
        if (work?.isActive == true) return
        val length = passphrase.text.length
        val error = when {
            length < if (prompt == BackupPrompt.Create) 12 else 1 -> BackupPasswordError.TooShort
            length > 1024 -> BackupPasswordError.TooLong
            prompt == BackupPrompt.Create && (length != confirmation.text.length || (0 until length).any { passphrase.text[it] != confirmation.text[it] }) -> BackupPasswordError.Mismatch
            else -> null
        }
        if (error != null) { mutableUi.value = mutableUi.value.copy(passwordError = error); return }
        val password = CharArray(length) { passphrase.text[it] }
        clearSecrets()
        mutableUi.value = mutableUi.value.copy(prompt = null, passwordError = null)
        secretWork = true
        if (prompt == BackupPrompt.Create) {
            run(TransferStep.Preparing, TransferNotice.PrepareFailed) {
                try {
                    val document = shell.withNotesLocked { transferPort.prepareBackup(password) }
                    setRequest(TransferRequest(UUID.randomUUID().toString(), TransferKind.Backup, document))
                } finally { password.fill('\u0000'); secretWork = false }
            }
        } else {
            val id = restoreInput
            if (id == null) { password.fill('\u0000'); secretWork = false; notice(TransferNotice.Interrupted); return }
            run(TransferStep.Unlocking, TransferNotice.RestoreFailed) {
                var port: NoteTransferRepository? = null
                var retainEncryptedInput = false
                var authenticated = false
                try {
                    val current = transferPort.also { port = it }
                    current.authenticateRestore(id, password)
                    authenticated = true
                    secretWork = false
                    mutableUi.value = mutableUi.value.copy(step = TransferStep.Restoring)
                    handle[WORK] = TransferStep.Restoring.name
                    val result = shell.withNotesLocked { current.restorePrepared(id) }
                    handle[IMPORTED] = result.addedNoteIds.size
                    handle[SKIPPED] = result.skipped
                    mutableUi.value = mutableUi.value.copy(imported = result.addedNoteIds.size, skipped = result.skipped)
                    notice(TransferNotice.Restored)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (authenticated) throw failure
                    retainEncryptedInput = true
                    mutableUi.value = mutableUi.value.copy(prompt = BackupPrompt.Restore, passwordError = BackupPasswordError.UnlockFailed)
                } finally {
                    password.fill('\u0000')
                    secretWork = false
                    if (!retainEncryptedInput) { restoreInput = null; port?.let { cleanup(it, id) } }
                }
            }
        }
    }

    fun cancelPassphrase() {
        clearSecrets()
        mutableUi.value = mutableUi.value.copy(prompt = null, passwordError = null)
        val id = restoreInput ?: return
        restoreInput = null
        run(TransferStep.Preparing, TransferNotice.Interrupted) { cleanup(transferPort, id); notice(TransferNotice.Cancelled) }
    }

    fun background() {
        clearSecrets()
        mutableUi.value = mutableUi.value.copy(passwordError = null)
        if (secretWork) work?.cancel()
    }

    private fun clearSecrets() {
        for (field in listOf(passphrase, confirmation)) {
            field.edit { replace(0, length, "") }
            field.undoState.clearHistory()
        }
    }
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
            pending.document?.takeIf { pending.kind in setOf(TransferKind.Export, TransferKind.Backup) }?.let { cleanup(transferPort, it.id) }
            notice(TransferNotice.Unavailable)
        }
    }

    fun processReturned(shell: DuckShellViewModel) {
        val pending = request.value?.takeIf { it.returned } ?: return
        if (work?.isActive == true) return
        setRequest(null)
        val writing = pending.kind in setOf(TransferKind.Export, TransferKind.Backup)
        run(if (writing) TransferStep.Writing else TransferStep.Reading, if (writing) TransferNotice.WriteFailed else if (pending.kind == TransferKind.Restore) TransferNotice.RestoreFailed else TransferNotice.ImportFailed) {
            if (pending.uri == null) {
                pending.document?.let { cleanup(transferPort, it.id) }
                notice(TransferNotice.Cancelled)
            } else if (writing) {
                val port = transferPort
                val document = checkNotNull(pending.document)
                try { port.writePrepared(document.id, pending.uri); notice(if (pending.kind == TransferKind.Backup) TransferNotice.BackupWritten else TransferNotice.Written) }
                finally { cleanup(port, document.id) }
            } else if (pending.kind == TransferKind.Restore) {
                restoreInput = transferPort.prepareRestore(pending.uri)
                clearSecrets()
                mutableUi.value = mutableUi.value.copy(prompt = BackupPrompt.Restore, passwordError = null)
            } else {
                val port = transferPort
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

    fun cancel() { if (ui.value.step !in setOf(TransferStep.Importing, TransferStep.Restoring)) work?.cancel() }
    fun dismissNotice() { handle.remove<String>(NOTICE); mutableUi.value = mutableUi.value.copy(notice = null, canRetry = false, cleanupFailed = false) }
    override fun onCleared() {
        clearSecrets()
        if (portOwner.isInitialized()) {
            val ids = buildSet {
                restoreInput?.let(::add)
                request.value?.takeIf { it.kind in setOf(TransferKind.Export, TransferKind.Backup) }?.document?.id?.let(::add)
            }
            val pendingWork = work
            val port = transferPort
            // Finish owned-file cleanup after the ViewModel scope has been cancelled.
            if (ids.isNotEmpty()) CoroutineScope(Dispatchers.IO).launch {
                pendingWork?.join()
                ids.forEach { cleanup(port, it) }
            }
        }
        super.onCleared()
    }
    private suspend fun cleanup(port: NoteTransferRepository, id: String) = withContext(NonCancellable) {
        try { port.discardPrepared(id) }
        catch (_: Exception) {
            android.util.Log.e("DuckStorage", "Transfer cleanup unavailable")
            mutableUi.value = mutableUi.value.copy(cleanupFailed = true)
        }
    }
    private fun busy() = work?.isActive == true || request.value != null || ui.value.prompt != null || restoreInput != null
    private fun notice(value: TransferNotice) {
        handle[NOTICE] = value.name
        mutableUi.value = mutableUi.value.copy(notice = value, canRetry = value == TransferNotice.PrepareFailed && (retryBackup || retry != null))
    }
    private fun run(step: TransferStep, failed: TransferNotice, action: suspend () -> Unit) {
        if (work?.isActive == true) return
        dismissNotice()
        work = viewModelScope.launch {
            mutableUi.value = mutableUi.value.copy(step = step)
            handle[WORK] = step.name
            try { action() }
            catch (cancelled: CancellationException) {
                notice(when (mutableUi.value.step) { TransferStep.Writing -> TransferNotice.WriteFailed; TransferStep.Importing, TransferStep.Restoring -> TransferNotice.Interrupted; else -> TransferNotice.Cancelled })
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
        val document = if (kind in setOf(TransferKind.Import, TransferKind.Restore)) null else PreparedDocument(values[2], values[3], values[4], values[5].toLong())
        TransferRequest(values[0], kind, document, values[6].ifEmpty { null }, values[7].toBooleanStrict(), values[8].toBooleanStrict())
    }.getOrNull()
    private data class ExportChoice(val noteId: String?, val share: Boolean)
    private companion object {
        const val REQUEST = "transfer.request"
        const val NOTICE = "transfer.notice"
        const val WORK = "transfer.work"
        const val IMPORTED = "transfer.imported"
        const val DUPLICATES = "transfer.duplicates"
        const val SKIPPED = "transfer.skipped"
    }
}
