package app.duck.notes.core.markdown

import androidx.compose.ui.text.style.TextDecoration
import app.duck.notes.core.theme.DuckThemeSpec
import app.duck.notes.core.theme.duckType
import app.duck.notes.domain.markdown.StyleKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one thing about [MarkdownStyles] that is a *rule* rather than a lookup: **a done task's box is
 * not struck through.**
 *
 * `SpanPlanner` paints the box on top of the task's own span, so what the reader sees is the merge of
 * the two, not either alone. Asserting `TASK_BOX_DONE.textDecoration == None` in isolation would pass
 * while the rendering was still wrong — `SpanStyle` merge keeps the underlying value wherever the
 * overriding one is null, so a box style that simply omitted the decoration would inherit the
 * `LineThrough` and pass an isolated check. The merge is therefore what gets asserted.
 *
 * `SpanStyle.merge` is a **model** of what `AnnotatedString.addStyle` does with overlapping spans,
 * not the thing itself. The device screenshots at the end of 8c-b are what close that gap; this is
 * what makes the claim cheap to re-check on every build.
 *
 * Every other key here is a colour lookup with no logic in it, and pinning those would pin the suite
 * to the palette — the argument `GoldenSpanTest` already makes for asserting plans over rendered text.
 */
class MarkdownStylesTest {

    private val colors = DuckThemeSpec.DuckRed.colors(isDark = false)
    private val styles = MarkdownStyles(colors, duckType())

    @Test
    fun theDoneBoxIsNotStruckThroughByTheTaskItSitsOn() {
        val task = styles.spanStyle(StyleKey.TASK_DONE, 0)
        val box = styles.spanStyle(StyleKey.TASK_BOX_DONE, 0)

        // Precondition, or the test is vacuous: the strikethrough it must survive has to be there.
        assertEquals(
            "a completed task line is struck through — if this changed, this test proves nothing",
            TextDecoration.LineThrough,
            task.textDecoration,
        )
        assertEquals(
            "a struck-through checkbox is not a checkbox — the box must overrule the task's decoration",
            TextDecoration.None,
            task.merge(box).textDecoration,
        )
        assertEquals(
            "the done box reads accent, not the task's muted prose colour",
            colors.accentText,
            task.merge(box).color,
        )
    }

    @Test
    fun theOpenBoxReadsAsBodyProse() {
        val task = styles.spanStyle(StyleKey.TASK_OPEN, 0)
        val box = styles.spanStyle(StyleKey.TASK_BOX_OPEN, 0)
        assertEquals(colors.onSurface, task.merge(box).color)
    }
}
