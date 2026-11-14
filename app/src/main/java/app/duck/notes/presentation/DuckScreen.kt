package app.duck.notes.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.SettingsNotice
import app.duck.notes.presentation.settings.SettingsScreen
import app.duck.notes.presentation.settings.SettingsViewModel
import app.duck.notes.presentation.shell.DuckShell
import app.duck.notes.presentation.shell.DuckShellViewModel

/** Retains the real editor owner and registered scroll state while Settings occupies the screen. */
@Composable
fun DuckScreen(
    canAuthenticate: Boolean,
    onLockChange: (Boolean) -> Unit,
    canUseTransfers: () -> Boolean,
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    shellViewModel: DuckShellViewModel = hiltViewModel(),
    transferViewModel: app.duck.notes.presentation.transfer.TransferViewModel = hiltViewModel(),
) {
    val ui by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val settingsOpen by settingsViewModel.settingsOpen.collectAsStateWithLifecycle()
    val pendingTransfer by transferViewModel.request.collectAsStateWithLifecycle()
    val transferUi by transferViewModel.ui.collectAsStateWithLifecycle()
    val lifecycle by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(pendingTransfer, transferUi.step, lifecycle) {
        if (canUseTransfers()) transferViewModel.processReturned(shellViewModel)
    }
    val savedViews = rememberSaveableStateHolder()
    BackHandler(enabled = settingsOpen) { settingsViewModel.closeSettings() }
    val notice = when {
        ui.writeFailed -> stringResource(R.string.settings_write_failed)
        ui.snapshot.notice == SettingsNotice.ResetAfterCorruption -> stringResource(R.string.settings_reset)
        ui.snapshot.notice == SettingsNotice.TemporaryReadFailure -> stringResource(R.string.settings_read_failed)
        ui.startupTimedOut -> stringResource(R.string.settings_startup_timeout)
        else -> null
    }
    Column(modifier.fillMaxSize().background(DuckTheme.colors.surface)) {
        if (!settingsOpen && notice != null) {
            Column(Modifier.safeDrawingPadding().padding(DuckTheme.spacing.lg)) {
                Text(notice, color = DuckTheme.colors.error, style = DuckTheme.type.uiBody,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = settingsViewModel::retry, enabled = !ui.pendingWrite) {
                    Text(stringResource(R.string.settings_retry))
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (settingsOpen) {
                savedViews.SaveableStateProvider("settings") {
                    SettingsScreen(ui.snapshot.settings, ui.pendingWrite, notice,
                        onBack = settingsViewModel::closeSettings,
                        onTheme = settingsViewModel::setTheme,
                        onLanguage = settingsViewModel::setLanguage,
                        onEditorFont = settingsViewModel::setEditorFont,
                        onEditorSize = settingsViewModel::setEditorSize,
                        onRetry = settingsViewModel::retry,
                        canAuthenticate = canAuthenticate, onLockChange = onLockChange,
                        onBackup = { if (canUseTransfers()) transferViewModel.requestBackup() },
                        onRestore = { if (canUseTransfers()) transferViewModel.requestRestore() })
                }
            } else {
                savedViews.SaveableStateProvider("shell") {
                    DuckShell(viewModel = shellViewModel, onSettings = settingsViewModel::openSettings,
                        onImport = { if (canUseTransfers()) transferViewModel.importNotes() },
                        onExport = { id, share -> if (canUseTransfers()) transferViewModel.export(shellViewModel, id, share) },
                        noteSort = ui.snapshot.settings.noteSort, onNoteSort = settingsViewModel::setNoteSort, noteSortPending = ui.pendingWrite)
                }
            }
        }
    }
}
