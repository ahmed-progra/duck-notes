package app.duck.notes.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme

@Composable
fun AppLockScreen(
    canAuthenticate: Boolean,
    authenticating: Boolean,
    onUnlock: () -> Unit,
    onRetry: () -> Unit,
    onConfigureLock: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(DuckTheme.colors.surface).safeDrawingPadding()
        .padding(DuckTheme.spacing.lg),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.app_name), style = DuckTheme.type.uiTitle, color = DuckTheme.colors.onSurface)
        Text(stringResource(if (canAuthenticate) R.string.lock_message else R.string.lock_setup_required),
            style = DuckTheme.type.uiBody, color = DuckTheme.colors.proseMuted)
        TextButton(onClick = if (canAuthenticate) onUnlock else onConfigureLock, enabled = !authenticating) {
            Text(stringResource(if (canAuthenticate) R.string.lock_unlock else R.string.lock_setup))
        }
        TextButton(onClick = onRetry, enabled = !authenticating) { Text(stringResource(R.string.settings_retry)) }
    }
}
