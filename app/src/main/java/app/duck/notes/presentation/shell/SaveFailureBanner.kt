package app.duck.notes.presentation.shell

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme

@Composable
internal fun SaveFailureBanner(noteTitle: String?, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Snackbar(modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        containerColor = DuckTheme.colors.surfaceElevated, contentColor = DuckTheme.colors.onSurface,
        action = { TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.editor_save_retry), color = DuckTheme.colors.accentText)
        } }) {
        Text(noteTitle?.let { stringResource(R.string.editor_save_failed_note, it) } ?: stringResource(R.string.editor_save_failed))
    }
}
