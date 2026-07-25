package app.duck.notes.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.presentation.markdown.MarkdownEditorScreen

/**
 * The app's single screen. Phase 4 points it at the markdown editor prototype; Phase 6 replaces this
 * with the real three-pane navigation shell.
 */
@Composable
fun DuckScreen(modifier: Modifier = Modifier) {
    MarkdownEditorScreen(modifier = modifier.fillMaxSize())
}

@Preview(showBackground = true)
@Composable
private fun DuckScreenPreview() {
    DuckTheme {
        DuckScreen()
    }
}
