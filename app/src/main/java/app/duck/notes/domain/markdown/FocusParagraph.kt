package app.duck.notes.domain.markdown

/**
 * The paragraph the caret sits in, in **source** offsets — the input focus mode dims everything else
 * against.
 *
 * A paragraph is the maximal run of non-blank lines around the caret, bounded by blank lines (a line
 * that is empty or whitespace-only) or the ends of the note. [at] returns `null` when the caret is on
 * a blank line: it belongs to no paragraph, and focus mode then dims the whole note. That null is
 * load-bearing — it is a distinct state from "focus off" (which never calls this), and it is what
 * `SpanPlanner.plan` reads to decide "spare nothing".
 *
 * Pure and Compose-free, so it is a plain JVM unit test. Offsets are absolute into [text] — the same
 * SOURCE space `MarkdownScanner` and `SpanPlanner.plan` speak, so the DIMMED ranges built from this
 * ride the one shared `renderedStyles` rebase like every other style, and no source-vs-rendered
 * boundary is introduced.
 */
internal object FocusParagraph {

    /** [text][first]..[last] is the caret's paragraph, or `null` when the caret is on a blank line. */
    fun at(text: CharSequence, caret: Int): IntRange? {
        if (text.isEmpty()) return null
        val c = caret.coerceIn(0, text.length)

        // The caret's own line, as a half-open [lineStart, lineEnd) with the newline excluded.
        var lineStart = c
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        var lineEnd = c
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++

        // On a blank line the caret is in no paragraph.
        if (isBlank(text, lineStart, lineEnd)) return null

        // Grow up over non-blank lines, stopping at a blank line or the start.
        var start = lineStart
        while (start > 0) {
            val prevEnd = start - 1 // the '\n' that ends the previous line
            var prevStart = prevEnd
            while (prevStart > 0 && text[prevStart - 1] != '\n') prevStart--
            if (isBlank(text, prevStart, prevEnd)) break
            start = prevStart
        }

        // Grow down over non-blank lines, stopping at a blank line or the end.
        var end = lineEnd
        while (end < text.length) {
            val nextStart = end + 1 // skip the '\n'
            var nextEnd = nextStart
            while (nextEnd < text.length && text[nextEnd] != '\n') nextEnd++
            if (isBlank(text, nextStart, nextEnd)) break
            end = nextEnd
        }

        return start until end
    }

    /** Whitespace-only (or empty) spans count as blank — the paragraph separators. */
    private fun isBlank(text: CharSequence, start: Int, end: Int): Boolean {
        for (i in start until end) if (!text[i].isWhitespace()) return false
        return true
    }
}
