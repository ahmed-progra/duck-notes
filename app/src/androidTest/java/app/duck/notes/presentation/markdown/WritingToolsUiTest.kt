package app.duck.notes.presentation.markdown

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WritingToolsUiTest {
    @get:Rule val rule = createComposeRule()

    /** Plant: bypass undoState.undo in the production toolbar; formatted text remains. */
    @Test fun typingAndFormattingUndoAndRedoThroughTheRealEditor() {
        val state = TextFieldState()
        rule.setContent { DuckTheme { MarkdownEditorScreen(state) } }
        rule.onNodeWithTag("noteEditor").performTextInput("alpha beta alpha")
        rule.onNodeWithTag("noteEditor").performTextInputSelection(TextRange(0, 5))
        rule.onNodeWithContentDescription("Bold").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("**alpha** beta alpha", state.text.toString()) }
        rule.onNodeWithContentDescription("Undo edit").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("alpha beta alpha", state.text.toString()) }
        rule.onNodeWithContentDescription("Redo edit").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("**alpha** beta alpha", state.text.toString()) }
    }

    /** Plant: clear history after production Replace All; one undo can no longer recover the source. */
    @Test fun replaceAllIsOneUndoableEdit() {
        val state = TextFieldState("alpha beta alpha")
        rule.setContent { DuckTheme { MarkdownEditorScreen(state) } }
        rule.onNodeWithContentDescription("More formatting").performScrollTo().performClick()
        rule.onNodeWithText("Find in note").performClick()
        rule.onNodeWithTag("findQuery").performTextInput("alpha")
        rule.onNodeWithTag("replaceQuery").performTextInput("omega")
        rule.waitUntil(5000) { rule.onAllNodes(androidx.compose.ui.test.hasText("1 / 2")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Replace all").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("omega beta omega", state.text.toString()) }
        rule.onNodeWithContentDescription("Undo edit").performScrollTo().performClick()
        rule.runOnIdle { assertEquals("alpha beta alpha", state.text.toString()) }
    }
}
