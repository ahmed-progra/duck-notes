package app.duck.notes.presentation.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckMotion
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.theme.LocalDuckMotion
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the Compact pane change **actually animates**, and that reduced motion **actually removes it**.
 *
 * ## Why this never reads `Settings`
 *
 * The obvious version of this test builds the real shell and lets `DuckTheme` resolve motion from the
 * system. It would then pass on a device with *Animator duration scale* set to 0 — every duration
 * collapses, the transition is instant, and an assertion about mid-transition state either has to be
 * weakened until it holds or is measuring nothing at all. A CI emulator is exactly where that setting
 * gets turned off.
 *
 * That is the same failure shape this project has now hit three times: the editor saver guards that
 * inspected `SavedStateHandle` while `rememberSaveable` wrote to the composition's registry, and the
 * `testDebugUnitTest` run that reported `BUILD SUCCESSFUL` while `UP-TO-DATE` meant no test executed.
 * **A green result that cannot distinguish "passed" from "never ran".**
 *
 * So motion is *provided*, not read. Both cases are hermetic and neither can be neutered by the host.
 *
 * The transition here is a local rebuild rather than the real `DuckShell`, because the assertion is
 * about `AnimatedContent`'s behaviour under a given `DuckMotion` — the real shell would drag in Hilt,
 * Room, a `ViewModel` and a `BoxWithConstraints` width branch, none of which this is about.
 */
@RunWith(AndroidJUnit4::class)
class PaneTransitionTest {

    @get:Rule val compose = createComposeRule()

    private companion object {
        const val LIST = "the list pane"
        const val EDITOR = "the editor pane"
    }

    /**
     * Mirrors the shape of `DuckShell`'s Compact branch: keyed on **a Boolean**, not a note id.
     *
     * That key is load-bearing in production and is reproduced here so this test exercises the same
     * arrangement. Keyed on the id, `AnimatedContent` would hold outgoing and incoming editors on
     * screen together, both bound to the single `DuckShellViewModel.buffer` — one `TextFieldState`
     * driving two `BasicTextField`s.
     */
    private fun setPanes(motion: DuckMotion): MutableState<Boolean> {
        // Hoisted out of composition so the test can flip it without a callback captured mid-compose.
        val open = mutableStateOf(false)
        compose.setContent {
            // INSIDE DuckTheme, not around it. DuckTheme provides LocalDuckMotion itself, from the
            // system setting — so a provider on the outside is overwritten and the test silently
            // runs at whatever the host device is set to. That is not hypothetical: this test was
            // written the other way round first, and the reduced-motion case ran at full motion
            // while still reporting a pass on the case above it.
            DuckTheme {
                CompositionLocalProvider(LocalDuckMotion provides motion) {
                    val m = DuckTheme.motion
                    // Mirrors DuckShell: reduced motion does not enter AnimatedContent at all.
                    if (!m.animates) {
                        Box { Text(if (open.value) EDITOR else LIST) }
                        return@CompositionLocalProvider
                    }
                    AnimatedContent(
                        targetState = open.value,
                        transitionSpec = {
                            fadeIn(m.mediumSpec()).togetherWith(fadeOut(m.fastSpec()))
                        },
                        label = "compactPane",
                    ) { isOpen -> Box { Text(if (isOpen) EDITOR else LIST) } }
                }
            }
        }
        return open
    }

    /**
     * **Both panes exist mid-transition.** That is the observable consequence of animating: with no
     * transition the swap is atomic and the outgoing pane is gone by the next frame.
     *
     * The clock is driven by hand — `autoAdvance = false` — because with it running the assertion
     * would race the animation and pass or fail on machine speed.
     */
    @Test
    fun theOutgoingPaneIsStillOnScreenPartWayThroughTheTransition() {
        val open = setPanes(DuckMotion())
        compose.onNodeWithText(LIST).assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { open.value = true }
        // Past the first frame so the transition has started, well short of the 200ms it runs for.
        compose.mainClock.advanceTimeBy(64)

        compose.onNodeWithText(LIST).assertExists(
            "the outgoing pane was gone 64ms into a 200ms transition — nothing is animating",
        )
        compose.onNodeWithText(EDITOR).assertExists()

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(EDITOR).assertIsDisplayed()
        compose.onNodeWithText(LIST).assertDoesNotExist()
    }

    /**
     * The same swap under [DuckMotion.none] is **atomic on the very first frame**.
     *
     * **16ms, not 64ms, and the difference is the whole test.** An earlier version asserted at 64ms
     * and passed while the panes were in fact both composed for two frames: zero-length specs finish
     * instantly, but `AnimatedContent` keeps the outgoing content alive until its transition
     * bookkeeping settles. Measured frame by frame — `0ms L1/E0, 16ms L1/E1, 32ms L1/E1, 48ms L0/E1`
     * — and invisible, because a zero-length fade is already at alpha 0, which is exactly why an
     * assertion loose enough to miss it is worse than none.
     *
     * That is what `DuckMotion.animates` and the branch above exist for, and this is what pins them.
     */
    @Test
    fun reducedMotionSwapsThePanesOnTheFirstFrame() {
        val open = setPanes(DuckMotion.none)
        compose.onNodeWithText(LIST).assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { open.value = true }
        compose.mainClock.advanceTimeBy(16) // one frame

        compose.onNodeWithText(LIST).assertDoesNotExist()
        compose.onNodeWithText(EDITOR).assertExists()
    }
}
