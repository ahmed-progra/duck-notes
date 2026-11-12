package app.duck.notes.presentation.shell

import android.os.ParcelFileDescriptor
import android.provider.Settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import app.duck.notes.core.theme.DuckMotion
import app.duck.notes.core.theme.LocalDuckMotion
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Behavioural guards drive live counts, contents navigation, and reduced motion through the real shell.
 *
 * Both drive the real [DuckShell] rather than [DuckInfoPanel] alone, because both properties are
 * about *wiring between two panes* — which is the class of defect this project keeps shipping
 * (8c-c's fold chevron had perfect semantics and could not be tapped). A panel rendered in isolation
 * would prove neither.
 */
@RunWith(AndroidJUnit4::class)
class InfoPanelUiTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * The word count describes what is **in the editor**, not what Room last persisted.
     *
     * `SaveNote` is driven by a debounce with a ceiling, so `ShellUiState.selectedNoteBody` trails the
     * keyboard by up to `DuckShellViewModel.MAX_WAIT_MS`. A counter reading it would sit visibly
     * behind the text the user is looking at — the whole reason `DuckInfoPanel` takes the
     * `TextFieldState` instead of the body string.
     *
     * The fixture makes the two disagree on purpose: Room says one word, the buffer holds three. Only
     * one of those numbers can be on screen.
     *
     * **Plant:** in `DuckInfoPanel`, source `body` from the state's note body rather than
     * `buffer.text`. One identifier, no new symbol — and it reports `1 word`.
     */
    @Test
    fun theCountsReadTheLiveBufferNotRoom() {
        shell(width = COMPACT_WIDTH, room = STALE_BODY, buffer = LIVE_BODY)

        rule.onNodeWithText(LIVE_WORDS).assertIsDisplayed()
        rule.onNodeWithText(LIVE_CHARS).assertIsDisplayed()
    }

    /**
     * Tapping a contents row scrolls the editor until that heading is on screen.
     *
     * **This is the phase's one rendered-offset claim, and the arbiter of the argument that motivated
     * it.** The plan predicted that the cheaper pure-source-space route — write the caret to the
     * heading's offset and let Compose scroll to it — cannot work, because
     * `TextFieldCoreModifier.updateScrollState` returns early unless `showCursor`, which requires the
     * field to be focused, and tapping a panel row is what takes focus away. That reading came from
     * the **1.7.3** sources in the Gradle cache; the build resolves foundation **1.9.4**, whose
     * sources are not cached. So this test is evidence for the *behaviour*, not confirmation of the
     * reasoning: it exercises the forward-mapping implementation that was actually built, and would
     * pass equally if the caret route had also worked. It is not cited as proof that it would not.
     *
     * Measured on the **fold chevron**, not on the heading text: the editor is one `BasicTextField`,
     * so its body has no per-heading semantics node to measure. `SectionFoldOverlay` emits one node
     * per section, positioned at that heading's y by the same `renderedOffset` chain the scroll uses.
     * That makes the chevron a faithful proxy for where the heading is drawn.
     *
     * **Its own positive control is the before-tap assertion.** Without it a note that already fits
     * on screen passes for free — the chevron would be inside the viewport before and after, and the
     * scroll could be a no-op. `getUnclippedBoundsInRoot` is what makes an off-screen node
     * measurable at all.
     *
     * **And the after-assertion is "near the top", not "on screen", because the weaker form passed
     * for the wrong reason.** With the original fixture Omega sat at the end of the note, so
     * `animateScrollTo` hit `ScrollState.maxValue` and stopped short: measured 1668.6dp before and
     * 600.8dp after, against an 800dp viewport. Green, and proving only that *a* scroll ran — the
     * computed target was never reached, so the arithmetic this test exists for was untested. See
     * [SCROLL_NOTE] for the fixture change and [SCROLLED_HOME] for why the bar is a third rather than
     * an exact offset.
     *
     * **Plant:** replace `measured.getBoundingBox(rendered).top.roundToInt()` with `0` in
     * `MarkdownEditorScreen`'s scroll effect. One expression; the chevron never moves and the
     * after-tap assertion goes red while the before-tap control stays green.
     */
    @Test
    fun aTocRowScrollsTheEditorToItsHeading() {
        shell(width = WIDE_WIDTH, room = SCROLL_NOTE, buffer = SCROLL_NOTE)

        val before = rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top
        println("DUCK 9e: omega chevron before=$before viewport=$VIEWPORT_HEIGHT")

        rule.onNodeWithText(OMEGA).performClick()
        rule.waitForIdle()

        val after = rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top
        println("DUCK 9e: omega chevron after=$after viewport=$VIEWPORT_HEIGHT")

        assertTrue(
            "the fixture must put the last heading BELOW the fold before the tap, or this test " +
                "passes on a note that already fits: chevron top=$before, viewport=$VIEWPORT_HEIGHT",
            before > VIEWPORT_HEIGHT,
        )
        assertEquals(
            "tapping the contents row must scroll the heading to NEAR THE TOP of the editor, not " +
                "merely somewhere on screen. Chevron top was $before and is now $after; the bar is " +
                "$SCROLLED_HOME of a $VIEWPORT_HEIGHT viewport. A scroll target of 0 — the plant — " +
                "leaves it exactly where it was.",
            OmegaOnScreen(true),
            OmegaOnScreen(after < SCROLLED_HOME),
        )
    }

    /** Plant TOC_STALE_PLAN_CAPTURE: retain the initial conceal plan after editing the body. */
    @Test
    fun tocNavigationUsesTheEditedBodyPlan() {
        val editor = shell(WIDE_WIDTH, "", "", { DuckMotion.none })
        rule.runOnIdle {
            editor.edit {
                replace(0, length, EDITED_SCROLL_NOTE)
                selection = TextRange(length)
            }
        }
        rule.waitForIdle()
        assertTrue(rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > VIEWPORT_HEIGHT)
        rule.onNodeWithText(OMEGA).performClick()
        rule.waitForIdle()
        val after = rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top
        assertTrue("contents navigation must use the edited body's conceal plan", after >= 0.dp && after < SCROLLED_HOME)
    }

    /** Plant TOC_REDUCED_MOTION_ANIMATES: animate instead of snapping when motion is disabled. */
    @Test
    fun reducedMotionTocNavigationFinishesWithinTwoFrames() {
        shell(WIDE_WIDTH, SCROLL_NOTE, SCROLL_NOTE, { DuckMotion.none })
        assertTrue(rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > VIEWPORT_HEIGHT)
        val home = rule.onNodeWithContentDescription(HIDE_ALPHA).getUnclippedBoundsInRoot().top
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText(OMEGA).performClick()
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            val after = rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top
            assertTrue(
                "reduced-motion contents navigation must land within two frames",
                after >= home - 1.dp && after <= home + 1.dp,
            )
        } finally { rule.mainClock.autoAdvance = true }
    }

    /** Positive motion control; plant TOC_ALWAYS_SNAPS replaces the enabled animation with scrollTo. */
    @Test
    fun enabledMotionTocNavigationAnimatesBeforeFinishing() {
        shell(WIDE_WIDTH, SCROLL_NOTE, SCROLL_NOTE, { DuckMotion() })
        assertTrue(rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > VIEWPORT_HEIGHT)
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText(OMEGA).performClick()
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            assertTrue(
                "enabled contents motion must have an observable intermediate position",
                rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > SCROLLED_HOME,
            )
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            assertTrue(rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top < SCROLLED_HOME)
        } finally { rule.mainClock.autoAdvance = true }
    }

    /** Plant TOC_MOTION_LATCHED_AT_REQUEST: ignore a disabled preference during an active scroll. */
    @Test
    fun disablingMotionDuringTocNavigationFinishesWithinTwoFrames() {
        val motion = mutableStateOf(DuckMotion())
        val editor = shell(WIDE_WIDTH, SCROLL_NOTE, SCROLL_NOTE, { motion.value })
        val selection = editor.selection
        assertTrue(rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > VIEWPORT_HEIGHT)
        val home = rule.onNodeWithContentDescription(HIDE_ALPHA).getUnclippedBoundsInRoot().top
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText(OMEGA).performClick()
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            assertTrue("motion must still be in flight before disabling it",
                rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > SCROLLED_HOME)
            rule.runOnUiThread { motion.value = DuckMotion.none }
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            val after = rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top
            assertTrue("disabling motion must finish the active contents navigation within two frames",
                after >= home - 1.dp && after <= home + 1.dp)
            assertEquals(SCROLL_NOTE, editor.text.toString())
            assertEquals(selection, editor.selection)
        } finally { rule.mainClock.autoAdvance = true }
    }

    /** Plant TOC_INTERRUPTION_KILLS_COLLECTOR: collect serially so a touch scroll kills future requests. */
    @Test
    fun touchInterruptedTocNavigationAcceptsTheNextRequest() {
        val editor = shell(WIDE_WIDTH, SCROLL_NOTE, SCROLL_NOTE, { DuckMotion() })
        val selection = editor.selection
        assertTrue(rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > VIEWPORT_HEIGHT)
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText(OMEGA).performClick()
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            val beforeTouch = rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top
            assertTrue("contents motion must still be active before touch interruption", beforeTouch > SCROLLED_HOME)
            rule.onNodeWithTag("noteEditorViewport").performTouchInput { swipeDown(durationMillis = 80) }
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            val afterTouch = rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top
            assertTrue("the actual touch must move the editor toward the beginning", afterTouch > beforeTouch + 20.dp)
            assertTrue("the second contents request must still need to scroll", afterTouch > SCROLLED_HOME)
            rule.onNodeWithText(OMEGA).performClick()
            rule.waitForIdle()
            val after = rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top
            assertTrue("contents navigation must accept another request after touch interruption",
                after >= 0.dp && after < SCROLLED_HOME)
            assertEquals(SCROLL_NOTE, editor.text.toString())
            assertEquals(selection, editor.selection)
        } finally { rule.mainClock.autoAdvance = true }
    }

    /** Plant TOC_QUEUES_SUPERSEDED_TARGET: serial collection delays the latest heading behind the old one. */
    @Test
    fun latestTocRequestSupersedesTheRunningTarget() {
        val editor = shell(WIDE_WIDTH, SCROLL_NOTE, SCROLL_NOTE, { DuckMotion() })
        val selection = editor.selection
        assertTrue(rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > VIEWPORT_HEIGHT)
        val home = rule.onNodeWithContentDescription(HIDE_ALPHA).getUnclippedBoundsInRoot().top
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText(OMEGA).performClick()
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            assertTrue("the first request must have moved Alpha offscreen",
                rule.onNodeWithContentDescription(HIDE_ALPHA).getUnclippedBoundsInRoot().top < 0.dp)
            rule.onNodeWithText(ALPHA).performClick()
            rule.mainClock.advanceTimeBy(300)
            rule.waitForIdle()
            val after = rule.onNodeWithContentDescription(HIDE_ALPHA).getUnclippedBoundsInRoot().top
            assertTrue("the latest contents target must finish within the literal 300 ms ceiling",
                after >= home - 1.dp && after <= home + 1.dp)
            assertEquals(SCROLL_NOTE, editor.text.toString())
            assertEquals(selection, editor.selection)
        } finally { rule.mainClock.autoAdvance = true }
    }

    /** Plant TOC_SYSTEM_MOTION_OBSERVER_DISABLED: ignore live Settings ContentObserver notifications. */
    @Test
    fun systemWindowScaleDisablesAnActiveTocNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertEquals("the system motion test requires the disposable emulator", "1", shellCommand("getprop ro.kernel.qemu").trim())
        val resolver = instrumentation.targetContext.contentResolver
        val windowKey = Settings.Global.WINDOW_ANIMATION_SCALE
        val animatorKey = Settings.Global.ANIMATOR_DURATION_SCALE
        val transitionKey = Settings.Global.TRANSITION_ANIMATION_SCALE
        val keys = listOf(windowKey, animatorKey, transitionKey)
        val original = keys.associateWith { Settings.Global.getString(resolver, it) }
        keys.forEach { assertEquals("system motion must begin at the normal scale", 1f, Settings.Global.getFloat(resolver, it, 1f), 0f) }
        val originalWindow = original[windowKey]
        assertTrue("the exact original setting must be safe to restore through the shell",
            originalWindow == null || originalWindow.matches(Regex("[0-9.]+")) && originalWindow.toFloatOrNull() == 1f)
        val observed = AtomicReference<DuckMotion>()
        val disabled = CountDownLatch(1)
        val editor = shell(WIDE_WIDTH, SCROLL_NOTE, SCROLL_NOTE, {
            val actual = LocalDuckMotion.current
            SideEffect {
                observed.set(actual)
                if (!actual.animates) disabled.countDown()
            }
            actual
        })
        val selection = editor.selection
        val home = rule.onNodeWithContentDescription(HIDE_ALPHA).getUnclippedBoundsInRoot().top
        assertTrue("the actual DuckTheme must initially enable motion", observed.get()?.animates == true)
        assertTrue(rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > VIEWPORT_HEIGHT)
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithText(OMEGA).performClick()
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            assertTrue("the system setting must change while contents motion is still active",
                rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top > SCROLLED_HOME)
            shellCommand("settings put global window_animation_scale 0")
            assertEquals("0", Settings.Global.getString(resolver, windowKey))
            assertEquals(original[animatorKey], Settings.Global.getString(resolver, animatorKey))
            assertEquals(original[transitionKey], Settings.Global.getString(resolver, transitionKey))
            assertEquals(1f, Settings.Global.getFloat(resolver, animatorKey, 1f), 0f)
            instrumentation.waitForIdleSync()
            // Allow one recomposition frame to publish the real ContentObserver result.
            rule.mainClock.advanceTimeBy(16)
            assertTrue("the live system window-scale observer must disable Duck motion",
                disabled.await(5, TimeUnit.SECONDS) && observed.get()?.animates == false)
            rule.mainClock.advanceTimeBy(32)
            rule.waitForIdle()
            val after = rule.onNodeWithContentDescription(HIDE_OMEGA).getUnclippedBoundsInRoot().top
            assertTrue("system-disabled motion must finish the active contents target within two frames",
                after >= home - 1.dp && after <= home + 1.dp)
            assertEquals(SCROLL_NOTE, editor.text.toString())
            assertEquals(selection, editor.selection)
        } finally {
            try {
                shellCommand(if (originalWindow == null) "settings delete global window_animation_scale"
                    else "settings put global window_animation_scale $originalWindow")
                assertEquals("the exact original window scale or absence must be restored", originalWindow, Settings.Global.getString(resolver, windowKey))
                assertEquals(original[animatorKey], Settings.Global.getString(resolver, animatorKey))
                assertEquals(original[transitionKey], Settings.Global.getString(resolver, transitionKey))
            } finally { rule.mainClock.autoAdvance = true }
        }
    }

    private fun shellCommand(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    /** Named wrapper so a failure reads `OmegaOnScreen(false)`, not a bare `false`. */
    private data class OmegaOnScreen(val value: Boolean)

    /**
     * The real [DuckShell] at [width], with a note open and the info panel showing.
     *
     * [room] and [buffer] are separate parameters precisely so a test can make them disagree.
     * `infoOpen` is passed in the state rather than clicked, because these tests drive an inert
     * [ShellActions] whose `onToggleInfo` does nothing.
     *
     * `requiredSize`, not `size`: a preferred size is clamped by the incoming constraints, so on a
     * 411dp emulator a `size(1200.dp, …)` box measures 411dp and `paneModeFor` silently returns
     * Compact. The lesson `RtlLayoutTest` records after it cost a red there.
     */
    private fun shell(width: androidx.compose.ui.unit.Dp, room: String, buffer: String, motion: @Composable () -> DuckMotion = { LocalDuckMotion.current }): TextFieldState {
        val editor = TextFieldState(buffer)
        rule.setContent {
            DuckTheme {
                CompositionLocalProvider(LocalDuckMotion provides motion()) {
                Box(Modifier.requiredSize(width, VIEWPORT_HEIGHT)) {
                    DuckShell(
                        state = ShellUiState(
                            notes = listOf(NOTE.copy(body = room)),
                            selectedNoteId = NOTE.id,
                            selectedNoteBody = room,
                            infoOpen = true,
                            selectedNoteCreatedAt = NOTE.createdAt,
                            selectedNoteModifiedAt = NOTE.modifiedAt,
                        ),
                        buffer = editor,
                        actions = ShellActions(),
                    )
                }
                }
            }
        }
        rule.waitForIdle()
        return editor
    }

    private companion object {
        val NOTE = Note(id = "n1", title = "note", body = "", createdAt = 0L, modifiedAt = 1L)

        /** Room's copy and the buffer disagree by two words, so only one can be on screen. */
        const val STALE_BODY = "one"

        const val LIVE_BODY = "one two three"

        /** What `editor_word_count` / `editor_char_count` render for [LIVE_BODY]. */
        const val LIVE_WORDS = "3 words"

        const val LIVE_CHARS = "13 characters"

        /** The last heading's own title, which is also its contents row's label. */
        const val ALPHA = "Alpha"
        const val HIDE_ALPHA = "Hide Alpha section"
        const val OMEGA = "Omega"

        /** `editor_collapse_section` — "Hide %1$s section" — for that heading. */
        const val HIDE_OMEGA = "Hide Omega section"

        /**
         * Two headings far enough apart that the second starts well below an 800dp viewport, with
         * filler **on both sides** of it.
         *
         * The tail is load-bearing twice over. It gives Omega a `FoldSection.content`, without which
         * no chevron is emitted at all — and it gives the scroll somewhere to go. **The first draft
         * had only one short tail line and it weakened the guard silently:** Omega sat at the end of
         * the note, so `animateScrollTo` clamped at `ScrollState.maxValue` before reaching its target
         * and the heading landed at 600.8dp of an 800dp viewport. That still satisfied a bare "is it
         * on screen" assertion, so the test was green while proving only that *some* scroll happened
         * — the target arithmetic it exists for was never exercised. Measured, not theorised.
         */
        val SCROLL_NOTE = buildString {
            append("# Alpha\n")
            repeat(60) { append("filler line $it\n") }
            append("# Omega\n")
            repeat(60) { append("tail line $it\n") }
        }

        val EDITED_SCROLL_NOTE = buildString {
            append("# Alpha\n")
            repeat(120) { append("- **filler line $it**\n") }
            append("# Omega\n")
            repeat(120) { append("tail line $it\n") }
        }

        val VIEWPORT_HEIGHT = 800.dp

        /**
         * How near the top a scrolled-to heading must land — the top third of the viewport.
         *
         * Not an exact offset: the effect targets `bounds.top`, so the heading should come to rest at
         * the editor's own top inset (12dp) plus whatever `Scaffold` and the status bar contribute,
         * and encoding that sum here would pin this test to Compose's inset bookkeeping. A third is
         * loose enough to survive that and tight enough to reject the clamped 600.8dp the first
         * fixture produced.
         */
        val SCROLLED_HOME = VIEWPORT_HEIGHT / 3

        /** Compact, so the panel is the bottom sheet. Below `paneModeFor`'s 600dp breakpoint. */
        val COMPACT_WIDTH = 400.dp

        /**
         * Wider than `RtlLayoutTest`'s 900dp Expanded fixture on purpose: at 900 the four panes
         * (280 + 320 + editor + 280) leave the editor about 15dp, which is not an editor to scroll.
         */
        val WIDE_WIDTH = 1200.dp
    }
}
