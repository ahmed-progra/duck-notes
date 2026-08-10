package app.duck.notes.presentation.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.ui.DuckEmptyState
import app.duck.notes.domain.model.NoteLink

/**
 * The info panel's contents: **backlinks, and nothing else this phase.**
 *
 * Word and character counts, the table of contents and unlinked mentions are all specced for Phase 9
 * and all deliberately absent here — 9b is the linking half. They are additive rows in this same
 * `Column`, so nothing about this shape has to change to admit them.
 *
 * One composable for both hosts. The Expanded side pane and the Compact/Medium bottom sheet differ
 * only in what *contains* them, which is `DuckShell`'s business; a second layout per host would be
 * two things to keep saying the same. That is also what makes the two RTL assertions in
 * `RtlLayoutTest` meaningful — they measure the containers, because the contents are shared.
 *
 * ## Every row is a real note, so it is `clickable` with `Role.Button`
 *
 * A backlink names a note by title and tapping it opens that note — `onSelectNote`, the same id-keyed
 * action `DuckNoteCard` uses. Nothing here maps a coordinate back into a source offset: a row carries
 * the source note's **id**, not a span, which is why 9b adds no offset-space surface. Create-on-tap
 * is what changes that, and it is a later phase.
 *
 * [NoteLink.sourceTitle] is joined at read time rather than stored, so a row can never name a title
 * the linking note has since edited away.
 */
@Composable
fun DuckInfoPanel(
    backlinks: List<NoteLink>,
    onSelectNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.editor_backlinks),
            style = DuckTheme.type.uiLabel,
            color = colors.proseMuted,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
        )

        if (backlinks.isEmpty()) {
            DuckEmptyState(caption = stringResource(R.string.editor_backlinks_empty))
            return@Column
        }

        LazyColumn {
            // Keyed by source id: a note links a given target once (composite PK), so the id is
            // unique within this list and survives the list reordering as sources are edited.
            items(backlinks, key = { it.sourceId }) { link ->
                Text(
                    text = link.sourceTitle,
                    style = DuckTheme.type.uiLabel,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onSelectNote(link.sourceId) }
                        .padding(horizontal = spacing.lg, vertical = spacing.md),
                )
            }
        }
    }
}
