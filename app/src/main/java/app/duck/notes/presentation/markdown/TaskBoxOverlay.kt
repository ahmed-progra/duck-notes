package app.duck.notes.presentation.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import app.duck.notes.R
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.markdown.TaskBox
import kotlin.math.roundToInt

/**
 * The tappable half of a rendered `[ ]`. Transparent, exactly the size of the box, on top of the text.
 *
 * ## Why an overlay and not a pointer interceptor
 *
 * The obvious design is to intercept on `PointerEventPass.Initial` in the text field and hit-test the
 * down event. It loses on all three of the constraints this had to meet: raw interception must
 * hand-roll slop and timeout or it eats scrolls, it has to decide by itself not to move the caret,
 * and **it produces no accessibility node at all** — a consumed tap on a non-semantic span is
 * invisible to TalkBack. `Modifier.toggleable` answers all three and is one line.
 *
 * ## It must be inside the scroll container
 *
 * `toggleable` builds on `clickable`, which **cancels** its gesture on drag rather than consuming it,
 * and the cancelled drag then travels to a scrollable *ancestor*. Place this as a sibling of the
 * scrolling column and there is no such ancestor: the drag reaches nothing, the note does not scroll,
 * and it reads as *"toggleable swallows drags"*. That cost two iterations in `TaskTapSpikeTest`
 * before it was recognised as a fixture bug rather than a Compose finding — SETUP.md trap 7. The
 * placement is also what production needs anyway, since the overlay is pinned to text that scrolls.
 *
 * ## Two stated ceilings
 *
 * **Target size — measured at 16.4 × 26.3dp, which is under WCAG 2.5.8's 24 × 24.** Stating the
 * number rather than a claim, because the plan assumed it would clear 24 and it does not. It is
 * `theBoxTargetSizeIsRecorded` that measures it; that test deliberately asserts no floor, since the
 * box is glyph-sized and moves with Phase 14's font-size setting and the user's display scale.
 *
 * **It stands on 2.5.8's `inline` exception, not on the numbers**: the exception covers a target
 * "in a sentence, or whose size is otherwise constrained by the line-height of non-target text",
 * which is exactly a `[ ]` rendered as characters inside a line of prose. That is a real allowance,
 * not a rounding of one — and it is worth knowing it is the only thing this passes on.
 *
 * Widening is not the fix and was not applied. Growing to 48dp vertically overlaps the lines above
 * and below and steals their taps; growing horizontally swallows taps meant to place the caret in
 * the task's own text, which is the behaviour `TaskToggleUiTest` pins and plant P7 falsifies. The
 * toolbar's Checkbox button is the 48dp route to the same action. If the inline exception is judged
 * insufficient, the answer is a drawn checkbox in the editor's Phase 8 draw layer, not a bigger
 * transparent rectangle over text.
 *
 * **Count.** One overlay per task box in the note, not windowed to the viewport. Fine for a realistic
 * note; a pathological one gets hundreds of composables.
 * ponytail: no viewport culling, add it if the 10k-word scroll target ever gets measured and fails.
 * Upgrade path is intersecting `box.rendered` against the visible rect before emitting.
 */
@Composable
internal fun TaskBoxOverlay(box: TaskBox, layout: TextLayoutResult, enabled: Boolean = true, onToggle: () -> Unit) {
    val first = layout.getBoundingBox(box.rendered.first)
    val last = layout.getBoundingBox(box.rendered.last)
    // min/max rather than reading one rect: a box at a wrap point would otherwise get a negative
    // width. Three characters at the start of a line effectively never wrap, so this is cheap
    // insurance rather than a case being handled.
    val left = minOf(first.left, last.left)
    val top = minOf(first.top, last.top)
    val width = maxOf(first.right, last.right) - left
    val height = maxOf(first.bottom, last.bottom) - top
    val density = LocalDensity.current
    val label = box.text.ifBlank { stringResource(R.string.task_checkbox) }

    Box(
        Modifier
            // absoluteOffset, NOT offset, and the difference is the whole of RTL support here.
            //
            // `left` comes from TextLayoutResult.getBoundingBox, which returns where bidi actually
            // put the glyph — an absolute visual coordinate that already accounts for direction.
            // Modifier.offset mirrors its x under RTL, so handing it a coordinate that is already
            // resolved mirrors it a second time and moves the overlay off the characters it
            // represents. absoluteOffset is the variant that leaves an absolute value alone.
            //
            // This is the opposite of the rule everywhere else in the file tree — start/end over
            // left/right — and it is opposite for a reason: those describe intent, this reports a
            // measurement. Pinned by RtlLayoutTest.theTaskBoxTracksItsGlyphsInBothDirections.
            .absoluteOffset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(density) { width.toDp() }, with(density) { height.toDp() })
            // No background: the box's glyphs are what the user sees, painted by MarkdownStyles as
            // TASK_BOX_OPEN / TASK_BOX_DONE. A tint here would be a fourth painted pair.
            .toggleable(value = box.done, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = label },
    )
}

/**
 * Flips the box at [box] in the buffer, leaving the caret where it was.
 *
 * Same shape and same argument as [applyFormat]: `state.edit` is the path the keyboard drives, so
 * `snapshotFlow { buffer.text }` emits and the 400ms debounce writes it. **No new write path**, and
 * routing it through `rewriteBuffer` is impossible rather than discouraged — that function is
 * `private` to `DuckShellViewModel`.
 *
 * The selection is captured and restored explicitly. A whole-buffer `replace` would otherwise collapse
 * it, and *the caret must not move* is the whole difference between tapping a checkbox and tapping
 * text. Restoring the old range is exact here because a toggle is a one-character substitution, so
 * every offset in the buffer is unchanged.
 *
 * `internal` so `DuckShellAutosaveTest` drives the **real** function. A test that retyped these four
 * lines would pass while this one rotted.
 */
internal fun TextFieldState.toggleTask(box: TaskBox) {
    val current = text.toString()
    val toggled = MarkdownScanner.toggleTaskAt(current, box.source.first) ?: return
    val before = selection
    edit {
        replace(0, length, toggled)
        selection = before
    }
}
