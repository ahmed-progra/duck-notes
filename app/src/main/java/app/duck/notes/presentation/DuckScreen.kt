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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    shellViewModel: DuckShellViewModel = hiltViewModel(),
) {
    val ui by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val settingsOpen by settingsViewModel.settingsOpen.collectAsStateWithLifecycle()
    val savedViews = rememberSaveableStateHolder()
    // Saving belongs to the retained owner, including while its editor is absent from composition.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { shellViewModel.onStop() }
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
                        onRetry = settingsViewModel::retry)
                }
            } else {
                savedViews.SaveableStateProvider("shell") {
                    DuckShell(viewModel = shellViewModel, onSettings = settingsViewModel::openSettings)
                }
            }
        }
    }
}