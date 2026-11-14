package app.duck.notes.presentation.markdown

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.tooling.preview.Preview
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.ui.DuckDivider
import app.duck.notes.core.ui.DuckIconButton
import app.duck.notes.domain.markdown.FormatAction
import app.duck.notes.domain.markdown.MarkdownFormat

/** Formatting and editing commands share the session buffer, undo history and autosave path. */
@Composable
fun FormatToolbar(
    state: TextFieldState,
    enabled: Boolean,
    canEdit: () -> Boolean = { true },
    modifier: Modifier = Modifier,
    onEnterFocus: (() -> Unit)? = null,
    onToggleInfo: (() -> Unit)? = null,
    onAddImage: (() -> Unit)? = null,
    onFind: (() -> Unit)? = null,
) {
    val colors = DuckTheme.colors

    Column(modifier = modifier.fillMaxWidth().background(colors.surfaceElevated)) {
        // Not decoration: `surfaceElevated on surface` is 1.06 in SepiaLight, so the bar does not
        // separate from the page as a plane there. Same argument as the shell's vertical pane rules.
        DuckDivider()
        Row(
            // Preserve full touch targets; secondary commands live in the overflow menu.
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            val addImageLabel = stringResource(R.string.attachment_add)
            onAddImage?.let { add ->
                androidx.compose.material3.TextButton(enabled = enabled, modifier = Modifier.semantics { contentDescription = addImageLabel }, onClick = { if (canEdit()) add() }) {
                    androidx.compose.material3.Text(stringResource(R.string.attachment_add))
                }
            }
            DuckIconButton(R.drawable.duckformat_undo, stringResource(R.string.editor_undo),
                enabled = canEdit() && state.undoState.canUndo, onClick = { if (canEdit()) state.undoState.undo() })
            DuckIconButton(R.drawable.duckformat_redo, stringResource(R.string.editor_redo),
                enabled = canEdit() && state.undoState.canRedo, onClick = { if (canEdit()) state.undoState.redo() })
            listOf(FormatAction.BOLD, FormatAction.ITALIC).forEach { action ->
                DuckIconButton(
                    icon = action.icon,
                    contentDescription = stringResource(action.label),
                    enabled = enabled,
                    onClick = { if (canEdit()) state.applyFormat(action) },
                )
            }
            Box {
                var expanded by remember { mutableStateOf(false) }
                DuckIconButton(R.drawable.duckformat_more, stringResource(R.string.editor_format_more), onClick = { expanded = true })
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    FormatAction.entries.filter { it != FormatAction.BOLD && it != FormatAction.ITALIC }.forEach { action ->
                        DropdownMenuItem(text = { Text(stringResource(action.label)) }, enabled = enabled,
                            onClick = { expanded = false; if (canEdit()) state.applyFormat(action) })
                    }
                    onFind?.let { find ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.editor_find)) }, onClick = { expanded = false; find() })
                    }
                }
            }
            // Focus toggle trails the format actions. `enabled = true` unconditionally — entering
            // focus mode has nothing to do with whether the caret sits in writable markdown.
            onEnterFocus?.let { enter ->
                DuckIconButton(
                    icon = R.drawable.duckformat_focus,
                    contentDescription = stringResource(R.string.editor_focus),
                    onClick = enter,
                )
            }
            // Backlinks toggle, 9b. `enabled = true` for the same reason focus is: what links *to*
            // this note has nothing to do with whether the caret sits in writable markdown.
            onToggleInfo?.let { toggle ->
                DuckIconButton(
                    icon = R.drawable.duckformat_backlink,
                    contentDescription = stringResource(R.string.editor_backlinks),
                    onClick = toggle,
                )
            }
        }
    }
}

/**
 * Applies [action] to the buffer through the same `edit` the keyboard uses.
 *
 * ponytail: whole-buffer replace, because `MarkdownFormat` returns a whole body. One press is one
 * undo entry, which is the behaviour you want, and this is not a keystroke path so the O(n) copy is
 * paid once per tap rather than per character. Upgrade path if that ever bites: have `MarkdownFormat`
 * return the splices it made instead of the finished string.
 *
 * `internal` rather than private so `DuckShellAutosaveTest` can drive the **real** function. A test
 * that re-typed these four lines would pass while this one rotted, which is the failure mode an audit
 * of this suite already found once.
 */
internal fun TextFieldState.applyFormat(action: FormatAction) {
    val current = text.toString()
    val before = selection
    val result = MarkdownFormat.apply(current, before.start, before.end, action)
    if (result.body == current && result.start == before.start && result.end == before.end) return
    edit {
        replace(0, length, result.body)
        selection = TextRange(result.start, result.end)
    }
}

/**
 * Glyph and label per action, as one exhaustive `when` each.
 *
 * Exhaustive rather than a map, so adding a [FormatAction] without a glyph does not compile — the
 * same totality `DuckIcon.drawable()` gets, in the layer that is allowed to know about `R`.
 * `FormatAction` itself lives in `domain/`, which `DomainPurityTest` forbids from importing Android.
 */
@get:DrawableRes
private val FormatAction.icon: Int
    get() = when (this) {
        FormatAction.BOLD -> R.drawable.duckformat_bold
        FormatAction.ITALIC -> R.drawable.duckformat_italic
        FormatAction.CODE -> R.drawable.duckformat_code
        FormatAction.HIGHLIGHT -> R.drawable.duckformat_highlight
        FormatAction.HEADING -> R.drawable.duckformat_heading
        FormatAction.BULLET -> R.drawable.duckformat_bullet
        FormatAction.QUOTE -> R.drawable.duckformat_quote
        FormatAction.TASK -> R.drawable.duckformat_task
        FormatAction.LINK -> R.drawable.duckformat_link
    }

@get:StringRes
private val FormatAction.label: Int
    get() = when (this) {
        FormatAction.BOLD -> R.string.format_bold
        FormatAction.ITALIC -> R.string.format_italic
        FormatAction.CODE -> R.string.format_code
        FormatAction.HIGHLIGHT -> R.string.format_highlight
        FormatAction.HEADING -> R.string.format_heading
        FormatAction.BULLET -> R.string.format_bullet
        FormatAction.QUOTE -> R.string.format_quote
        FormatAction.TASK -> R.string.format_task
        FormatAction.LINK -> R.string.format_link
    }

@Preview(showBackground = true)
@Composable
private fun FormatToolbarPreview() {
    DuckTheme {
        FormatToolbar(state = TextFieldState("some text"), enabled = true)
    }
}

@Preview(showBackground = true)
@Composable
private fun FormatToolbarInAFencePreview() {
    DuckTheme {
        // What the user sees with the caret inside a fenced block: every button disabled, which is
        // the entire refusal. There is no message path in 8c, by design — a disabled button is
        // visible before the tap, so a Snackbar would explain a state already on screen.
        //
        // The text is empty rather than an actual fence because nothing here reads it — `enabled` is
        // the whole input to what gets drawn. Writing one would also put a markdown literal in this
        // file, which `MarkdownSyntaxSourceTest` forbids, and weakening that guard to admit a preview
        // would be the wrong trade.
        FormatToolbar(state = TextFieldState(), enabled = false)
    }
}
