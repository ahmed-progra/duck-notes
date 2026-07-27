package app.duck.notes.presentation.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.duck.notes.core.markdown.MarkdownOutputTransformation
import app.duck.notes.core.markdown.MarkdownStyles
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.markdown.MarkdownTextCache

/**
 * Phase 4's prototype editor — the markdown engine, on screen, and nothing else.
 *
 * No autosave, no toolbar, no repository, no note id. The build spec asks for the editor to be
 * prototyped alone before anything is wired to it, and Phase 8 is where this becomes the real
 * screen. State lives in memory and dies with the composable, on purpose.
 *
 * **Phase 6 gave it [text] and deliberately not a ViewModel.** The shell hands it the selected
 * note's body; autosave, the note id and the save path are Phase 8's Build list, and giving the
 * prototype its own ViewModel here would pull that work forward. The consequence is that edits die
 * on a rotation while the *selection* survives — `DuckShellViewModel` holds that, not this.
 *
 * The buffer is `rememberSaveable` **keyed on [text]**, which is `rememberTextFieldState` expanded by
 * hand — that function is exactly `rememberSaveable(saver = TextFieldState.Saver)` with no key. Two
 * properties are needed here and neither can be dropped:
 *
 * - **Keyed**, so selecting a different note replaces the buffer. Without the key the new argument is
 *   ignored and the failure looks like "tapping a note does nothing".
 * - **Saveable**, so a rotation does not discard what was typed. Plain `remember(text)` gets the
 *   keying and silently loses this — a regression against Phase 4's prototype, which had the saver.
 *
 * Known ceiling: `rememberSaveable` keys on composition position, and the editor moves between
 * `Scaffold` content on Compact and a `Row` child on Expanded, so **crossing 600dp still drops the
 * buffer**. That is the same trap `DuckShellViewModel` documents, and the reason shell state is in
 * `SavedStateHandle` instead. The editor cannot follow it there until Phase 8 gives it a ViewModel
 * and a save path; until then a resize losing unsaved text is the honest limit.
 */
@Composable
fun MarkdownEditorScreen(text: String, modifier: Modifier = Modifier) {
    val state = rememberSaveable(text, saver = TextFieldState.Saver) { TextFieldState(text) }

    // Three remembers with three different lifetimes, and the split is the point.
    //
    // The cache is unkeyed, so the incremental scan survives everything — a fresh one per frame
    // would re-parse the whole note on every keystroke. The styles are a thin lookup table, rebuilt
    // only when the theme moves. The transformation is keyed on the styles, so a theme change
    // produces a new instance while the cache it was handed carries straight over.
    //
    // That a new instance is enough to repaint with no keystroke is not assumed: a theme switch is
    // not a buffer change, and ThemeRepaintTest is what settled it.
    val cache = remember { MarkdownTextCache() }
    val colors = DuckTheme.colors
    val type = DuckTheme.type
    val styles = remember(colors, type) { MarkdownStyles(colors, type) }
    val transformation = remember(styles) { MarkdownOutputTransformation(cache, styles) }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BasicTextField(
                state = state,
                modifier = Modifier.fillMaxSize(),
                outputTransformation = transformation,
                // noteBody carries the serif reading font and the size the engine's `em` heading
                // multiples resolve against.
                textStyle = type.noteBody.copy(color = colors.onSurface),
                // accent, not accentText: a caret is a fill, not a glyph.
                cursorBrush = SolidColor(colors.accent),
            )
        }
    }
}

/**
 * Exercises every token kind, so the prototype shows the whole engine rather than a happy path.
 *
 * `internal` rather than private since Phase 6: the preview below and the debug gallery's Editor
 * page are both fixtures for the same thing, and two copies of this would drift.
 */
internal val SAMPLE_NOTE = """
    # Duck Notes

    A **markdown** editor with _concealed_ syntax. Move the caret into a
    node and its markers ==reappear==.

    ## Syntax

    - [x] inline `code` and ~~corrections~~
    - [ ] tags such as #projects/duck and #multi word tag#
    - [ ] links: [the docs](https://duck.example) and [[Another Note]]

    > Plain text is the source of truth. Nothing here edits a document model.

    ```
    fun scan(line: String) = "fences are not markdown"
    ```

    ---

    1. headings, quotes, lists and tasks
    2. all of it re-scanned one line at a time
""".trimIndent()

@Preview(showBackground = true)
@Composable
private fun MarkdownEditorScreenPreview() {
    DuckTheme {
        MarkdownEditorScreen(SAMPLE_NOTE)
    }
}
