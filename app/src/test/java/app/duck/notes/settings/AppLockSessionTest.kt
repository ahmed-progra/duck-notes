package app.duck.notes.settings

import app.duck.notes.domain.model.AppSettings
import app.duck.notes.domain.model.SettingsNotice
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.SettingsSnapshot
import app.duck.notes.presentation.settings.AppLockSession
import app.duck.notes.presentation.settings.mayShowNotes
import org.junit.Assert.*
import org.junit.Test

/** Plants LOCK_FAIL_OPEN and LOCK_BACKGROUND: invert the gate or remove background invalidation. */
class AppLockSessionTest {
    @Test fun onlyKnownDisabledLockOrAuthenticatedSessionCanShowNotes() {
        val ready = SettingsSnapshot(AppSettings(), SettingsReadStatus.Ready)
        assertTrue(mayShowNotes(ready, false))
        assertFalse(mayShowNotes(ready.copy(settings = AppSettings(lockEnabled = true)), false))
        assertFalse(mayShowNotes(SettingsSnapshot(), false))
        assertFalse(mayShowNotes(ready.copy(readStatus = SettingsReadStatus.TemporaryDefaults), false))
        assertFalse(mayShowNotes(ready.copy(notice = SettingsNotice.ResetAfterCorruption), false))
        assertTrue(mayShowNotes(ready.copy(settings = AppSettings(lockEnabled = true)), true))
    }

    @Test fun successIsRequiredAndCancelCannotChangeLockPreference() {
        val session = AppLockSession()
        assertFalse(session.state.value.authenticated)
        assertNull(session.finish(true))
        assertFalse(session.state.value.authenticated)
        assertTrue(session.begin(true))
        assertFalse(session.begin(false))
        assertNull(session.finish(false))
        assertFalse(session.state.value.authenticated)
        assertTrue(session.begin(true))
        assertEquals(true, session.finish(true))
        assertTrue(session.state.value.authenticated)
        assertTrue(session.begin(false))
        assertFalse(session.state.value.authenticated)
        assertEquals(false, session.finish(true))
    }

    @Test fun backgroundRelocksButRotationAndCredentialUiDoNotDiscardThePendingRequest() {
        val session = AppLockSession()
        session.begin()
        session.finish(true)
        session.background(changingConfigurations = true)
        assertTrue(session.state.value.authenticated)
        session.background(changingConfigurations = false)
        assertFalse(session.state.value.authenticated)
        session.begin(true)
        session.background(changingConfigurations = false)
        assertTrue(session.state.value.authenticating)
        assertEquals(true, session.finish(true))
        assertTrue(session.state.value.authenticated)
        assertFalse(AppLockSession().state.value.authenticated)
    }
}
