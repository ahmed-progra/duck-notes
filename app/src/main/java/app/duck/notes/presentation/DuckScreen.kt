package app.duck.notes.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.duck.notes.presentation.shell.DuckShell

/**
 * The app's single entry composable. Phase 6 replaced the Phase 4 editor prototype here with the
 * real navigation shell; `DuckShell` resolves its own `DuckShellViewModel` through Hilt, which
 * `MainActivity` can supply because it is already `@AndroidEntryPoint`.
 *
 * No `@Preview`: the shell needs a ViewModel, and a preview cannot build one. The debug gallery's
 * Shell page is the harness that replaces it, driven by a fixture so it works on an empty database.
 */
@Composable
fun DuckScreen(modifier: Modifier = Modifier) {
    DuckShell(modifier = modifier.fillMaxSize())
}
