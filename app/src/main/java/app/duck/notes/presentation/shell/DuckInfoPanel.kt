package app.duck.notes.presentation.shell

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.remember
import app.duck.notes.R
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.ui.DuckEmptyState
import app.duck.notes.domain.markdown.MarkdownTextCache
import app.duck.notes.domain.markdown.NoteStats
import app.duck.notes.domain.markdown.SectionFolding
import app.duck.notes.domain.model.NoteLink

/**
 * The info panel: counts, timestamps, contents, backlinks — in that order.
 *
 * 9b shipped the backlinks half and said the rest were "additive rows in this same `Column`". They
 * were, with one structural correction: this is now a single [LazyColumn] rather than a `Column`
 * wrapping one. Two lists in a non-scrolling `Column` is the classic double-scrollable bug, and one
 * lazy list is *smaller* than nesting, not larger.
 *
 * One composable for both hosts. The Expanded side pane and the Compact/Medium bottom sheet differ
 * only in what *contains* them, which is `DuckShell`'s business. That is also what makes the three
 * RTL assertions in `RtlLayoutTest` meaningful — two measure the containers and one measures a row,
 * because the contents are shared.
 *
 * **They differ in exactly one behaviour, and it is named rather than discovered.** Below Expanded
 * the panel is a `ModalBottomSheet` covering the editor, so a contents tap scrolls something partly
 * hidden. It does not auto-dismiss: the backlinks row already does not (the panel is a *mode* the
 * user chose), the sheet is draggable and scrim-tappable, and a mode-conditional dismiss would mean
 * threading `PaneMode` in here — two panels wearing one name. Stated ceiling.
 *
 * ## Counts read the live buffer, and recompute per keystroke
 *
 * [buffer] rather than `ShellUiState.selectedNoteBody`: Room's copy lags the keyboard by up to
 * `DuckShellViewModel.MAX_WAIT_MS`, and a word count that trails what is on screen by two seconds is
 * the wrong thing to ship. The read happens **inside this composable**, not in `DuckShell`'s `info`
 * lambda — one level up it would recompose the whole shell on every keystroke.
 *
 * The panel is not composed while closed, so all of this costs nothing until it is opened.
 *
 * ponytail: while open this is a SECOND incremental scan, beside the one the editor runs, plus two
 * O(n) passes for the counts — on the surface whose 10k-word claim is still unevidenced. The cache is
 * `remember`ed so it rescans only changed lines, exactly as the editor's does. Upgrade path is having
 * `MarkdownEditorScreen` publish the `ScanResult` it already computes; worth doing the day a third
 * consumer wants it, not before.
 *
 * ## Nothing here maps a coordinate back to a source offset
 *
 * A backlink row carries a note **id**. A contents row carries a source **offset**, handed to
 * [onScrollToHeading] and mapped *forward* — source to rendered to bounding box — by the editor,
 * reusing the chain `SectionFoldOverlay` already runs per heading every frame. The inverse map still
 * does not exist and this phase does not build one.
 */
