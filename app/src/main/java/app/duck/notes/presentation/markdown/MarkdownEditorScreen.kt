package app.duck.notes.presentation.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.duck.notes.core.markdown.MarkdownOutputTransformation
import app.duck.notes.core.markdown.MarkdownStyles
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.markdown.MarkdownFormat
import app.duck.notes.domain.markdown.MarkdownTextCache
import app.duck.notes.domain.markdown.SectionFolding
import app.duck.notes.domain.markdown.SpanPlanner

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
 *
 * ## Phase 8c hung the format toolbar under it
 *
 * [FormatToolbar] is the `bottomBar`, and `imePadding()` is what floats it over the keyboard rather
 * than under it. Its buttons write through `state.edit`, the same path the keyboard uses, so autosave
 * needs no new entry point — see that file for the argument.
 *
 * ## 8c-b hung a tap target on every checkbox
 *
 * `SpanPlanner` now leaves `[ ] ` in the rendered text rather than concealing the whole `- [ ] `, and
 * [TaskBoxOverlay] puts a transparent `toggleable` on each one. Before that carve-out there was
 * literally nothing to hit: `TaskTapSpikeTest` measured every tap across a task line landing in
 * `{0} ∪ [6..]` and never on the box, because the six marker offsets collapsed onto one rendered
 * position. The overlay writes through `state.edit` too, so it is a third caller of one write path
 * rather than a third write path.
 */
