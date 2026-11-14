package app.duck.notes.presentation.transfer

import androidx.lifecycle.SavedStateHandle
import app.duck.notes.domain.repository.NoteTransferRepository
import javax.inject.Provider
import org.junit.Assert.*
import org.junit.Test

class TransferViewModelTest {
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
