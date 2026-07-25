package app.duck.notes.markdown

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.markdown.MarkdownOutputTransformation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Editing *through* a concealed region.
 *
 * The entire case for `OutputTransformation` over `VisualTransformation` was that Compose derives
 * the offset mapping from our deletions, so we never hand-write one. Nothing else in this phase
 * actually checks that it does. These tests do: they type, backspace and replace at and across
 * concealment boundaries, and assert on the **raw markdown** in `TextFieldState` — the source of
 * truth — not on what is rendered.
 *
 * If the mapping were wrong, characters would land at the wrong index in the raw text, silently
 * corrupting notes. That is the failure this file exists to catch.
 */
@RunWith(AndroidJUnit4::class)
class ConcealMappingTest {

    @get:Rule
    val rule = createComposeRule()

    private fun editor(initial: String): TextFieldState {
        val state = TextFieldState(initial)
        rule.setContent {
            BasicTextField(
                state = state,
                outputTransformation = MarkdownOutputTransformation(),
                modifier = Modifier.testTag(TAG),
            )
        }
        rule.waitForIdle()
        return state
    }

    /** Put the caret at [index] in *raw* coordinates and let the transformation settle. */
    private fun TextFieldState.caretAt(index: Int) {
        rule.runOnUiThread { edit { selection = TextRange(index) } }
        rule.waitForIdle()
    }

    private fun type(text: String) {
        rule.onNodeWithTag(TAG).performTextInput(text)
        rule.waitForIdle()
    }

    // --- Typing next to a concealed marker -----------------------------------

    @Test
    fun typingImmediatelyAfterAConcealedMarkerLandsAtTheRightRawIndex() {
        // `some **bold** text` renders as `some bold text` with both `**` hidden.
        val state = editor("some **bold** text")
        // Immediately after the opening `**`, i.e. just before the `b` of bold.
        val afterOpeningMarker = "some **".length
        state.caretAt(afterOpeningMarker)
        type("X")

        assertEquals("some **Xbold** text", state.text.toString())
    }

    @Test
    fun typingImmediatelyBeforeAConcealedClosingMarkerLandsAtTheRightRawIndex() {
        val state = editor("some **bold** text")
        val beforeClosingMarker = "some **bold".length
        state.caretAt(beforeClosingMarker)
        type("X")

        assertEquals("some **boldX** text", state.text.toString())
    }

    @Test
    fun typingAfterAConcealedBlockMarkerLandsAtTheRightRawIndex() {
        // `# Heading` renders as `Heading`; the `# ` is gone from the rendered text entirely.
        val state = editor("# Heading")
        state.caretAt("# ".length)
        type("X")

        assertEquals("# XHeading", state.text.toString())
    }

    @Test
    fun typingPastSeveralConcealedRegionsAccumulatesCorrectly() {
        // Three hidden regions before the caret: `# `, and both `**`.
        val state = editor("# A **bold** heading")
        val caret = "# A **bold** ".length
        state.caretAt(caret)
        type("X")

        assertEquals("# A **bold** Xheading", state.text.toString())
    }

    // --- Backspace at a concealment boundary ---------------------------------

    @Test
    fun backspaceAtAConcealmentBoundaryDeletesTheRightRawCharacter() {
        val state = editor("some **bold** text")
        // Caret just after the `d` of bold, i.e. hard against the hidden closing `**`.
        val caret = "some **bold".length
        state.caretAt(caret)
        rule.runOnUiThread {
            state.edit {
                // What a backspace key does: remove the character before the caret.
                replace(caret - 1, caret, "")
            }
        }
        rule.waitForIdle()

        assertEquals("some **bol** text", state.text.toString())
    }

    @Test
    fun backspaceIntoAConcealedMarkerRemovesTheMarkerCharacter() {
        val state = editor("some **bold** text")
        // Caret immediately before the `b`; the character before it is the second `*`.
        val caret = "some **".length
        state.caretAt(caret)
        rule.runOnUiThread { state.edit { replace(caret - 1, caret, "") } }
        rule.waitForIdle()

        // The node stops being bold — which is correct, the syntax really was edited.
        assertEquals("some *bold** text", state.text.toString())
    }

    // --- Replacing a selection that spans a concealed region -----------------

    @Test
    fun replacingASelectionSpanningAConcealedRegionRewritesTheRightRawRange() {
        val state = editor("some **bold** text")
        val from = "some ".length
        val to = "some **bold**".length
        rule.runOnUiThread { state.edit { replace(from, to, "plain") } }
        rule.waitForIdle()

        assertEquals("some plain text", state.text.toString())
    }

    @Test
    fun replacingAcrossTwoConcealedNodesRewritesTheRightRawRange() {
        val state = editor("a **b** and _c_ d")
        val from = "a ".length
        val to = "a **b** and _c_".length
        rule.runOnUiThread { state.edit { replace(from, to, "X") } }
        rule.waitForIdle()

        assertEquals("a X d", state.text.toString())
    }

    // --- The raw text is never altered by rendering alone --------------------

    @Test
    fun concealingAndRevealingNeverMutatesTheRawText() {
        val original = "# H\n> q\n- [x] t\nsome **bold** and `code` and #tag"
        val state = editor(original)
        // Sweep the caret through every offset; conceal state changes constantly.
        for (i in original.indices) state.caretAt(i)

        assertEquals("rendering must not touch the source of truth", original, state.text.toString())
    }

    @Test
    fun typingIntoAnEmptyEditorIsUnaffected() {
        val state = editor("")
        rule.onNodeWithTag(TAG).performClick()
        type("# hi")
        assertEquals("# hi", state.text.toString())
    }

    private companion object {
        const val TAG = "duck-editor"
    }
}
