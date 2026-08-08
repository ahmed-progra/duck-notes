package app.duck.notes.presentation.markdown

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The strip's two gestures — the whole of its behaviour that Compose owns, since the markdown side is
 * `MarkdownFormatTest`'s and the wiring `DuckShellViewModelTest`'s.
 *
 * Tap-the-add-tile and long-press-a-thumbnail are the two the ViewModel can't see: they turn into
 * `onAddImage` / `onDeleteAttachment` here. The thumbnail's own uri is deliberately bogus — no bitmap
 * loads — because a tile is `.size(TILE)` and so has bounds and semantics whether or not Coil decodes
 * anything, which is exactly what lets this run with no fixture image on disk.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentStripUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val sample =
        Attachment(id = "a1", noteId = "n1", uri = "/nope/a1", mime = "image/png", createdAt = 0L)

    @Test
    fun theAddTileClickFiresOnAddImage() {
        var added = 0
        rule.setContent {
            DuckTheme {
                AttachmentStrip(emptyList(), onAddImage = { added++ }, onDeleteAttachment = {})
            }
        }
        rule.onNodeWithContentDescription(ADD_IMAGE).performClick()
        assertEquals(1, added)
    }

    @Test
    fun aThumbnailLongPressFiresOnDeleteWithThatAttachment() {
        var deleted: Attachment? = null
        rule.setContent {
            DuckTheme {
                AttachmentStrip(listOf(sample), onAddImage = {}, onDeleteAttachment = { deleted = it })
            }
        }
        rule.onNodeWithContentDescription(ATTACHMENT).performTouchInput { longClick() }
        assertEquals(sample, deleted)
    }

    /**
     * Positive control: with one attachment both tiles are on screen. Without it the two gesture tests
     * could pass against a strip that happened to render only the node each one drives.
     */
    @Test
    fun theAddTileAndTheThumbnailAreBothShown() {
        var added = 0
        var deleted: Attachment? = null
        rule.setContent {
            DuckTheme {
                AttachmentStrip(listOf(sample), onAddImage = { added++ }, onDeleteAttachment = { deleted = it })
            }
        }
        rule.onNodeWithContentDescription(ADD_IMAGE).assertIsDisplayed()
        rule.onNodeWithContentDescription(ATTACHMENT).assertIsDisplayed()
        // Nothing fires without a gesture.
        assertTrue(added == 0 && deleted == null)
    }

    private companion object {
        const val ADD_IMAGE = "Add image"
        const val ATTACHMENT = "Attachment"
    }
}
