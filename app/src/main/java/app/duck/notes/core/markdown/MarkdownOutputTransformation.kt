package app.duck.notes.core.markdown

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import app.duck.notes.domain.markdown.MarkdownTextCache
import app.duck.notes.domain.markdown.SpanPlanner

/**
 * Renders raw markdown as styled text, hiding syntax the caret is not inside.
 *
 * Contains no markdown knowledge of its own. The scan is [MarkdownTextCache]'s and the decisions are
 * [SpanPlanner]'s — both pure and both tested without Compose. This is only the replay, kept thin on
 * purpose: it is the one part of the engine that cannot be unit tested on the JVM.
 *
 * Compose derives the offset mapping between raw and rendered text from the deletions made here.
 * That is the whole reason this API was chosen over `VisualTransformation`, and
 * `ConcealMappingTest` verifies it end to end by editing through a concealed region.
 *
 * [cache] is passed in rather than owned so that a theme change — which produces a new [styles] and
 * therefore a new instance of this class — does not throw away the incremental scan. The caller
 * remembers the cache unkeyed and this keyed on the styles; `ThemeRepaintTest` is what established
 * that a new instance is sufficient to force a repaint with no keystroke.
 */
class MarkdownOutputTransformation(
    private val cache: MarkdownTextCache,
    private val styles: MarkdownStyles,
) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        val scan = cache.scan(originalText.toString())
        val selection = originalSelection
        val plan = SpanPlanner.plan(scan.tokens, selection.start, selection.end)

        // Conceal first, back-to-front. This ordering is REQUIRED FOR CORRECTNESS, not a
        // micro-optimisation: every range was computed against the original text, and removing one
        // shifts every later offset left by its length. Applying these forwards would delete the
        // wrong characters. (It also lets the gap buffer traverse once instead of thrashing, but
        // that is incidental — do not "simplify" this to ascending order.)
        plan.conceal.forEach { replace(it.first, it.last + 1, "") }

        // Styles afterwards, in rendered coordinates. TextFieldBuffer does not document whether a
        // style added before an edit survives it, so we never find out: by the time these are
        // applied the buffer is already in its final shape.
        SpanPlanner.renderedStyles(plan).forEach {
            addStyle(styles.spanStyle(it.style, it.level), it.start, it.end)
        }
    }
}
