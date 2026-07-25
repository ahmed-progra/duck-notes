package app.duck.notes.markdown

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4's load-bearing assumption, pinned on a device.
 *
 * Conceal/reveal is driven entirely by caret position: a syntax marker is hidden unless the caret is
 * inside its node. That only works if Compose re-runs `transformOutput` when the *selection* changes
 * and the text does not. Nothing else in the markdown engine is worth writing until this is true, so
 * it is the first test in the phase rather than an afterthought.
 *
 * If this ever goes red on a Compose upgrade, the fallback is to mirror the caret into a
 * `MutableState` via `snapshotFlow { state.selection }` and read it inside the transformation lambda
 * to force invalidation — same design, one extra hop. Do not delete this test; it is the tripwire.
 */
@RunWith(AndroidJUnit4::class)
class OutputTransformationCaretSpikeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun transformOutputRerunsOnCaretOnlyChange() {
        val runs = AtomicInteger(0)
        val seenSelections = Collections.synchronizedList(mutableListOf<TextRange>())
        lateinit var state: TextFieldState

        rule.setContent {
            state = rememberTextFieldState(INITIAL)
            BasicTextField(
                state = state,
                outputTransformation = remember {
                    {
                        runs.incrementAndGet()
                        seenSelections += originalSelection
                    }
                },
            )
        }
        rule.waitForIdle()

        val runsBeforeCaretMove = runs.get()
        assertTrue("transformOutput never ran at all", runsBeforeCaretMove > 0)

        // The whole point: move the caret, touch no text.
        rule.runOnUiThread { state.edit { selection = TextRange(CARET) } }
        rule.waitForIdle()

        assertEquals("text must not have changed", INITIAL, state.text.toString())
        assertTrue(
            "transformOutput did not re-run on a caret-only change " +
                "(runs before=$runsBeforeCaretMove, after=${runs.get()})",
            runs.get() > runsBeforeCaretMove,
        )
        assertTrue(
            "transformOutput never observed the new caret via originalSelection; saw $seenSelections",
            seenSelections.any { it.start == CARET && it.collapsed },
        )
    }

    private companion object {
        const val INITIAL = "hello world"
        const val CARET = 3
    }
}
