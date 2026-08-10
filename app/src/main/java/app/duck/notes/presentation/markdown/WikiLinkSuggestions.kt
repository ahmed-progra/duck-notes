package app.duck.notes.presentation.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.ui.DuckDivider
import app.duck.notes.domain.markdown.WikiLinkScanner

/**
 * The `[[title]]` picker — Phase 9c.
 *
 * ## It is a docked strip, not a caret-anchored popup, and that is the phase's central decision
 *
 * It sits in the editor's existing `bottomBar` above [AttachmentStrip], riding the same
 * `imePadding()` that floats the format toolbar over the keyboard. **Compose positions it; this file
 * computes no coordinate.** That is what keeps the whole of 9c in *source* offsets.
 *
 * A caret-anchored popup would need the caret's pixel position, which means mapping the source offset
 * through `SpanPlanner.renderedOffset` and the conceal cuts and then asking a `TextLayoutResult` for
 * `getCursorRect` — the `TaskBoxOverlay` rebase, and the same re-entry into rendered space that
 * create-on-tap needs. `MarkdownEditorScreen` already holds a `TextLayoutResult` for the overlays, so
 * the wrong tool is one line away; this phase does not touch it. Source-versus-rendered confusion has
 * shipped four times in this project, and the cheapest way to not add a fifth is to need no coordinate.
 *
 * ## Everything shown is derived, so nothing can get stuck
 *
 * There is no remembered "dismissed" flag and no open/closed state. The strip renders exactly when
 * `WikiLinkScanner.activeAt` finds a half-typed `[[` and something matches it, so closing the
 * brackets, moving the caret away, deleting the `[[`, typing a prefix nothing matches, or choosing an
 * entry all hide it by construction rather than by a handler that could be missed.
 *
 * ## Enter and the arrow keys are the text field's, deliberately
 *
 * Selecting is a tap. Capturing keys would mean `onPreviewKeyEvent` on `BasicTextField`, and `src/main`
 * contains no key handling at all today — a wrong guard there breaks newline insertion, the most-used
 * key in a markdown editor, to buy a convenience on a form factor nothing here tests. Enter therefore
 * inserts a newline, which also dismisses the strip: a link is line-local, so the context becomes null.
 * Stated ceiling; the upgrade path is key capture the day a hardware-keyboard test exists.
 *
 * ## RTL is the framework's, not ours
 *
 * `LazyRow` lays items start-to-end, so the best match sits at the **right** under RTL for free — the
 * same claim [AttachmentStrip] makes, and `RtlLayoutTest.theSuggestionStripMirrorsWithLayoutDirection`
 * is what turns it into evidence rather than an assumption.
 */
@Composable
fun WikiLinkSuggestions(
    titles: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (titles.isEmpty()) return
    val colors = DuckTheme.colors

    Column(modifier.fillMaxWidth().background(colors.surfaceElevated)) {
        DuckDivider()
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(DuckTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(DuckTheme.spacing.sm),
        ) {
            // Keyed by the title itself: `WikiLinkScanner.suggest` dedupes, so it is unique here.
            items(titles, key = { it }) { title ->
                Text(
                    text = title,
                    style = DuckTheme.type.uiLabel,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clickable(role = Role.Button) { onSelect(title) }
                        .padding(
                            horizontal = DuckTheme.spacing.md,
                            vertical = DuckTheme.spacing.sm,
                        ),
                )
            }
        }
    }
}

/**
 * Completes the half-typed link at [active] with [title], leaving the caret past the closing brackets.
 *
 * **All three offsets are source offsets.** [active] comes from `WikiLinkScanner.activeAt` over
 * `state.text`, and `TextFieldState.edit` indexes that same raw buffer — `OutputTransformation` only
 * changes what is *drawn*. Nothing here rebases anything.
 *
 * The caret lands **after** the `]]` rather than inside them, so typing continues outside the link
 * rather than extending its title. That is the whole reason this is a named function with a test
 * instead of two lines inside the composable: a completion that parks the caret somewhere surprising
 * is exactly the defect a green suite does not see. Setting it to `start + 2 + title.length` — inside
 * the brackets — is the plant.
 *
 * Text after the caret is left alone, so `[[Pon|d` completes to `[[Pond]]d`. Accepted and stated: the
 * alternative is guessing where the user meant their title to end.
 *
 * `internal` rather than private so `WikiLinkCompletionTest` drives the **real** function — the same
 * reason `applyFormat` is internal, and the shape CLAUDE.md's guard rules require.
 */
internal fun TextFieldState.completeWikiLink(active: IntRange, title: String) {
    val written = WikiLinkScanner.linkTo(title)
    edit {
        replace(active.first, active.last + 1, written)
        selection = TextRange(active.first + written.length)
    }
}
