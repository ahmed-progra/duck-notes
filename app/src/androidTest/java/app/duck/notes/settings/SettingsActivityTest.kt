package app.duck.notes.settings

import android.app.UiModeManager
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.settings.SettingsRepository
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.ThemePreference
import app.duck.notes.presentation.MainActivity
import app.duck.notes.presentation.settings.SettingsViewModel
import app.duck.notes.presentation.shell.DuckShellViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual launcher Activity, actual Settings rows, real Preferences I/O and isolated Room.
 * Plants: ACTIVITY_DARK_SURFACE, INFO_CONTENTS_TEXT_ROLE, SETTINGS_RETURN_SCROLL_STATE
 * and SETTINGS_WRITE_FAILURE_NOTICE. The return plant isolates scroll, not owner/body/caret.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SettingsActivityTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    @Inject lateinit var resources: SettingsTestResources
    @Inject lateinit var repository: SettingsRepository
    @Inject lateinit var database: DuckDatabase
    private var scenario: ActivityScenario<MainActivity>? = null

    private val body = "## Palette target\n" + (1..80).joinToString("\n") { "Plain editor line $it with enough text to scroll." }

    @Before fun setUp() {
        SettingsTestOptions.initialTheme = ThemePreference.Light
        hilt.inject()
        pumpUntil { repository.state.value.readStatus == SettingsReadStatus.Ready }
        runBlocking {
            database.noteDao().insert(
                NoteEntity(id = "settings-fixture", title = "Settings fixture", body = body,
                    createdAt = 1_700_000_000_000L, modifiedAt = 1_700_000_000_000L),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    @After fun tearDown() {
        try {
            scenario?.close()
        } finally {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync {
                if (Build.VERSION.SDK_INT >= 31) {
                    instrumentation.targetContext.getSystemService(UiModeManager::class.java)
                        .setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                }
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                SettingsDrawProbe.reset()
            }
            if (::resources.isInitialized) runBlocking { resources.close() }
            if (::database.isInitialized) database.close()
            SettingsTestOptions.reset()
        }
    }

    @Test fun themeRowPersistsDarkAndRecreationRendersTheLiteralDarkSurface() {
        openSettings()
        compose.onNodeWithText("Dark").performClick()
        pumpUntil { repository.state.value.settings.theme == ThemePreference.Dark }
        compose.waitForIdle()
        compose.onNodeWithText("Dark").assertIsSelected()
        assertSurface(0xFF1C1C1E.toInt())
        scenario?.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("Dark").assertIsSelected()
        assertSurface(0xFF1C1C1E.toInt())
    }

    @Test fun actionTextUsesLiteralAccessibleColorInBothRealThemes() {
        selectFixture()
        compose.onNodeWithContentDescription("Backlinks").performScrollTo().assertIsDisplayed().performClick()
        assertActionColor(0xFFB93C3F.toInt(), 0xFFF7F7F8.toInt())
        // Close the production panel before opening Settings.
        scenario?.onActivity { ViewModelProvider(it)[DuckShellViewModel::class.java].onToggleInfo() }
        openSettings()
        compose.onNodeWithText("Dark").performClick()
        pumpUntil { repository.state.value.settings.theme == ThemePreference.Dark }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Back to notes").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Backlinks").performScrollTo().assertIsDisplayed().performClick()
        assertActionColor(0xFFF08386.toInt(), 0xFF2A2A2D.toInt())
    }

    @Test fun settingsReturnPreservesTheActualEditorOwnerBodyCaretAndNonzeroScroll() {
        selectFixture()
        lateinit var original: DuckShellViewModel
        var beforeText = ""
        var beforeSelection = TextRange.Zero
        scenario?.onActivity { activity ->
            original = ViewModelProvider(activity)[DuckShellViewModel::class.java]
            original.buffer.edit {
                replace(length, length, "\nUnsaved Settings-return sentinel")
                selection = TextRange(180)
            }
            beforeText = original.buffer.text.toString()
            beforeSelection = original.buffer.selection
        }
        compose.waitForIdle()
        val editor = compose.onNodeWithTag("noteEditor").fetchSemanticsNode()
        var parent = editor.parent
        while (parent != null && parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) == null) {
            parent = parent.parent
        }
        val scroll = requireNotNull(parent) { "Real editor has no scroll semantics ancestor" }
        compose.onNode(SemanticsMatcher("real editor scroller") { it.id == scroll.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 320f) }
        compose.waitForIdle()
        val beforeScroll = requireNotNull(scroll.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
        assertTrue("Positive control: editor did not actually scroll", beforeScroll > 0f)
        openSettings()
        compose.onNodeWithContentDescription("Back to notes").performClick()
        compose.waitForIdle()
        scenario?.onActivity { activity ->
            val current = ViewModelProvider(activity)[DuckShellViewModel::class.java]
            assertSame(original, current)
            assertEquals(beforeText, current.buffer.text.toString())
            assertEquals(beforeSelection, current.buffer.selection)
            assertEquals("settings-fixture", current.state.value.selectedNoteId)
        }
        var restoredParent = compose.onNodeWithTag("noteEditor").fetchSemanticsNode().parent
        while (restoredParent != null && restoredParent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) == null) {
            restoredParent = restoredParent.parent
        }
        val afterScroll = requireNotNull(requireNotNull(restoredParent).config
            .getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
        assertEquals(beforeScroll, afterScroll, 1f)
    }

    @Test fun actualWriteFailureKeepsSavedLightAndShowsRetryThenReallyRetries() {
        openSettings()
        resources.blockWrites()
        compose.onNodeWithText("Dark").performClick()
        compose.waitForIdle()
        var pending = false
        scenario?.onActivity { pending = ViewModelProvider(it)[SettingsViewModel::class.java].uiState.value.pendingWrite }
        assertTrue("Positive control: the real file write was not pending on the paused worker", pending)
        pumpUntil {
            scenario?.onActivity { pending = ViewModelProvider(it)[SettingsViewModel::class.java].uiState.value.pendingWrite }
            !pending
        }
        compose.waitForIdle()
        compose.onNodeWithText("Light").assertIsSelected()
        assertEquals(ThemePreference.Light, repository.state.value.settings.theme)
        compose.onNodeWithText("Could not save the change. Try again.").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertExists()
        resources.unblockWrites()
        compose.onNodeWithText("Retry").performClick()
        pumpUntil { repository.state.value.settings.theme == ThemePreference.Dark }
        compose.waitForIdle()
        compose.onNodeWithText("Dark").assertIsSelected()
        assertSurface(0xFF1C1C1E.toInt())
    }

    private fun selectFixture() {
        compose.waitUntil(timeoutMillis = 10_000L) {
            compose.onAllNodesWithText("Settings fixture").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Settings fixture").performClick()
        compose.waitForIdle()
    }

    private fun openSettings() {
        val visible = try {
            compose.onNodeWithText("Settings").assertIsDisplayed()
            true
        } catch (_: AssertionError) {
            false
        }
        if (!visible) {
            compose.onNodeWithContentDescription("Open sidebar").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
    }

    private fun pumpUntil(predicate: () -> Boolean) {
        compose.waitUntil(timeoutMillis = 10_000L) {
            resources.advanceSettingsWork()
            predicate()
        }
    }

    private fun assertSurface(expected: Int) {
        val pixels = compose.onRoot().captureToImage().toPixelMap()
        var matches = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (closeColor(pixels[x, y].toArgb(), expected)) matches++
        }
        assertTrue("No substantial literal surface ${expected.toUInt().toString(16)}: $matches pixels",
            matches > pixels.width * pixels.height / 5)
    }

    private fun assertActionColor(foreground: Int, background: Int) {
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("Contents")))
            .performScrollToNode(hasText("Palette target"))
        compose.waitForIdle()
        val pixels = compose.onNodeWithText("Palette target").captureToImage().toPixelMap()
        var observedForeground: Int? = null
        var observedBackground: Int? = null
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y].toArgb()
            if (closeColor(color, foreground)) observedForeground = color
            if (closeColor(color, background)) observedBackground = color
        }
        assertTrue("Expected literal action text was not rendered", observedForeground != null)
        val textColor = requireNotNull(observedForeground) { "Expected literal action text was not rendered" }
        val surfaceColor = requireNotNull(observedBackground) { "Expected literal sheet background was not rendered" }
        val first = luminance(textColor)
        val second = luminance(surfaceColor)
        val contrast = (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
        assertTrue("Rendered 12sp action text owes 4.5:1; actual=$contrast", contrast >= 4.5)
    }

    private fun closeColor(actual: Int, expected: Int): Boolean =
        listOf(16, 8, 0).all { shift -> abs((actual shr shift and 255) - (expected shr shift and 255)) <= 8 }

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val s = (color shr shift and 255) / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
