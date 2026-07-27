package app.duck.notes.presentation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.ui.DuckDivider
import app.duck.notes.core.ui.DuckEmptyState
import app.duck.notes.domain.model.Note

/**
 * The middle pane: what the sidebar is filtering to, how many of them there are, and the cards.
 *
 * The header is where the reference layout puts a search field. **Phase 6 ships without one** — a
 * field that accepts text and does nothing is not a stub, it is a lie in the UI, and making it true
 * is Phase 10's entire Build list (debounce, operators, `snippet()` highlighting). Phase 5b already
 * deferred `DuckSearchField` on exactly that ground. The slot is here and empty on purpose.
 *
 * The count is `proseMuted`, not `chromeMuted`: `uiLabel` is 12sp, WCAG 1.4.3's large-text exemption
 * starts at 18pt, so it is normal text owing 4.5 and `chromeMuted` misses that on every light
 * palette.
 */
@Composable
fun DuckNoteList(
    filter: ShellFilter,
    notes: List<Note>,
    selectedNoteId: String?,
    onSelectNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing

    Column(modifier.fillMaxSize().background(colors.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = filter.label(),
                style = DuckTheme.type.uiTitle,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = notes.size.toString(),
                style = DuckTheme.type.uiLabel,
                color = colors.proseMuted,
                modifier = Modifier.padding(start = spacing.sm),
            )
        }
        DuckDivider()

        if (notes.isEmpty()) {
            DuckEmptyState(stringResource(R.string.shell_empty_notes))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(notes, key = { it.id }) { note ->
                    DuckNoteCard(
                        title = note.title,
                        preview = note.previewLine(),
                        modifiedAt = note.modifiedAt,
                        selected = note.id == selectedNoteId,
                        onClick = { onSelectNote(note.id) },
                    )
                    DuckDivider()
                }
            }
        }
    }
}

/**
 * The body minus the line the title came from.
 *
 * `DeriveTitle` already took the first non-empty line, so repeating it in the preview would show
 * every note's title twice. Blank lines are dropped rather than collapsed into the two visible
 * lines, since a markdown body is mostly blank lines between blocks.
 */
private fun Note.previewLine(): String =
    body.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .drop(1)
        .joinToString(" ")
        .take(PREVIEW_CHARS)

/** Two lines at `uiSecondary` never reach this; it caps the string a `maxLines` would clip anyway. */
private const val PREVIEW_CHARS = 200
