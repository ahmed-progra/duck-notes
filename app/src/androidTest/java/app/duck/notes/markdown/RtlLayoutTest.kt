package app.duck.notes.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.NoteLink
import app.duck.notes.presentation.markdown.MarkdownEditorScreen
import app.duck.notes.presentation.shell.DuckShell
import app.duck.notes.presentation.shell.ShellActions
import app.duck.notes.presentation.shell.ShellUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first layout-direction-aware tests in the project.
 *
 * `android:supportsRtl="true"` has been in the manifest since Phase 1, but nothing in `src/main`
 * reads `LocalLayoutDirection` and nothing asserted mirroring, so "RTL support" meant "the manifest
 * says yes". These pin what actually happens.
 *
 * Direction is a `mutableStateOf` flipped mid-test rather than two separate tests, so each property
 * is **one assertion over both directions**. A per-direction test pair proves only whichever runs
 * first — the reason `TaskToggleUiTest`'s drag test is written the same way.
 *
 * The sample is Arabic, not Latin text in a mirrored container: bidi resolution is the thing under
 * test, and Latin glyphs in an RTL container do not exercise it.
 */
@RunWith(AndroidJUnit4::class)
class RtlLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private var direction by mutableStateOf(LayoutDirection.Ltr)

    private fun editor(text: String) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                DuckTheme { MarkdownEditorScreen(TextFieldState(text)) }
            }
        }
        rule.waitForIdle()
    }

    private fun flipToRtl() {
        rule.runOnUiThread { direction = LayoutDirection.Rtl }
        rule.waitForIdle()
    }

    private fun fieldBounds() = rule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()

    /**
     * The fold gutter is a leading column, so it must swap sides with reading direction.
     *
     * Expected to pass without a production change: `Row` lays children out start-to-end per
     * `LocalLayoutDirection`, so the gutter — the first child — moves to the right under RTL on its
     * own, and `SectionFoldOverlay` offsets by `IntOffset(0, y)`, where 0 cannot mirror wrongly.
     * That is a claim about Compose, and this is what turns it into evidence.
     */
    @Test
    fun theFoldGutterMirrorsWithLayoutDirection() {
        editor(NOTE)
        val ltrChevron = rule.onNodeWithContentDescription(HIDE_SECTION).getUnclippedBoundsInRoot()
        val ltrField = fieldBounds()
        println("DUCK rtl: LTR chevron=$ltrChevron field=$ltrField")

        flipToRtl()
        val rtlChevron = rule.onNodeWithContentDescription(HIDE_SECTION).getUnclippedBoundsInRoot()
        val rtlField = fieldBounds()
        println("DUCK rtl: RTL chevron=$rtlChevron field=$rtlField")

        assertEquals(
            "the fold gutter must lead in both directions: left of the text in LTR " +
                "($ltrChevron vs $ltrField), right of it in RTL ($rtlChevron vs $rtlField)",
            GutterBeforeText(true) to GutterAfterText(true),
            GutterBeforeText(ltrChevron.right <= ltrField.left) to
                GutterAfterText(rtlChevron.left >= rtlField.right),
        )
    }

    /**
     * The task box overlay must sit on its own glyphs in both directions.
     *
     * `TaskBoxOverlay` derives its x from `TextLayoutResult.getBoundingBox(...).left`, which is an
     * **absolute visual coordinate** — the glyph is already where bidi put it. Feeding that to
     * `Modifier.offset`, which mirrors x under RTL, would move the overlay away from the glyphs it
     * represents. `Modifier.absoluteOffset` is the one that leaves an absolute coordinate alone.
     *
     * **Asserted as containment, and the first draft got this wrong in the instructive direction.**
     * It checked "is the box in the trailing half of the field" under RTL, which passed — measured
     * at `673.9..690.3dp` against a field of `20..363` on a 411dp screen. The box was **off the
     * right edge of the device entirely**, and "off-screen right" satisfies "in the trailing half".
     * A real assertion with a real control, proving something other than what it was cited for:
     * the *misdirected* class in `DUCK_NOTES_BUILD_PROMPT.md`, written by the same hand that had
     * just fixed the chevron's containment defect.
     *
     * Containment is the invariant that survives bidi being bidi. Where exactly the glyph sits is
     * the text layout's business; that the overlay sits on top of it is ours.
     */
    @Test
    fun theTaskBoxTracksItsGlyphsInBothDirections() {
        editor(NOTE)
        val ltrBox = rule.onNodeWithContentDescription(TASK).getUnclippedBoundsInRoot()
        val ltrField = fieldBounds()
        println("DUCK rtl: LTR taskbox=$ltrBox field=$ltrField")

        flipToRtl()
        val rtlBox = rule.onNodeWithContentDescription(TASK).getUnclippedBoundsInRoot()
        val rtlField = fieldBounds()
        println("DUCK rtl: RTL taskbox=$rtlBox field=$rtlField")

        assertEquals(
            "the task box must lie inside the text field in both directions. LTR box=$ltrBox " +
                "field=$ltrField; RTL box=$rtlBox field=$rtlField. A box outside the field is not " +
                "on the characters it represents and cannot be tapped where the user sees them.",
            InsideLtr(true) to InsideRtl(true),
            InsideLtr(ltrBox.left >= ltrField.left && ltrBox.right <= ltrField.right) to
                InsideRtl(rtlBox.left >= rtlField.left && rtlBox.right <= rtlField.right),
        )
    }

    /**
     * The attachment strip is a `LazyRow` whose first item is the add tile, so it must lead — sit at
     * the left in LTR and the right in RTL, with the thumbnails trailing it either way.
     *
     * Expected to pass without a production change: `LazyRow` lays items start-to-end per
     * `LocalLayoutDirection`, so the add tile moves to the trailing... no — to the *leading* edge,
     * which is the right under RTL, on its own. `AttachmentStrip` adds nothing to make that happen.
     * That is a claim about Compose, and — like the fold gutter above — this is what turns it into
     * evidence rather than an assumption. The plant that proves it can fail is a hardcoded
     * `LocalLayoutDirection provides Ltr` inside the strip.
     *
     * Its own `setContent`, because the shared [editor] helper passes no attachments. The thumbnail's
     * uri is bogus and never loads; the tile is `.size`, so it has bounds regardless.
     */
    @Test
    fun theAttachmentStripMirrorsWithLayoutDirection() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                DuckTheme {
                    MarkdownEditorScreen(
                        state = TextFieldState(NOTE),
                        attachments = listOf(SAMPLE_ATTACHMENT),
                    )
                }
            }
        }
        rule.waitForIdle()

        val ltrAdd = rule.onNodeWithContentDescription(ADD_IMAGE).getUnclippedBoundsInRoot()
        val ltrThumb = rule.onNodeWithContentDescription(ATTACHMENT).getUnclippedBoundsInRoot()
        println("DUCK rtl: LTR add=$ltrAdd thumb=$ltrThumb")

        flipToRtl()
        val rtlAdd = rule.onNodeWithContentDescription(ADD_IMAGE).getUnclippedBoundsInRoot()
        val rtlThumb = rule.onNodeWithContentDescription(ATTACHMENT).getUnclippedBoundsInRoot()
        println("DUCK rtl: RTL add=$rtlAdd thumb=$rtlThumb")

        assertEquals(
            "the add tile must lead the strip in both directions: left of the first thumbnail in LTR " +
                "($ltrAdd vs $ltrThumb), right of it in RTL ($rtlAdd vs $rtlThumb). LazyRow lays items " +
                "start-to-end per LocalLayoutDirection; this is what proves it rather than assuming it.",
            AddBeforeThumb(true) to AddAfterThumb(true),
            AddBeforeThumb(ltrAdd.right <= ltrThumb.left) to
                AddAfterThumb(rtlAdd.left >= rtlThumb.right),
        )
    }

    /**
     * The focus-mode exit icon is a root overlay aligned `Alignment.TopEnd`, so it must swap corners
     * with reading direction — trailing (right) in LTR, leading (left) in RTL.
     *
     * **8d-a's rule: this is measured, not assumed.** `Alignment.TopEnd` mirroring is a claim about
     * Compose; the fold gutter turned out to mirror correctly but only because a test proved it, and
     * this keeps 8d-d from being the one exception. It renders the real [DuckShell], enters focus mode
     * through the real toolbar button, and reads the exit icon against the field's centre in both
     * directions. The plant that proves it can fail is `AbsoluteAlignment.TopRight` in `DuckShell`,
     * which pins the icon right regardless of direction.
     *
     * Own `setContent`: it drives `DuckShell`, not the shared [editor] helper's `MarkdownEditorScreen`.
     */
    @Test
    fun theFocusExitIconMirrorsWithLayoutDirection() {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                DuckTheme {
                    Box(Modifier.size(400.dp, 800.dp)) {
                        DuckShell(
                            state = ShellUiState(
                                notes = listOf(EXIT_NOTE),
                                selectedNoteId = EXIT_NOTE.id,
                                selectedNoteBody = EXIT_NOTE.body,
                            ),
                            buffer = TextFieldState(EXIT_NOTE.body),
                            actions = ShellActions(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()

        // Enter focus mode from the toolbar (trailing button, so scroll to it first).
        rule.onNodeWithContentDescription(ENTER_FOCUS).performScrollTo().performClick()
        rule.waitForIdle()

        val ltrExit = rule.onNodeWithContentDescription(EXIT_FOCUS).getUnclippedBoundsInRoot()
        val ltrField = fieldBounds()
        val ltrCentre = (ltrField.left + ltrField.right) * 0.5f
        println("DUCK rtl: LTR exit=$ltrExit field=$ltrField")

        flipToRtl()
        val rtlExit = rule.onNodeWithContentDescription(EXIT_FOCUS).getUnclippedBoundsInRoot()
        val rtlField = fieldBounds()
        val rtlCentre = (rtlField.left + rtlField.right) * 0.5f
        println("DUCK rtl: RTL exit=$rtlExit field=$rtlField")

        assertEquals(
            "the focus exit icon must sit at the trailing corner in both directions: right of the " +
                "field centre in LTR ($ltrExit vs $ltrField), left of it in RTL ($rtlExit vs " +
                "$rtlField). Alignment.TopEnd mirrors; AbsoluteAlignment.TopRight would pin it right.",
            ExitTrailingLtr(true) to ExitLeadingRtl(true),
            ExitTrailingLtr(ltrExit.left > ltrCentre) to ExitLeadingRtl(rtlExit.right < rtlCentre),
        )
    }

    /**
     * The Expanded backlinks pane is a **trailing** column, so it must swap sides with direction —
     * right of the editor in LTR, left of it in RTL.
     *
     * Expected to pass without a production change, for the same reason the fold gutter does: `Row`
     * lays children out start-to-end per `LocalLayoutDirection`, and the pane is declared *last* in
     * that `Row`. That is a claim about Compose, and 8d-a's rule is that such claims are measured
     * here rather than assumed — the fold gutter and the attachment strip both turned out correct
     * only because a test proved it, and the task box did not.
     *
     * **The plant is a pure reorder**: move `info(...)` above `editor(...)` in `DuckShell`'s Expanded
     * branch. That pins the pane leading in both directions, so the LTR half of the assertion goes
     * red. No new code, nothing to fail on its own terms — the plant discipline CLAUDE.md asks for.
     *
     * Measured against the **editor**, not the field centre: on Expanded the note list is also to the
     * left of the editor in LTR, so "left of centre" would be satisfied by the list's side of the
     * screen too. The editor is the pane the panel is about.
     */
    @Test
    fun theInfoPaneMirrorsWithLayoutDirection() {
        shellWithInfoOpen(width = EXPANDED_WIDTH)

        val ltrPane = rule.onNodeWithText(BACKLINKS).getUnclippedBoundsInRoot()
        val ltrEditor = fieldBounds()
        println("DUCK rtl: LTR infoPane=$ltrPane editor=$ltrEditor")

        flipToRtl()
        val rtlPane = rule.onNodeWithText(BACKLINKS).getUnclippedBoundsInRoot()
        val rtlEditor = fieldBounds()
        println("DUCK rtl: RTL infoPane=$rtlPane editor=$rtlEditor")

        assertEquals(
            "the backlinks pane must trail the editor in both directions: right of it in LTR " +
                "($ltrPane vs $ltrEditor), left of it in RTL ($rtlPane vs $rtlEditor). Row lays " +
                "children start-to-end per LocalLayoutDirection and the pane is its last child; " +
                "this is what proves that rather than assuming it.",
            PaneTrailingLtr(true) to PaneLeadingRtl(true),
            PaneTrailingLtr(ltrPane.left >= ltrEditor.right) to
                PaneLeadingRtl(rtlPane.right <= rtlEditor.left),
        )
    }

    /**
     * Below Expanded the panel is a `ModalBottomSheet`, and its rows must lead with direction — the
     * title starts at the left in LTR and at the right in RTL.
     *
     * The sheet sets no layout direction of its own and must not: `ModalBottomSheet` inherits
     * `LocalLayoutDirection`, so its contents lead correctly on their own. **The plant is the idiom
     * `theAttachmentStripMirrorsWithLayoutDirection` already names** — a hardcoded
     * `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)` wrapped around
     * `info(...)` inside the sheet, which pins the rows left in both directions.
     *
     * Asserted as *which half of the sheet the row starts in* rather than an absolute coordinate: the
     * row is `fillMaxWidth`, so its own bounds span the sheet either way and only the **text's**
     * placement inside it moves. The node measured is the heading, which is padded from the leading
     * edge and therefore has a side.
     */
    @Test
    fun theInfoSheetMirrorsWithLayoutDirection() {
        shellWithInfoOpen(width = COMPACT_WIDTH)

        val ltrHeading = rule.onNodeWithText(BACKLINKS).getUnclippedBoundsInRoot()
        val ltrCentre = COMPACT_WIDTH * 0.5f
        println("DUCK rtl: LTR sheetHeading=$ltrHeading centre=$ltrCentre")

        flipToRtl()
        val rtlHeading = rule.onNodeWithText(BACKLINKS).getUnclippedBoundsInRoot()
        println("DUCK rtl: RTL sheetHeading=$rtlHeading centre=$ltrCentre")

        assertEquals(
            "the sheet's rows must lead with reading direction: the heading starts in the left " +
                "half in LTR ($ltrHeading), the right half in RTL ($rtlHeading), against a " +
                "$COMPACT_WIDTH sheet whose centre is $ltrCentre. ModalBottomSheet inherits " +
                "LocalLayoutDirection; a hardcoded Ltr inside it would pin the text left in both.",
            HeadingLeadingLtr(true) to HeadingLeadingRtl(true),
            HeadingLeadingLtr(ltrHeading.left < ltrCentre) to
                HeadingLeadingRtl(rtlHeading.right > ltrCentre),
        )
    }

    /**
     * The real [DuckShell] at [width], with a note open and the backlinks panel already showing.
     *
     * `infoOpen` is passed in the state rather than reached by clicking the toolbar button, because
     * these tests drive `DuckShell` with an inert [ShellActions] — `onToggleInfo` would do nothing.
     * Focus mode's test can click, since focus is `remember`ed inside the shell; this is not.
     *
     * One backlink is seeded so the list branch renders rather than the empty state. Both branches
     * draw the same heading, which is the node measured, but the populated one is the real case.
     *
     * **`requiredSize`, not `size`, and that distinction cost a red.** `Modifier.size` is a
     * *preferred* size and is clamped by the incoming constraints — on a 411dp emulator a
     * `size(900.dp, …)` box measures 411dp wide, `paneModeFor` returns Compact, and the Expanded test
     * silently measures the **bottom sheet** instead of the side pane. It failed rather than passing
     * falsely, but only by luck of which way the numbers fell. `requiredSize` overrides the incoming
     * constraints, so the pane mode under test is the one asked for.
     */
    private fun shellWithInfoOpen(width: Dp) {
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                DuckTheme {
                    Box(Modifier.requiredSize(width, 800.dp)) {
                        DuckShell(
                            state = ShellUiState(
                                notes = listOf(EXIT_NOTE),
                                selectedNoteId = EXIT_NOTE.id,
                                selectedNoteBody = EXIT_NOTE.body,
                                selectedNoteBacklinks = listOf(SAMPLE_BACKLINK),
                                infoOpen = true,
                            ),
                            buffer = TextFieldState(EXIT_NOTE.body),
                            actions = ShellActions(),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** Named wrappers so failures read `GutterAfterText(false)`, not a bare `false`. */
    private data class GutterBeforeText(val value: Boolean)

    private data class GutterAfterText(val value: Boolean)

    private data class InsideLtr(val value: Boolean)

    private data class InsideRtl(val value: Boolean)

    private data class AddBeforeThumb(val value: Boolean)

    private data class AddAfterThumb(val value: Boolean)

    private data class ExitTrailingLtr(val value: Boolean)

    private data class ExitLeadingRtl(val value: Boolean)

    private data class PaneTrailingLtr(val value: Boolean)

    private data class PaneLeadingRtl(val value: Boolean)

    private data class HeadingLeadingLtr(val value: Boolean)

    private data class HeadingLeadingRtl(val value: Boolean)

    private companion object {
        /** "Hide <title> section" — the collapse label, with the Arabic heading as its argument. */
        const val HIDE_SECTION = "Hide ملاحظات section"

        /** The task's own text becomes its contentDescription. */
        const val TASK = "حليب"

        /** The strip's two contentDescriptions, from `strings.xml`. */
        const val ADD_IMAGE = "Add image"

        const val ATTACHMENT = "Attachment"

        /** Focus-mode toggle contentDescriptions, from `strings.xml`. */
        const val ENTER_FOCUS = "Focus mode"

        const val EXIT_FOCUS = "Exit focus mode"

        /** A minimal open note for the exit-icon case; its body only has to make the field measurable. */
        val EXIT_NOTE = Note(id = "n1", title = "note", body = "text here", createdAt = 0L, modifiedAt = 1L)

        /** The backlinks panel's heading and its toolbar button, from `strings.xml`. */
        const val BACKLINKS = "Backlinks"

        /** One incoming link, so the panel renders its list branch rather than the empty state. */
        val SAMPLE_BACKLINK =
            NoteLink(sourceId = "n0", targetId = "n1", linkText = "note", sourceTitle = "Journal")

        /** Either side of `paneModeFor`'s 840dp and 600dp breakpoints. */
        val EXPANDED_WIDTH = 900.dp

        val COMPACT_WIDTH = 400.dp

        /** Bogus uri on purpose — the tile is fixed-size, so it lays out without a bitmap. */
        val SAMPLE_ATTACHMENT = Attachment(
            id = "a1",
            noteId = "n1",
            uri = "/nope/a1",
            mime = "image/png",
            createdAt = 0L,
        )

        /**
         * Arabic, with a foldable section (a heading that has a body) and a task line.
         *
         * The caret lands at the end, inside the last block, which is why the folded section under
         * test is the first one — same fixture rule as `SectionFoldUiTest`.
         */
        val NOTE = """
            # ملاحظات

            نص عربي هنا

            - [ ] حليب

            ذيل
        """.trimIndent()
    }
}
