package app.duck.notes.presentation.settings

import androidx.lifecycle.ViewModel
import app.duck.notes.domain.model.SettingsNotice
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.SettingsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun mayShowNotes(snapshot: SettingsSnapshot, authenticated: Boolean): Boolean =
    authenticated || (snapshot.readStatus == SettingsReadStatus.Ready &&
        snapshot.notice != SettingsNotice.ResetAfterCorruption && !snapshot.settings.lockEnabled)

data class AppLockState(val authenticated: Boolean = false, val authenticating: Boolean = false)

class AppLockSession : ViewModel() {
    private val mutableState = MutableStateFlow(AppLockState())
    val state = mutableState.asStateFlow()
    private var requestedLock: Boolean? = null

    fun begin(changeLock: Boolean? = null): Boolean {
        if (mutableState.value.authenticating) return false
        requestedLock = changeLock
        mutableState.value = AppLockState(authenticating = true)
        return true
    }

    fun finish(success: Boolean): Boolean? {
        if (!mutableState.value.authenticating) return null
        val change = requestedLock.takeIf { success }
        requestedLock = null
        mutableState.value = AppLockState(authenticated = success)
        return change
    }

    fun background(changingConfigurations: Boolean) {
        if (!changingConfigurations && !mutableState.value.authenticating) {
            mutableState.value = AppLockState()
        }
    }
}
