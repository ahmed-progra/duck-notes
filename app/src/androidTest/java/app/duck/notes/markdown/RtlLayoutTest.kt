package app.duck.notes.markdown

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.Attachment
import app.duck.notes.presentation.markdown.MarkdownEditorScreen
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

    /** Named wrappers so failures read `GutterAfterText(false)`, not a bare `false`. */
    private data class GutterBeforeText(val value: Boolean)

    private data class GutterAfterText(val value: Boolean)

    private data class InsideLtr(val value: Boolean)

    private data class InsideRtl(val value: Boolean)

    private data class AddBeforeThumb(val value: Boolean)

    private data class AddAfterThumb(val value: Boolean)

    private companion object {
        /** "Hide <title> section" — the collapse label, with the Arabic heading as its argument. */
        const val HIDE_SECTION = "Hide ملاحظات section"

        /** The task's own text becomes its contentDescription. */
        const val TASK = "حليب"

        /** The strip's two contentDescriptions, from `strings.xml`. */
        const val ADD_IMAGE = "Add image"

        const val ATTACHMENT = "Attachment"

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
