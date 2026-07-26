package app.duck.notes.markdown

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.markdown.MarkdownOutputTransformation
import app.duck.notes.core.markdown.MarkdownStyles
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.theme.DuckThemeSpec
import app.duck.notes.domain.markdown.MarkdownTextCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Does a theme switch repaint text that is already on screen, with no keystroke?**
 *
 * This test is the oracle for a design decision, not a regression net for one. `transformOutput` runs
 * when the transformed text is recomputed, and a theme change is *not* a buffer change — so whether
 * handing `BasicTextField` a new `OutputTransformation` instance is enough to force a repaint is a
 * fact about Compose Foundation internals that cannot be reasoned out from the JVM. Two mechanisms
 * were designed for it:
 *
 * - **A** — `remember(styles) { MarkdownOutputTransformation(cache, styles) }`, a plain
 *   [MarkdownStyles]. Simple, and what production ships if this test is green.
 * - **B** — a permanently-remembered transformation reading a `State<MarkdownStyles>` *inside*
 *   `transformOutput`, so invalidation rides the same snapshot path as `originalText`. Strictly more
 *   machinery, and only justified if A fails.
 *
 * This asserts A. If it ever goes red, the fix is B, not a louder assertion.
 *
 * The assertion is on **which palette the transformation actually ran with**, recorded through a
 * delegating wrapper. Reading rendered colour back out of semantics or sampling pixels would be
 * brittle against font rendering and would not distinguish "re-ran" from "never ran".
 *
 * The wiring below mirrors [app.duck.notes.presentation.markdown.MarkdownEditorScreen] line for line;
 * it is duplicated rather than reused because the wrapper has to sit between the field and the real
 * transformation to see anything.
 */
@RunWith(AndroidJUnit4::class)
class ThemeRepaintTest {

    @get:Rule
    val rule = createComposeRule()

    /** Records the palette each `transformOutput` ran with, then delegates unchanged. */
    private class Recording(
        private val tag: String,
        private val delegate: OutputTransformation,
        private val log: MutableList<String>,
    ) : OutputTransformation {
        override fun TextFieldBuffer.transformOutput() {
            log += tag
            val buffer = this
            with(delegate) { buffer.transformOutput() }
        }
    }

    @Test
    fun switchingThemeRepaintsWithoutAKeystroke() {
        val log = mutableListOf<String>()
        val state = TextFieldState("a **bold** word and a [link](https://duck.example)")
        var spec by mutableStateOf(DuckThemeSpec.DuckRed)

        rule.setContent {
            DuckTheme(spec = spec, dark = false) {
                val cache = remember { MarkdownTextCache() }
                val colors = DuckTheme.colors
                val type = DuckTheme.type
                val styles = remember(colors, type) { MarkdownStyles(colors, type) }
                val transformation = remember(styles) {
                    Recording(
                        tag = colors.accentText.value.toString(16),
                        delegate = MarkdownOutputTransformation(cache, styles),
                        log = log,
                    )
                }
                BasicTextField(
                    state = state,
                    outputTransformation = transformation,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        rule.waitForIdle()

        val duckRedTag = DuckThemeSpec.DuckRed.light.accentText.value.toString(16)
        val midnightTag = DuckThemeSpec.Midnight.light.accentText.value.toString(16)
        assertTrue(
            "the field never transformed at all under the initial theme — log=$log",
            log.contains(duckRedTag),
        )
        assertEquals("the two palettes must be distinguishable", false, duckRedTag == midnightTag)

        log.clear()

        // The whole point: change the theme and touch nothing else. No typing, no selection, no
        // buffer edit of any kind.
        rule.runOnUiThread { spec = DuckThemeSpec.Midnight }
        rule.waitForIdle()

        assertTrue(
            "MECHANISM A FAILED: after a theme switch with no keystroke, transformOutput did not " +
                "re-run with the new palette (log=$log). The text on screen is still painted in the " +
                "old theme's colours. Switch MarkdownOutputTransformation to mechanism B — a " +
                "State<MarkdownStyles> read inside transformOutput.",
            log.contains(midnightTag),
        )
    }

    /** The recorder must be able to fail, or the assertion above proves nothing. */
    @Test
    fun theRecorderObservesEachTransform() {
        val log = mutableListOf<String>()
        val state = TextFieldState("**x**")

        rule.setContent {
            DuckTheme(spec = DuckThemeSpec.DuckRed, dark = false) {
                val cache = remember { MarkdownTextCache() }
                val colors = DuckTheme.colors
                val type = DuckTheme.type
                val styles = remember { MarkdownStyles(colors, type) }
                BasicTextField(
                    state = state,
                    outputTransformation = Recording(
                        "seen",
                        MarkdownOutputTransformation(cache, styles),
                        log,
                    ),
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        rule.waitForIdle()

        assertTrue("the wrapper never saw a transform, so it cannot witness anything", log.isNotEmpty())
        assertEquals(setOf("seen"), log.toSet())
    }

    private companion object {
        const val TAG = "editor"
    }
}