@Composable
fun DuckInfoPanel(
    backlinks: List<NoteLink>,
    onSelectNote: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The live editor buffer — counts and contents read this, not Room's copy. */
    buffer: TextFieldState = TextFieldState(),
    createdAt: Long? = null,
    modifiedAt: Long? = null,
    /** Scroll the editor to a heading, by its **source** offset. */
    onScrollToHeading: (Int) -> Unit = {},
) {
    val colors = DuckTheme.colors
    val spacing = DuckTheme.spacing

    // Unkeyed like the editor's, so the incremental rescan survives every keystroke rather than
    // re-parsing the note each time the panel recomposes.
    val cache = remember { MarkdownTextCache() }
    val text = buffer.text
    val body = text.toString()
    val words = remember(text) { NoteStats.words(body) }
    val characters = remember(text) { NoteStats.characters(body) }
    // Fence-correctness is inherited, not written: a `# heading` inside a fenced block scans as
    // FENCE_BODY, never reaches `scanBlockPrefix`, and so produces no HEADING token for
    // `SectionFolding` to find. That rule lives in `MarkdownScanner` and is held by
    // `SectionFoldingTest` and `GoldenSpanTest.fencedBlockIsNotStyledAsMarkdown`; a copy of it here
    // would be a fourth dressing of one check.
    val sections = remember(text) { SectionFolding.sections(body, cache.scan(body).tokens) }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        item {
            // NOT fillMaxWidth, and that is load-bearing rather than incidental: a full-width node's
            // bounds span the panel in both reading directions, which satisfies "starts in the
            // leading half" either way. `RtlLayoutTest.theCountsRowLeadsWithLayoutDirection` asserts
            // full containment against these bounds, and fillMaxWidth here is its plant.
            Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
                Text(
                    text = pluralStringResource(R.plurals.editor_word_count, words, words),
                    style = DuckTheme.type.uiLabel,
                    // proseMuted, not chromeMuted: a 12sp count is normal text under 1.4.3 and owes
                    // 4.5 — the same reason DuckNoteCard's timestamp is proseMuted.
                    color = colors.proseMuted,
                )
                Text(
                    text = pluralStringResource(R.plurals.editor_char_count, characters, characters),
                    style = DuckTheme.type.uiLabel,
                    color = colors.proseMuted,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }
        }

        if (createdAt != null && modifiedAt != null) {
            item {
                Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
                    // Relative spans from the same renderer DuckNoteCard uses, so the panel and the
                    // note list cannot describe one timestamp two ways. Device-local timezone,
                    // implicit in DateUtils.
                    //
                    // `modifiedAt` is what the AUTOSAVE PATH wrote: `SaveNote` restamps it on every
                    // save that carries an edit, so this trails the last keystroke by up to
                    // MAX_WAIT_MS and names the last save rather than the last edit. The one write
                    // that deliberately does not restamp is the owed tag-minting one, which carries
                    // no new keystrokes.
                    //
                    // A note never modified since creation shows the same string twice: `SaveNote`
                    // sets both to `now` on create. Shown rather than hidden — it is the truth, and
                    // suppressing the second row would cost a branch to say less.
                    Text(
                        text = stringResource(
                            R.string.editor_created,
                            DateUtils.getRelativeTimeSpanString(createdAt).toString(),
                        ),
                        style = DuckTheme.type.uiLabel,
                        color = colors.proseMuted,
                    )
                    Text(
                        text = stringResource(
                            R.string.editor_modified,
                            DateUtils.getRelativeTimeSpanString(modifiedAt).toString(),
                        ),
                        style = DuckTheme.type.uiLabel,
                        color = colors.proseMuted,
                        modifier = Modifier.padding(top = spacing.xs),
                    )
                }
            }
        }

        // No headings, no section at all — not a heading with an empty state. Backlinks earns one
        // because "nothing links here yet" is information; "this note has no headings" is noise.
        if (sections.isNotEmpty()) {
            item { SectionHeading(stringResource(R.string.editor_contents)) }
            // Keyed by the fold key — `level:occurrence:title`, unique by construction, and the same
            // identity the fold chevrons use, so a row and its chevron cannot disagree about which
            // heading they mean.
            items(sections, key = { it.key }) { section ->
                Text(
                    text = section.title,
                    style = DuckTheme.type.uiLabel,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onScrollToHeading(section.headingStart) }
                        // `start`, not `left`: the indent mirrors with reading direction for free.
                        // All six levels are listed — filtering to 1-3 is a rule nobody asked for.
                        .padding(
                            start = spacing.lg + spacing.md * (section.level - 1),
                            end = spacing.lg,
                            top = spacing.md,
                            bottom = spacing.md,
                        ),
                )
            }
        }

        item { SectionHeading(stringResource(R.string.editor_backlinks)) }

        if (backlinks.isEmpty()) {
            item { DuckEmptyState(caption = stringResource(R.string.editor_backlinks_empty)) }
        } else {
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

/** The label above a group of rows. Two callers, so the weight and colour cannot drift apart. */
@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = DuckTheme.type.uiLabel,
        color = DuckTheme.colors.proseMuted,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(
            horizontal = DuckTheme.spacing.lg,
            vertical = DuckTheme.spacing.md,
        ),
    )
}