@Composable
fun MarkdownEditorScreen(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    collapsedSections: Set<String> = emptySet(),
    onToggleSection: (String) -> Unit = {},
) {
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
    val transformation = remember(styles, collapsedSections) {
        MarkdownOutputTransformation(cache, styles, collapsedSections)
    }

    // The toolbar's enabled state, off the SAME incremental scan the transformation renders from.
    //
    // Keyed on the text, not on the selection: only the text can move a fence, and the caret moves
    // far more often than it changes. Keying on both would run a scan per caret move, on the one
    // surface still carrying the unresolved 10k-word question. `cache.scan` is safe to call here for
    // the reason its KDoc gives — an `AtomicReference`, deliberately not snapshot state, and a
    // repeat call on identical text is a pure read.
    val text = state.text
    val selection = state.selection
    val scan = remember(text) { cache.scan(text.toString()) }
    val writable = MarkdownFormat.isWritable(scan, selection.start, selection.end)

    // The task overlays, and the one thing they need that the toolbar does not: the caret.
    //
    // Keyed on the selection as well as the text, because the conceal moves with the caret and the
    // rendered offsets move with it — a box on the line the caret is on sits two characters further
    // right, since `- ` is revealed rather than deleted. A stale plan puts the tap target on `] `.
    //
    // ponytail: this is a SECOND SpanPlanner.plan per frame, beside the one the transformation runs.
    // O(tokens) on a surface whose 10k-word scroll claim is still unevidenced. Upgrade path is having
    // MarkdownOutputTransformation publish the plan it already computed rather than recomputing here.
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val folds = remember(text, collapsedSections, selection) {
        SectionFolding.ranges(
            text.toString(), scan.tokens, collapsedSections, selection.start, selection.end,
        )
    }
    val plan = remember(text, collapsedSections, selection) {
        SpanPlanner.plan(scan.tokens, selection.start, selection.end, folds)
    }
    val boxes = remember(plan) { SpanPlanner.taskBoxes(text.toString(), scan.tokens, plan) }
    val sections = remember(text) { SectionFolding.sections(text.toString(), scan.tokens) }

    Scaffold(
        // imePadding, so the bar rides above the keyboard instead of behind it.
        modifier = modifier.fillMaxSize().imePadding(),
        bottomBar = { FormatToolbar(state = state, enabled = writable) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // start is 0 because [FOLD_GUTTER] supplies the left inset now: the text begins
                // 48dp in, where it began 20dp in before the gutter existed.
                .padding(start = 0.dp, end = 20.dp, top = 12.dp, bottom = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Two columns, and the split is the fix for a defect 8c-c shipped.
            //
            // The fold chevron used to be an absolutely-positioned child of the text Box at
            // `bounds.left - 24.dp`. For a heading at column 0 — the normal case — that put its
            // parent-space bounds at [-24dp, 0dp], entirely left of its parent's origin. Compose
            // hit-tests within ancestor layout bounds, so the control was **permanently untappable**
            // while its semantics node still looked perfect. Measured on device at -3.81dp against
            // an editor starting at 20.19dp.
            //
            // A real gutter column fixes it by construction rather than by another coordinate
            // adjustment: the chevron is a sibling in its own layout slot, so its x is 0 within a
            // parent that actually contains it and there is no left-of-origin arithmetic to get
            // wrong again. It also leaves [TaskBoxOverlay]'s coordinate math completely untouched —
            // the text Box is still exactly the text field's coordinate space, which is what that
            // overlay's rebased offsets assume.
            //
            // Both Boxes stay INSIDE the scrolling Column so a drag starting on either overlay still
            // reaches the scroll. Load-bearing; SETUP.md trap 7.
            Row(modifier = Modifier.fillMaxWidth()) {
                val measured = layout

                // The gutter is given the text's own height. A chevron offset past its parent's
                // bottom would be unhittable for exactly the reason the old one was unhittable to
                // the left — same defect, one axis over.
                Box(
                    modifier = Modifier
                        .width(FOLD_GUTTER)
                        .height(with(LocalDensity.current) { (measured?.size?.height ?: 0).toDp() }),
                ) {
                    if (measured != null) {
                        sections.forEach { section ->
                            // Only "does this section have a body to hide". The bounds check that
                            // used to sit here compared `section.headingStart` — a SOURCE offset —
                            // against the RENDERED text length, so once an earlier section folded
                            // and the rendered text got shorter, every later heading's chevron
                            // silently vanished. SectionFoldOverlay already guards the right
                            // quantity, its own rebased offset, so this one was both wrong and
                            // redundant. Same source-vs-rendered confusion as the offset defect.
                            if (section.content != null) {
                                SectionFoldOverlay(
                                    section = section,
                                    plan = plan,
                                    layout = measured,
                                    collapsed = section.key in collapsedSections,
                                    onToggle = { onToggleSection(section.key) },
                                )
                            }
                        }
                    }
                }

                // AbsoluteAlignment.TopLeft, not the default TopStart, and it is half of RTL support
                // for the overlays. Both TaskBoxOverlay and its offsets are expressed in the text
                // layout's own absolute pixel space, where x grows rightward whatever the reading
                // direction. Box's default TopStart resolves to the RIGHT edge under RTL, so the
                // overlay was being based at the right edge and then offset rightward again by the
                // glyph's x: measured at 673.9dp against a field ending at 363.4dp, i.e. off the
                // device. Basing them at the absolute left makes `absoluteOffset` mean what it says.
                //
                // The text field fills this Box, so its own placement is unaffected either way.
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = AbsoluteAlignment.TopLeft,
                ) {
                    BasicTextField(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        outputTransformation = transformation,
                        // noteBody carries the serif reading font and the size the engine's `em`
                        // heading multiples resolve against.
                        textStyle = type.noteBody.copy(color = colors.onSurface),
                        // accent, not accentText: a caret is a fill, not a glyph.
                        cursorBrush = SolidColor(colors.accent),
                        onTextLayout = { getResult -> layout = getResult() },
                    )

                    if (measured != null) {
                        boxes.forEach { box ->
                            // The layout is one frame behind on the frame the text changes, so a box
                            // computed from the new plan can point past the end of the old layout.
                            // Skipping it costs one frame of a missing overlay; not skipping it
                            // throws out of getBoundingBox.
                            if (box.rendered.last < measured.layoutInput.text.length) {
                                TaskBoxOverlay(box, measured) { state.toggleTask(box) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Exercises every token kind, so the prototype shows the whole engine rather than a happy path.
 *
 * `internal` rather than private since Phase 6: the preview below and the debug gallery's Editor
 * page are both fixtures for the same thing, and two copies of this would drift.
 */
/**
 * The leading column the fold chevrons live in, the editor's left inset, **and the chevron's own
 * touch target** — `SectionFoldOverlay` sizes itself from this, so the three cannot drift apart.
 *
 * **48dp is Android's documented minimum touch target**, and the reason it is not 24dp is a real
 * distinction the earlier gate could not see. `SectionFoldUiTest.theChevronStaysInsideTheGutter`
 * proves the target is *reachable* — that its bounds lie inside a parent that hit-tests it — and
 * Compose's `click()` lands on exact bounds, so a 24dp target passes every check while still being
 * an unreliable thumb target on glass. Reachability and size are different properties and nothing
 * here measures the second.
 *
 * Unlike the task checkbox, which is 16.4 × 26.3dp and stands on WCAG 2.5.8's `inline` exception
 * because it is drawn as characters inside a sentence, this control sits in its own gutter with no
 * such excuse — so it takes the full 48.
 *
 * The reading column starts immediately after it: text now begins **48dp** from the pane edge,
 * having been 20dp before the gutter existed. Anything asserting or documenting that number reads
 * it from here.
 */
internal val FOLD_GUTTER = 48.dp

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
