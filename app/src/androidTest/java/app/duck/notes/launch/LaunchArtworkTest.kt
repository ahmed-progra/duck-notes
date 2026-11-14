package app.duck.notes.launch

import android.graphics.Bitmap
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.Note
import app.duck.notes.presentation.shell.DuckShell
import app.duck.notes.presentation.shell.ShellActions
import app.duck.notes.presentation.shell.ShellUiState
import app.duck.notes.presentation.shell.TagRow
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Visual fixture of real production composables, not a security or persistence guard. */
class LaunchArtworkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureSyntheticResearchNotes() = capture(dark = false)
    @Test fun captureSyntheticResearchNotesInDarkTheme() = capture(dark = true)

    private fun capture(dark: Boolean) {
        val body = """
            # Research journal

            Ideas become clearer when they connect.

            ## Questions worth keeping
            - [x] Review the reading list
            - [ ] Connect observations to sources
            - [ ] Write the next draft

            ## A connected workspace
            Follow [[Reading list]] to revisit a source.
            Keep related ideas under #research/notes.

            **Write thoughtfully. Keep your notes close.**
        """.trimIndent()
        val notes = listOf(
            Note("research", "Research journal", body, 1_794_528_000_000L, 1_794_528_000_000L, isPinned = true),
            Note("reading", "Reading list", "Sources, questions, and useful connections.", 1_794_528_000_000L, 1_794_528_000_000L),
            Note("ideas", "Working ideas", "Small observations for the next draft.", 1_794_528_000_000L, 1_794_528_000_000L),
        )
        compose.setContent {
            DuckTheme(dark = dark) {
                DuckShell(
                    state = ShellUiState(notes = notes, selectedNoteId = "research",
                        selectedNoteBody = body, selectedNoteTags = listOf("research/notes"),
                        tagRows = listOf(
                            TagRow("research", "research", 0, true, true, null, false, count = 1),
                            TagRow("research/notes", "notes", 1, false, false, "research/notes", false,
                                count = 1, ownCount = 1),
                        )),
                    buffer = rememberTextFieldState(body, initialSelection = androidx.compose.ui.text.TextRange(body.indexOf("\n\n") + 1)),
                    actions = ShellActions(onPinNote = { _, _ -> }, onNoteSort = {},
                        onSearchFilters = {}, onImport = {}, onExport = { _, _ -> }),
                )
            }
        }
        compose.waitForIdle()
        assertTrue("The production shell must render the research note",
            compose.onAllNodesWithText("Research journal", substring = true).fetchSemanticsNodes().isNotEmpty())
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("Actual Compose capture must not be empty", image.width > 0 && image.height > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, if (dark) "launch-editor-dark.png" else "launch-editor.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
