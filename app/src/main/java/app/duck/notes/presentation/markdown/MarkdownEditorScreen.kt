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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.duck.notes.core.markdown.MarkdownOutputTransformation
import app.duck.notes.core.markdown.MarkdownStyles
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.markdown.MarkdownTextCache

/**
 * The editor — the markdown engine over a buffer someone else owns.
 *
 * **Phase 8a took the buffer away and it is not coming back.** Until then this held its own state in
 * `rememberSaveable(text, saver = TextFieldState.Saver)`, which was right for Phase 6 and wrong for
 * a real editor in two ways:
 *
 * - **It serialised the whole note body into the saved-instance `Bundle`** on every rotation, through
 *   a 1MB per-process Binder buffer already shared with `DuckShellViewModel`'s `SavedStateHandle`.
 *   A 10k-word note — this phase's own target — put `TransactionTooLargeException` in range.
 * - **`rememberSaveable` keys on composition position**, and the editor moves between `Scaffold`
 *   content on Compact and a `Row` child on Expanded, so crossing 600dp silently dropped the buffer.
 *
 * Both are gone rather than moved: [state] is a plain field on `DuckShellViewModel`, which has no
 * composition key at all, and the `Bundle` now carries only the note id. Restore is "read the id,
 * re-query Room" — which is what makes *"kill the app mid-typing"* true for real rather than true up
 * to a `Bundle` size.
 *
 * This composable now holds **nothing saveable**. Do not give it a fallback `TextFieldState`: a
 * default would let a caller silently get a detached buffer that autosave never sees.
 */
@Composable
fun MarkdownEditorScreen(state: TextFieldState, modifier: Modifier = Modifier) {
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
        // `remember`, not `rememberTextFieldState` — that IS the saver, and a preview has no process
        // death to survive. Keeping it out means `src/main` has no saveable buffer at all, which is
        // an invariant a scan can check rather than an exception it has to carve out.
        MarkdownEditorScreen(remember { TextFieldState(SAMPLE_NOTE) })
    }
}
