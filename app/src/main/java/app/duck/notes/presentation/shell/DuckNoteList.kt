package app.duck.notes.presentation.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import app.duck.notes.core.ui.DuckIconButton
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

/** Shared list header and basic search for every pane size. */
@Composable
fun DuckNoteList(
    filter: ShellFilter,
    notes: List<Note>,
    selectedNoteId: String?,
    onSelectNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAddTag: (noteId: String) -> Unit = {},
    onNewNote: (() -> Unit)? = null,
    creatingNote: Boolean = false,
    search: SearchState = SearchState(),
    onSearchChanged: ((String) -> Unit)? = null,
    onRetrySearch: () -> Unit = {},
    order: app.duck.notes.domain.model.NoteSort = app.duck.notes.domain.model.NoteSort.Modified,
    onNoteSort: ((app.duck.notes.domain.model.NoteSort) -> Unit)? = null,
    onPinNote: ((String, Boolean) -> Unit)? = null,
    onSearchFilters: ((app.duck.notes.domain.model.SearchFilters) -> Unit)? = null,
    tagPaths: List<String> = emptyList(),
    sortPending: Boolean = false,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing

    Column(modifier.fillMaxSize().background(colors.surface)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (!search.active) filter.label() else stringResource(R.string.search_results),
                style = DuckTheme.type.uiTitle,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = notes.size.toString(),
                style = DuckTheme.type.uiLabel,
                color = colors.proseMuted,
                modifier = Modifier.padding(start = spacing.sm),
            )
            onNewNote?.let { create ->
                DuckIconButton(R.drawable.duck_new_note, stringResource(R.string.note_new), create, enabled = !creatingNote)
            }
        }
        onSearchChanged?.let { change ->
            OutlinedTextField(
                value = search.query,
                onValueChange = change,
                label = { Text(stringResource(R.string.search_notes)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm),
                trailingIcon = {
                    if (search.query.isNotEmpty()) DuckIconButton(R.drawable.duck_clear, stringResource(R.string.search_clear), { change("") })
                },
            )
        }
        NoteListControls(order, search.filters, tagPaths, onNoteSort, onSearchFilters, sortPending)
        DuckDivider()
        when (search.phase) {
            SearchPhase.Pending -> { DuckEmptyState(stringResource(R.string.search_pending)); return@Column }
            SearchPhase.Failed -> {
                Text(stringResource(R.string.search_failed), modifier = Modifier.padding(spacing.lg), color = colors.onSurface)
                TextButton(onClick = onRetrySearch, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.editor_save_retry)) }
                return@Column
            }
            else -> Unit
        }

        if (notes.isEmpty()) {
            DuckEmptyState(stringResource(if (!search.active) R.string.shell_empty_notes else R.string.search_empty))
        } else {
            val motion = DuckTheme.motion
            LazyColumn(Modifier.fillMaxSize()) {
                items(notes, key = { it.id }) { note ->
                    // Wrapped so the card and its divider move as one item. Without the Column the
                    // modifier would animate the card and leave the divider to jump.
                    //
                    // The list reorders on every save — `SaveNote` restamps `modifiedAt` and the
                    // query is newest-first — so a note being edited climbs to the top. Unanimated,
                    // that reads as rows teleporting.
                    // Three different specs, because the three are three different jobs: arriving
                    // (200ms, decelerating), moving (a spring, because reorders interrupt each
                    // other), and leaving (120ms, accelerating — shorter, so dead rows clear).
                    Column(
                        Modifier.animateItem(
                            fadeInSpec = motion.mediumSpec(),
                            placementSpec = motion.placementSpec(),
                            fadeOutSpec = motion.exitSpec(),
                        ),
                    ) {
                        DuckNoteCard(
                            title = note.title,
                            preview = note.previewLine(),
                            modifiedAt = note.modifiedAt,
                            selected = note.id == selectedNoteId,
                            onClick = { onSelectNote(note.id) },
                            onAddTag = if (note.isTrashed) null else ({ onAddTag(note.id) }),
                            pinned = note.isPinned,
                            onTogglePin = if (note.isTrashed || onPinNote == null) null else ({ onPinNote(note.id, !note.isPinned) }),
                        )
                        DuckDivider()
                    }
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
internal fun Note.previewLine(): String {
    // Bound work before splitting: list previews must not join an entire large note.
    val prefix = body.take(4096)
    var carry = app.duck.notes.domain.markdown.LineCarry()
    var titleSeen = false
    val result = StringBuilder()
    for (line in prefix.lineSequence()) {
        val (tokens, next) = app.duck.notes.domain.markdown.MarkdownScanner.scanLine(line, 0, carry)
        carry = next
        if (line.isBlank()) continue
        if (!titleSeen) { titleSeen = true; continue }
        val hidden = BooleanArray(line.length)
        tokens.flatMap { it.markers }.forEach { range ->
            range.forEach { if (it in hidden.indices) hidden[it] = true }
        }
        val plain = buildString { line.forEachIndexed { i, c -> if (!hidden[i]) append(c) } }.trim()
        if (plain.isNotEmpty()) {
            if (result.isNotEmpty()) result.append(' ')
            result.append(plain)
        }
        if (result.length >= PREVIEW_CHARS) break
    }
    val preview = result.toString().take(PREVIEW_CHARS).trim()
    return if (preview.lastOrNull()?.isHighSurrogate() == true) preview.dropLast(1) else preview
}

/** Two lines at `uiSecondary` never reach this; it caps the string a `maxLines` would clip anyway. */
private const val PREVIEW_CHARS = 200
