package app.duck.notes.presentation.shell

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.R
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.model.Note
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real shell/editor semantics and layout; callbacks only hoist state, so no Room claim is made.
 * The width sweep requires a visible host of at least 1120 x 800dp. Clipped fixtures fail explicitly.
 * Images are placeholders: decoding and storage are covered by the native attachment tests.
 * Named production plants are specified below; this source does not claim they have run.
 */
@RunWith(AndroidJUnit4::class)
class OfflineEditorAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var originalConfiguration: Configuration? = null
    private var originalDirection = android.view.View.LAYOUT_DIRECTION_LTR

    @Suppress("DEPRECATION")
    private fun configureHost(arabic: Boolean, fontScale: Float) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = rule.activity
            originalConfiguration = Configuration(activity.resources.configuration)
            originalDirection = activity.window.decorView.layoutDirection
            val locale = Locale.forLanguageTag(if (arabic) "ar" else "en")
            val configuration = Configuration(activity.resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
                this.fontScale = fontScale
            }
            // Popups create their own composition from the actual Activity/View context.
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            activity.window.decorView.layoutDirection = configuration.layoutDirection
            assertEquals(if (arabic) ARABIC.restore else ENGLISH.restore, activity.getString(R.string.note_restore))
        }
    }

    @After @Suppress("DEPRECATION") fun restoreHostConfiguration() {
        val configuration = originalConfiguration ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = rule.activity
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
            activity.window.decorView.layoutDirection = originalDirection
        }
    }

    @Test
    fun trashActionsAreDisabledAndRestoreReenablesThemInEnglish() {
        assertTrashAndRestore(arabic = false)
    }

    @Test
    fun arabicTrashLabelsAndActionsWorkAtTwiceTheFontScale() {
        assertTrashAndRestore(arabic = true)
    }

    /**
     * TASK_TRASH_ENABLED: TaskBoxOverlay's toggleable enabled argument becomes true.
     * ATTACHMENT_TRASH_LONG_CLICK: Thumbnail's conditional onLongClick becomes unconditional.
     * SHELL_TRASH_EDITABLE: DuckShell passes canEdit = { true } to MarkdownEditorScreen.
     * ATTACHMENT_ZERO_SIZE: AttachmentStrip's TILE becomes 0.dp; the visible touch target disappears.
     * ARABIC_RESTORE_LABEL: values-ar note_restore becomes the literal English "Restore".
     * ATTACHMENT_DELETE_LABEL_MISSING: remove Thumbnail's onLongClickLabel argument.
     */
    private fun assertTrashAndRestore(arabic: Boolean) {
        val labels = if (arabic) ARABIC else ENGLISH
        val session = EditorSession(NOTE.copy(isTrashed = true))
        var trashed by mutableStateOf(true)
        var restored = 0
        var deleted: String? = null
        configureHost(arabic, if (arabic) 2f else 1f)
        rule.setContent {
            EditorHost(360.dp) {
                DuckShell(
                    state = ShellUiState(
                        selectedNoteId = NOTE.id,
                        selectedNoteBody = NOTE.body,
                        editorSession = session,
                        selectedNoteTrashed = trashed,
                        selectedNoteAttachments = listOf(ATTACHMENT),
                    ),
                    buffer = session.buffer,
                    actions = ShellActions(
                        onArchiveNote = {},
                        onRestoreNote = {
                            restored++
                            session.readOnly = false
                            trashed = false
                        },
                        onDeleteAttachment = { deleted = it },
                    ),
                )
            }
        }
        assertFixtureFits(360.dp)
        rule.onNodeWithText(labels.readOnly).assertIsDisplayed()
        val task = rule.onNodeWithContentDescription(TASK)
        task.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertIsOff().assertIsNotEnabled()
        // Accessibility actions can be invoked directly; the editor must still refuse the edit.
        task.performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.runOnIdle { assertEquals(NOTE.body, session.buffer.text.toString()) }
        rule.onNodeWithContentDescription(labels.addImage).assertDoesNotExist()
        val image = rule.onNodeWithContentDescription(labels.attachment)
        image.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
        assertTouchTarget(image, "read-only attachment")
        assertTouchTarget(rule.onNodeWithText(labels.actions), "note actions")

        rule.onNodeWithText(labels.actions).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty() }
        val restore = rule.onNode(hasText(labels.restore) and hasAnyAncestor(isPopup()))
        assertTouchTarget(restore, "restore")
        restore.performClick()
        rule.runOnIdle {
            assertEquals(1, restored)
            assertEquals(null, deleted)
            session.buffer.edit { selection = TextRange(0) }
        }
        rule.onNodeWithText(labels.readOnly).assertDoesNotExist()
        task.assertIsEnabled().assertIsOff().performClick()
        task.assertIsOn()
        rule.runOnIdle {
            assertEquals(NOTE.body.replace("- [ ]", "- [x]"), session.buffer.text.toString())
        }
        assertTouchTarget(rule.onNodeWithContentDescription(labels.addImage), "restored add image")
        image.assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        val expectedDelete = if (arabic) "\u062d\u0630\u0641 \u0627\u0644\u0635\u0648\u0631\u0629" else "Delete image"
        assertEquals("Attachment deletion must expose its localized accessibility action label", expectedDelete,
            image.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        image.performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        rule.runOnIdle { assertEquals(ATTACHMENT.id, deleted) }
    }

    @Test
    fun manyTagsPreserveTheEditorAtCompactMediumAndExpandedBoundaries() {
        assertManyTags(arabic = false, widths = listOf(599, 600, 839, 840))
    }

    @Test
    fun manyArabicTagsPreserveTheEditorAtTwiceTheFontScale() {
        assertManyTags(arabic = true, widths = listOf(360, 600, 840, 1119, 1120))
    }

    /**
     * TAG_ROW_VERTICAL_PADDING_400: EditorTagRow's existing vertical padding becomes 400.dp.
     * EDITOR_TAG_ACTION_DROPPED: its existing onClick lambda becomes {}.
     * Restoring the previous wrapping FlowRow also fails the height/scroll checks, but needs a
     * structural edit (LazyRow items DSL cannot be swapped to FlowRow with one identifier).
     */
    private fun assertManyTags(arabic: Boolean, widths: List<Int>) {
        val labels = if (arabic) ARABIC else ENGLISH
        val tags = (1..40).map { if (arabic) "\u0648\u0633\u0645$it" else "tag-$it" }
        val session = EditorSession(NOTE)
        var width by mutableStateOf(widths.first().dp)
        var shownTags by mutableStateOf(tags.take(1))
        var clicked: ShellFilter? = null
        configureHost(arabic, if (arabic) 2f else 1f)
        rule.setContent {
            EditorHost(width) {
                DuckShell(
                    state = ShellUiState(
                        notes = listOf(NOTE),
                        selectedNoteId = NOTE.id,
                        selectedNoteBody = NOTE.body,
                        editorSession = session,
                        selectedNoteTags = shownTags,
                    ),
                    buffer = session.buffer,
                    actions = ShellActions(onArchiveNote = {}, onFilter = { clicked = it }),
                )
            }
        }
        for (literalWidth in widths) {
            rule.runOnIdle {
                width = literalWidth.dp
                shownTags = tags.take(1)
                clicked = null
            }
            assertFixtureFits(literalWidth.dp)
            val editor = rule.onNodeWithTag("noteEditorViewport").assertIsDisplayed()
            val before = editor.getUnclippedBoundsInRoot()
            assertTrue("EDITOR_AREA before tags at $literalWidth dp: $before", before.height >= 160.dp)
            rule.runOnIdle { shownTags = tags }
            val after = editor.assertIsDisplayed().getUnclippedBoundsInRoot()
            assertEquals(
                "TAG_ROW_BOUNDED at $literalWidth dp: forty tags must preserve editor height",
                before.height.value,
                after.height.value,
                1f,
            )
            assertTrue("EDITOR_AREA after tags at $literalWidth dp: $after", after.height >= 160.dp)
            assertTouchTarget(rule.onNodeWithText(labels.actions), "note actions at $literalWidth dp")
            rule.onNodeWithText(tags.first()).assertIsDisplayed()
            rule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(tags.first())))
                .performScrollToIndex(39)
            val last = rule.onNodeWithText(tags.last())
            assertTouchTarget(last, "last tag at $literalWidth dp")
            last.performClick()
            rule.runOnIdle { assertEquals(ShellFilter.Tag(tags.last()), clicked) }
            println("DUCK offline tags: width=$literalWidth arabic=$arabic editor=$after")
        }
    }

    @Composable
    private fun EditorHost(width: Dp, content: @Composable () -> Unit) {
        DuckTheme {
            Box(Modifier.fillMaxSize().testTag("offlineTestHost"), contentAlignment = Alignment.TopStart) {
                Box(Modifier.requiredSize(width, 800.dp).testTag("offlineTestViewport")) { content() }
            }
        }
    }

    private fun assertFixtureFits(width: Dp) {
        rule.waitForIdle()
        val host = rule.onNodeWithTag("offlineTestHost").getUnclippedBoundsInRoot()
        val viewport = rule.onNodeWithTag("offlineTestViewport").getUnclippedBoundsInRoot()
        assertEquals("Requested production width must be measured literally", width.value, viewport.width.value, 1f)
        assertTrue(
            "Fixture needs a visible ${width.value} x 800dp host; host=$host viewport=$viewport",
            viewport.left >= host.left && viewport.right <= host.right &&
                viewport.top >= host.top && viewport.bottom <= host.bottom,
        )
    }

    private fun assertTouchTarget(target: SemanticsNodeInteraction, label: String) {
        target.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val node = target.fetchSemanticsNode()
        val bounds = node.touchBoundsInRoot
        with(node.layoutInfo.density) {
            assertTrue("TOUCH_TARGET $label: $bounds", bounds.width.toDp() >= 48.dp && bounds.height.toDp() >= 48.dp)
        }
    }

    private data class Labels(val actions: String, val restore: String, val readOnly: String, val addImage: String, val attachment: String)

    private companion object {
        const val TASK = "\u062d\u0644\u064a\u0628"
        val NOTE = Note(id = "offline-ui-note", title = "Offline UI", body = "Offline UI\n\n- [ ] $TASK", createdAt = 1L, modifiedAt = 1L)
        val ATTACHMENT = Attachment(
            id = "00000000-0000-0000-0000-000000000001",
            noteId = NOTE.id,
            uri = "attachments/00000000-0000-0000-0000-000000000001",
            mime = "image/png",
            createdAt = 1L,
        )
        val ENGLISH = Labels("Note actions", "Restore", "This note is in Trash. Restore it to edit.", "Add image", "Attachment")
        val ARABIC = Labels(
            "\u0625\u062c\u0631\u0627\u0621\u0627\u062a \u0627\u0644\u0645\u0644\u0627\u062d\u0638\u0629",
            "\u0627\u0633\u062a\u0639\u0627\u062f\u0629",
            "\u0647\u0630\u0647 \u0627\u0644\u0645\u0644\u0627\u062d\u0638\u0629 \u0641\u064a \u0627\u0644\u0645\u0647\u0645\u0644\u0627\u062a. \u0627\u0633\u062a\u0639\u062f\u0647\u0627 \u0644\u062a\u0639\u062f\u064a\u0644\u0647\u0627.",
            "\u0625\u0636\u0627\u0641\u0629 \u0635\u0648\u0631\u0629",
            "\u0645\u0631\u0641\u0642",
        )
    }
}
