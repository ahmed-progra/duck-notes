package app.duck.notes.presentation.transfer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.duck.notes.domain.repository.NoteTransferRepository
import javax.inject.Provider
import org.junit.Assert.*
import org.junit.Test

class TransferViewModelTest {
    /** Plant: force lazy repository initialization during clearing of an unopened saved request. */
    @Test fun clearingAnUnopenedSavedBackupRequestCannotInitializeTheRepository() {
        var calls = 0
        val handle = SavedStateHandle(mapOf("transfer.request" to arrayListOf("pending", "Backup", "expired", "Duck-Notes.duckbackup", "application/octet-stream", "10", "", "true", "false")))
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = requireNotNull(modelClass.cast(
                TransferViewModel(Provider { calls++; error("Clearing a locked request opened the repository") }, handle)))
        })
        assertEquals(TransferKind.Backup, provider[TransferViewModel::class.java].request.value?.kind)
        store.clear()
        assertEquals(0, calls)
    }

    @Test fun backupPickerCallbacksRemainQueuedAcrossRecreationWithoutOpeningTheRepository() {
        var calls = 0
        val provider = Provider<NoteTransferRepository> { calls++; error("Locked backup callback started work") }
        val handle = SavedStateHandle()
        val model = TransferViewModel(provider, handle)
        model.requestRestore()
        val request = requireNotNull(model.request.value)
        assertEquals(TransferKind.Restore, request.kind)
        model.markLaunched(request.id)
        model.pickerReturned(TransferKind.Import, "content://fixture/wrong-kind")
        assertFalse(model.request.value?.returned == true)
        model.pickerReturned(TransferKind.Restore, "content://fixture/encrypted")
        val restored = TransferViewModel(provider, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals(TransferKind.Restore, restored.request.value?.kind)
        assertEquals("content://fixture/encrypted", restored.request.value?.uri)
        assertTrue(restored.request.value?.returned == true)
        assertEquals(0, calls)
    }

    /** Plant: retain passphrase state or undo on background, or place it in SavedStateHandle. */
    @Test fun backgroundClearsPasswordTextAndHistoryWithoutPersistingSecrets() {
        val handle = SavedStateHandle(mapOf("positiveControl" to "retained synthetic state"))
        val model = TransferViewModel(Provider { error("Password prompt accessed notes") }, handle)
        model.requestBackup()
        val secret = "synthetic test-only passphrase"
        model.passphrase.edit { append(secret) }
        model.confirmation.edit { append(secret) }
        model.background()
        val saved = handle.keys().associateWith { handle.get<Any>(it) }
        assertEquals("retained synthetic state", saved["positiveControl"])
        assertFalse("Passphrase persisted in saved state", saved.values.any { it.toString().contains(secret) })
        assertEquals("", model.passphrase.text.toString())
        assertEquals("", model.confirmation.text.toString())
        assertFalse(model.passphrase.undoState.canUndo)
        assertFalse(model.confirmation.undoState.canUndo)
        val recreated = TransferViewModel(Provider { error("Recreation accessed notes") }, SavedStateHandle(saved))
        assertEquals("", recreated.passphrase.text.toString())
        assertNull(recreated.ui.value.prompt)
    }

    /** Queue/persistence coverage; real pre-unlock database access is checked by native startup tests. */
    @Test fun returnedPickerUriSurvivesRecreationWithoutStartingRepositoryWork() {
        var calls = 0
        val provider = Provider<NoteTransferRepository> { calls++; error("Picker callback started work") }
        val handle = SavedStateHandle()
        val model = TransferViewModel(provider, handle)
        model.importNotes()
        val pending = requireNotNull(model.request.value)
        model.markLaunched(pending.id)
        model.pickerReturned(TransferKind.Import, "content://fixture/first")
        val restored = TransferViewModel(provider, SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        assertEquals("content://fixture/first", restored.request.value?.uri)
        assertEquals(TransferKind.Import, restored.request.value?.kind)
        assertTrue(restored.request.value?.returned == true)
        assertEquals(0, calls)
    }

    /** Plants: drop kind/launched/returned checks and stale callbacks replace the queued selection. */
    @Test fun onlyTheLaunchedMatchingPickerCanQueueOneResult() {
        val model = TransferViewModel(Provider { error("Callback touched notes") }, SavedStateHandle())
        model.importNotes()
        val id = requireNotNull(model.request.value).id
        model.importNotes()
        assertEquals(id, model.request.value?.id)
        model.pickerReturned(TransferKind.Import, "content://fixture/too-early")
        assertFalse(model.request.value?.returned == true)
        model.markLaunched(id)
        model.pickerReturned(TransferKind.Export, "content://fixture/wrong-picker")
        assertFalse(model.request.value?.returned == true)
        model.pickerReturned(TransferKind.Import, "content://fixture/accepted")
        model.pickerReturned(TransferKind.Import, null)
        assertEquals("content://fixture/accepted", model.request.value?.uri)
        assertTrue(model.request.value?.returned == true)
    }

    @Test fun malformedSavedRequestExpiresAndAnUnmatchedCallbackReportsInterruption() {
        val handle = SavedStateHandle(mapOf("transfer.request" to arrayListOf("invalid")))
        val model = TransferViewModel(Provider { error("Expired callback touched notes") }, handle)
        assertNull(model.request.value)
        model.pickerReturned(TransferKind.Import, "content://fixture/expired")
        assertEquals(TransferNotice.Interrupted, model.ui.value.notice)
    }
}
