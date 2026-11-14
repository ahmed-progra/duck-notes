package app.duck.notes.presentation.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.model.Note
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals

/**
 * Focus mode hides the four chrome elements and brings them back — asserted against the **semantics
 * tree**, not visibility.
 *
 * `assertDoesNotExist`, deliberately, not `assertIsNotDisplayed`: the whole contract is that the
 * chrome is *not rendered*, not rendered-at-alpha-0. An alpha-0 node is still in the tree and still
 * intercepts nothing-you-can-see; the plant that wraps a hidden element in `Modifier.alpha(0f)` is
 * caught here precisely because these assertions read the tree.
 *
 * Compact width so all four elements are present at once — the sidebar button exists only under a
 * `Scaffold`, which only Compact routes through (`ShellStateTest` pins `paneModeFor`). The real
 * stateless [DuckShell] is driven, and the toggle goes through the real enter button and the real
 * persistent exit icon.
 */
@RunWith(AndroidJUnit4::class)
class FocusModeUiTest {

    @get:Rule val compose = createComposeRule()

    private companion object {
        val COMPACT_WIDTH = 400.dp
        val COMPACT_HEIGHT = 800.dp

        const val TAG = "focusfixturetag"
        const val BODY = "first paragraph\n\nsecond paragraph"

        // contentDescriptions / text already in the tree, reused rather than new testTags.
        const val FORMAT_BOLD = "Bold"
        const val ADD_IMAGE = "Add image"
        const val OPEN_SIDEBAR = "Open sidebar"
        const val ENTER_FOCUS = "Focus mode"
        const val EXIT_FOCUS = "Exit focus mode"

        val NOTE = Note(id = "n1", title = "Fixture", body = BODY, createdAt = 0L, modifiedAt = 1L)
        val ATTACHMENT = Attachment(id = "a1", noteId = "n1", uri = "/nope/a1", mime = "image/png", createdAt = 0L)
    }

    private fun setShell(width: androidx.compose.ui.unit.Dp = COMPACT_WIDTH) {
        compose.setContent {
            DuckTheme {
                Box(Modifier.size(width, COMPACT_HEIGHT)) {
                    DuckShell(
                        state = ShellUiState(
                            notes = listOf(NOTE),
                            selectedNoteId = NOTE.id,
                            selectedNoteBody = BODY,
                            selectedNoteTags = listOf(TAG),
                            selectedNoteAttachments = listOf(ATTACHMENT),
                        ),
                        buffer = TextFieldState(BODY),
                        actions = ShellActions(),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun setLongShell(width: () -> androidx.compose.ui.unit.Dp) {
        val body = (1..200).joinToString("\n") { "Research paragraph $it." }
        val note = NOTE.copy(body = body)
        val buffer = TextFieldState(body)
        compose.setContent { DuckTheme { Box(Modifier.size(width(), COMPACT_HEIGHT)) {
            DuckShell(state = ShellUiState(notes = listOf(note), selectedNoteId = note.id, selectedNoteBody = body), buffer = buffer, actions = ShellActions())
        } } }
        compose.onNodeWithTag("noteEditorViewport").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 500f) }
        compose.waitUntil(5_000) { editorScroll() > 400f }
    }

    private fun editorScroll() = compose.onNodeWithTag("noteEditorViewport").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** Plant: let the editor own a new scroll state when Focus changes its composition position. */
    @Test fun enteringAndLeavingFocusKeepsTheActualEditorScrollPosition() {
        setLongShell { COMPACT_WIDTH }
        val before = editorScroll()
        compose.onNodeWithContentDescription(ENTER_FOCUS).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Entering Focus lost the writing position", before, editorScroll(), 1f)
        compose.onNodeWithContentDescription(EXIT_FOCUS).performClick()
        compose.waitForIdle()
        assertEquals("Leaving Focus lost the writing position", before, editorScroll(), 1f)
    }

    /** Plant: recreate scroll state when the editor moves between phone and wide navigation. */
    @Test fun resizingBetweenPhoneAndWideLayoutsKeepsTheActualEditorScrollPosition() {
        val width = mutableStateOf(COMPACT_WIDTH)
        setLongShell { width.value }
        val before = editorScroll()
        compose.runOnIdle { width.value = 1000.dp }
        compose.waitForIdle()
        assertEquals("Widening lost the writing position", before, editorScroll(), 1f)
        compose.runOnIdle { width.value = COMPACT_WIDTH }
        compose.waitForIdle()
        assertEquals("Narrowing lost the writing position", before, editorScroll(), 1f)
    }

    /** Plant: remove the Medium sidebar button; this click must become unavailable. */
    @Test fun mediumLayoutProvidesVisibleSidebarNavigation() {
        setShell(800.dp)
        compose.onNodeWithContentDescription(OPEN_SIDEBAR).assertIsDisplayed().performClick()
        compose.onNodeWithText("Untagged").assertIsDisplayed()
    }

    /** Plant: render normal wide panes while focusing; Notes remains in the tree. */
    @Test fun wideFocusHidesTheListAndRestoresIt() {
        setShell(1000.dp)
        compose.onNodeWithText("Fixture").assertExists()
        compose.onNodeWithContentDescription(ENTER_FOCUS).performScrollTo().performClick()
        compose.onNodeWithText("Fixture").assertDoesNotExist()
        compose.onNodeWithContentDescription(EXIT_FOCUS).performClick()
        compose.onNodeWithText("Fixture").assertExists()
    }

    @Test
    fun focusModeHidesEveryChromeElementFromTheTreeAndBringsItBack() {
        setShell()

        // All four present before focus mode, plus the enter button. The toolbar scrolls at 400dp, so
        // the trailing focus button exists in the tree but may be scrolled off — assertExists, not
        // assertIsDisplayed, and scroll to it before the tap.
        compose.onNodeWithContentDescription(FORMAT_BOLD).assertExists()
        compose.onNodeWithContentDescription(ADD_IMAGE).assertExists()
        compose.onNodeWithText(TAG, substring = true).assertExists()
        compose.onNodeWithContentDescription(OPEN_SIDEBAR).assertExists()
        compose.onNodeWithContentDescription(ENTER_FOCUS).assertExists()

        compose.onNodeWithContentDescription(ENTER_FOCUS).performScrollTo().performClick()
        compose.waitForIdle()

        // Gone from the tree — not merely invisible.
        compose.onNodeWithContentDescription(FORMAT_BOLD).assertDoesNotExist()
        compose.onNodeWithContentDescription(ADD_IMAGE).assertDoesNotExist()
        compose.onNodeWithText(TAG, substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription(OPEN_SIDEBAR).assertDoesNotExist()
        // The one control that survives: the way out.
        compose.onNodeWithContentDescription(EXIT_FOCUS).assertIsDisplayed()

        compose.onNodeWithContentDescription(EXIT_FOCUS).performClick()
        compose.waitForIdle()

        // Everything is back.
        compose.onNodeWithContentDescription(FORMAT_BOLD).assertExists()
        compose.onNodeWithContentDescription(ADD_IMAGE).assertExists()
        compose.onNodeWithText(TAG, substring = true).assertExists()
        compose.onNodeWithContentDescription(OPEN_SIDEBAR).assertExists()
        compose.onNodeWithContentDescription(EXIT_FOCUS).assertDoesNotExist()
    }
}
