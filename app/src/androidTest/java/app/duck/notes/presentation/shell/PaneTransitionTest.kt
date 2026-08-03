package app.duck.notes.presentation.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.theme.DuckMotion
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.theme.LocalDuckMotion
import app.duck.notes.domain.model.Note
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That **`DuckShell`'s** Compact pane change actually animates, and that reduced motion actually
 * removes it.
 *
 * ## This drove a copy until an audit caught it
 *
 * The first version built its own `AnimatedContent` and its own `if (!m.animates)` branch inside the
 * test and never constructed `DuckShell` at all, on the stated grounds that "the real shell would
 * drag in Hilt, Room, a `ViewModel` and a `BoxWithConstraints` width branch". Two things were wrong
 * with that. `DuckShell` has a **stateless overload** taking `(state, buffer, actions, modifier)` —
 * the gallery is already its second caller — so none of Hilt, Room or a ViewModel is required. And
 * the copy was not even faithful: it passed `fadeOut(fastSpec())` where production passes
 * `fadeOut(exitSpec())` and omitted the `scaleIn` entirely, so the *exit* half — the half Phase 8b
 * added `easeExit` for — was the half under test nowhere.
 *
 * What a red proved was therefore "Compose's `AnimatedContent` behaves as documented", not "Duck
 * animates". **Deleting the whole `AnimatedContent` from `DuckShell` left it green.** CLAUDE.md's
 * Guards rule — *drive the real thing* — was written from this and three siblings.
 *
 * ## Why this still never reads `Settings`
 *
 * Unchanged and still load-bearing. Letting `DuckTheme` resolve motion from the system means the
 * test passes on a device with *Animator duration scale* set to 0 — every duration collapses, the
 * transition is instant, and an assertion about mid-transition state measures nothing. A CI emulator
 * is exactly where that setting gets turned off. So motion is *provided*, not read, and both cases
 * are hermetic.
 *
 * The provider goes **inside** `DuckTheme`, which provides `LocalDuckMotion` itself — outside, the
 * theme overwrites it and the reduced-motion case silently runs at full motion. That is not
 * hypothetical: this test was written the other way round first.
 */
@RunWith(AndroidJUnit4::class)
class PaneTransitionTest {

    @get:Rule val compose = createComposeRule()

    private companion object {
        /** Under the 600dp breakpoint, so `paneModeFor` yields Compact. `ShellStateTest` pins that. */
        val COMPACT_WIDTH = 400.dp
        val COMPACT_HEIGHT = 800.dp

        const val TITLE = "Pond notes"
        const val BODY = "how many ducks"

        val NOTE = Note(id = "n1", title = TITLE, body = BODY, createdAt = 0L, modifiedAt = 1L)
    }

    /**
     * The real [DuckShell], Compact, with the selection hoisted so a test can flip it.
     *
     * Selection is the production trigger for the transition (`DuckShell` keys `AnimatedContent` on
     * `state.selectedNoteId != null`), so flipping it is what a tap on a note card does. Returned as
     * a [MutableState] rather than driven through `actions.onSelectNote`, because the real callback
     * routes through a ViewModel this test deliberately does not build.
     */
    private fun setShell(motion: DuckMotion): MutableState<Boolean> {
        val open = mutableStateOf(false)
        compose.setContent {
            DuckTheme {
                CompositionLocalProvider(LocalDuckMotion provides motion) {
                    Box(Modifier.size(COMPACT_WIDTH, COMPACT_HEIGHT)) {
                        DuckShell(
                            state = ShellUiState(
                                notes = listOf(NOTE),
                                selectedNoteId = if (open.value) NOTE.id else null,
                                selectedNoteBody = if (open.value) BODY else "",
                            ),
                            buffer = TextFieldState(BODY),
                            actions = ShellActions(),
                        )
                    }
                }
            }
        }
        return open
    }

    /**
     * **Both panes exist mid-transition.** That is the observable consequence of animating: with no
     * transition the swap is atomic and the outgoing pane is gone by the next frame.
     *
     * The panes are identified by their own content — the note's title is the list pane's card, the
     * body is the editor — rather than by two literals the test invented, so the assertion is about
     * what a user sees.
     *
     * The clock is driven by hand (`autoAdvance = false`), or the assertion would race the animation
     * and pass or fail on machine speed.
     */
    @Test
    fun theOutgoingPaneIsStillOnScreenPartWayThroughTheTransition() {
        val open = setShell(DuckMotion())
        compose.onNodeWithText(TITLE).assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { open.value = true }
        // Past the first frame so the transition has started, well short of the 200ms it runs for.
        compose.mainClock.advanceTimeBy(64)

        compose.onNodeWithText(TITLE).assertExists(
            "the outgoing list pane was gone 64ms into a 200ms transition — DuckShell is not " +
                "animating the Compact pane change",
        )
        compose.onNodeWithText(BODY).assertExists()

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText(BODY).assertIsDisplayed()
        compose.onNodeWithText(TITLE).assertDoesNotExist()
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
     * That is what `DuckMotion.animates` and `DuckShell`'s branch on it exist for, and this is what
     * pins them — now in `DuckShell`, where the branch actually is.
     */
    @Test
    fun reducedMotionSwapsThePanesOnTheFirstFrame() {
        val open = setShell(DuckMotion.none)
        compose.onNodeWithText(TITLE).assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { open.value = true }
        compose.mainClock.advanceTimeBy(16) // one frame

        compose.onNodeWithText(TITLE).assertDoesNotExist()
        compose.onNodeWithText(BODY).assertExists()
    }
}
