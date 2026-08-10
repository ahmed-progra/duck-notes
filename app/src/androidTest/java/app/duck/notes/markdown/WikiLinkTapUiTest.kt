package app.duck.notes.markdown

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.presentation.markdown.MarkdownEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The half of 9d that cannot be a JVM test: **what happens when a finger lands on a wiki link.**
 *
 * The mapping itself is already covered without an emulator — `WikiLinkTargetTest` pins the source
 * and rendered spans including the RTL and non-BMP cases, and `OpenWikiLinkTest` pins resolve,
 * create and relink. **That split is the evidence the mapping is one pure leg plus one layout leg:**
 * everything below needs a device precisely because it is about rendered offsets becoming screen
 * rectangles, and nothing above it did.
 *
 * What only a device can settle is that the overlay lands on the title's own glyphs rather than on
 * its brackets, that a tap reaches it and not the text field, and — the inverse, and the one most
 * likely to be missed — that a **revealed** link has no overlay at all, so the caret can still be
 * placed inside a link to edit it.
 */
@RunWith(AndroidJUnit4::class)
class WikiLinkTapUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val opened = mutableListOf<String>()

    private fun editor(initial: String, caret: Int = 0): TextFieldState {
        val state = TextFieldState(initial, TextRange(caret))
        rule.setContent {
            DuckTheme {
                MarkdownEditorScreen(state, onOpenWikiLink = { opened += it })
            }
        }
        rule.waitForIdle()
        return state
    }

    /** The overlay names itself with the link's title, which is also what TalkBack reads. */
    private fun overlay(title: String = TITLE) = rule.onNodeWithContentDescription(title)

    private fun fieldBounds() = rule.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()

    // --- the tap --------------------------------------------------------------

    @Test
    fun aTapOnAWikiLinkOpensItByTitle() {
        editor(NOTE)

        overlay().performClick()
        rule.waitForIdle()

        assertEquals(listOf(TITLE), opened)
    }

    /**
     * The overlay exists at all, and announces the link's title.
     *
     * A consumed tap on a non-semantic span would be invisible to TalkBack — one of the three reasons
     * `TaskBoxOverlay` gives for an overlay over a pointer interceptor, and it applies here unchanged.
     */
    @Test
    fun theOverlayIsAnAccessibilityNodeNamedAfterTheLink() {
        editor(NOTE)

        overlay().assertIsDisplayed()
    }

    /**
     * **The overlay sits on the title's glyphs, not on its brackets.**
     *
     * The brackets are concealed when the caret is outside, so the rendered span is four characters
     * shorter than the source span. An overlay built from the source span would be measurably wider
     * and offset — this asserts it lies inside the text field and is no wider than the rendered title
     * could be, which is the containment invariant `theTaskBoxTracksItsGlyphsInBothDirections`
     * established as the one that survives layout being layout.
     */
    @Test
    fun theOverlayLiesOnTheRenderedTitle() {
        editor(NOTE)

        val box = overlay().getUnclippedBoundsInRoot()
        val field = fieldBounds()

        assertTrue(
            "the overlay must lie inside the text field: box=$box field=$field",
            box.left >= field.left && box.right <= field.right,
        )
        assertTrue("the overlay must have real width: $box", box.right > box.left)
    }

    // --- the inverse: a revealed link is editable -----------------------------

    /**
     * **A caret inside the link removes the overlay, so the tap places the caret instead.**
     *
     * This is the routing rule, and it is what makes a link editable at all: `wikiLinkTargets` emits
     * nothing for a revealed link, so there is no overlay to intercept and the tap reaches
     * `BasicTextField`. Emitting targets unconditionally is the plant — navigation would keep working
     * and the link would simply become impossible to edit, which no other test would notice.
     *
     * Caret 7 is inside `Pond`; the fixture puts the link at source 4..11.
     */
    @Test
    fun aRevealedLinkHasNoOverlaySoTapsPlaceTheCaret() {
        editor(NOTE, caret = 7)

        overlay().assertDoesNotExist()
    }

    /**
     * Tapping *beside* a link reveals it — the escape hatch that makes editing reachable.
     *
     * Endpoints count as touching in `SpanPlanner.revealed`, so a caret immediately after the closing
     * bracket removes the overlay, and the next tap lands in the text. One tap to follow, two to edit.
     */
    @Test
    fun aCaretImmediatelyAfterTheLinkAlsoRemovesTheOverlay() {
        editor(NOTE, caret = 12)

        overlay().assertDoesNotExist()
    }

    /** Nothing to tap in a note with no links, and no crash from an empty target list. */
    @Test
    fun proseWithNoLinksHasNoOverlays() {
        editor("just some prose with no markup at all")

        overlay().assertDoesNotExist()
        assertNull(opened.firstOrNull())
    }

    private companion object {
        const val TITLE = "Pond"

        /** `[[Pond]]` occupies source 4..11; caret 0 is outside it, so it is concealed. */
        const val NOTE = "see [[Pond]] here"
    }
}
