package app.duck.notes.domain.markdown

import java.util.Locale

/** A tag occurrence in a body: `[start, end)` covers the whole token, `#` and any closing `#` included. */
data class TagSpan(val start: Int, val end: Int, val name: String)

/**
 * The one and only implementation of Duck's tag syntax.
 *
 * Three consumers depend on this agreeing with itself: `ParseTags` (what gets written to `note_tags`),
 * the editor's `MarkdownScanner` (what gets painted accent-red as you type), and the CommonMark
 * extension (what gets exported). They do not each parse tags — they all call this. A second tag
 * parser anywhere in the codebase is a bug, not an optimisation.
 *
 * Recognised: `#tag`, `#multi word tag#`, `#nested/tag`. Names are lowercased (the `tags.name` UNIQUE
 * index is a byte comparison, so `#Work` and `#work` must collapse to one row). Nested names stay
 * whole: `#a/b/c` is one tag, not three — the tree is derived from the separators by `ObserveTagTree`.
 *
 * Works in two passes, mask then scan, because `#` is overloaded: it also starts ATX headings, and
 * appears inside code and URL fragments. `ParseTagsTest` pins 37 cases against this; they are the
 * specification, not a regression net.
 */
object TagScanner {

    /** Every tag occurrence in [body], in source order. Duplicates are kept — callers dedupe. */
    fun scan(body: String): List<TagSpan> {
        val spans = mutableListOf<TagSpan>()
        var fenceChar: Char? = null
        var fenceLength = 0

        forEachLine(body) { line, offset ->
            val fence = fenceAt(line)
            if (fence != null) {
                val (char, length) = fence
                when {
                    fenceChar == null -> { fenceChar = char; fenceLength = length }
                    // A closing fence must use the same character and be at least as long.
                    char == fenceChar && length >= fenceLength -> { fenceChar = null; fenceLength = 0 }
                }
            } else if (fenceChar == null) {
                scanLine(line, offset, spans)
            }
        }
        return spans
    }

    /**
     * Splits on `\n`, `\r\n` and `\r` exactly as `CharSequence.lineSequence` does, but hands the
     * caller each line's absolute start offset — which `lineSequence` throws away and the editor
     * cannot do without.
     */
    private inline fun forEachLine(body: String, action: (line: String, offset: Int) -> Unit) {
        var start = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\n' || c == '\r') {
                action(body.substring(start, i), start)
                // Treat CRLF as one break, not two.
                i = if (c == '\r' && i + 1 < body.length && body[i + 1] == '\n') i + 2 else i + 1
                start = i
            } else {
                i++
            }
        }
        action(body.substring(start), start)
    }

    /** The ``` ``` ``` / `~~~` run opening or closing a fenced block, or null if this isn't a fence line. */
    fun fenceAt(line: String): Pair<Char, Int>? {
        var i = 0
        while (i < MAX_INDENT && i < line.length && line[i] == ' ') i++
        val char = line.getOrNull(i)?.takeIf { it == '`' || it == '~' } ?: return null
        var length = 0
        while (i + length < line.length && line[i + length] == char) length++
        return if (length >= MIN_FENCE) char to length else null
    }

    /** Appends the tags on a single [line] to [out], offsetting positions by [base]. */
    fun scanLine(line: String, base: Int, out: MutableList<TagSpan>) {
        val masked = codeSpanMask(line)
        var i = headingBodyStart(line)

        while (i < line.length) {
            if (line[i] != '#' || masked[i] || !isTagStart(line, i)) {
                i++
                continue
            }
            val next = line.getOrNull(i + 1)
            if (next == null || next.isWhitespace() || next == '#') {
                i++
                continue
            }

            // Closed form `#multi word tag#` first. The next `#` on the line closes it only if the
            // character before it isn't whitespace — that rejection is what makes `#a #b` two tags.
            val close = line.indexOf('#', i + 1)
            if (close > i + 1 && !masked[close] && !line[close - 1].isWhitespace()) {
                normalize(line.substring(i + 1, close))
                    ?.let { out += TagSpan(base + i, base + close + 1, it) }
                i = close + 1
                continue
            }

            var end = i + 1
            while (end < line.length && isTagChar(line[end])) end++
            normalize(line.substring(i + 1, end).trimEnd('/', '-'))
                ?.let { out += TagSpan(base + i, base + end, it) }
            i = if (end > i + 1) end else i + 1
        }
    }

    /**
     * Whether the `#` at [i] can open a tag, judged by what precedes it.
     *
     * This one rule retires every URL case without a URL parser: in `https://x.com/p#frag` and
     * `[a](/x#y)` the `#` follows `/`. It also covers escapes — `\` is not an accepted predecessor,
     * so `\#literal` is inert.
     */
    private fun isTagStart(line: String, i: Int): Boolean {
        if (i == 0) return true
        return when (line[i - 1]) {
            '[' -> true
            // `](` means a markdown link destination, so `[Jump](#introduction)` is an anchor, not a tag.
            '(' -> i < 2 || line[i - 2] != ']'
            else -> line[i - 1].isWhitespace()
        }
    }

    /**
     * Index just past an ATX heading marker, or 0. The rest of the line is still scanned, so
     * `## Sprint #q3` yields `q3`.
     */
    fun headingBodyStart(line: String): Int {
        var i = 0
        while (i < MAX_INDENT && i < line.length && line[i] == ' ') i++
        var end = i
        while (end < line.length && line[end] == '#' && end - i < MAX_HEADING_LEVEL) end++
        val level = end - i
        val terminated = end >= line.length || line[end] == ' '
        return if (level in 1..MAX_HEADING_LEVEL && terminated) end else 0
    }

    /**
     * Marks characters inside inline code spans. A run of N backticks opens a span that only a run
     * of exactly N backticks on the same line can close; an unclosed run marks nothing.
     */
    fun codeSpanMask(line: String): BooleanArray {
        val mask = BooleanArray(line.length)
        var i = 0
        while (i < line.length) {
            if (line[i] != '`') {
                i++
                continue
            }
            val open = runLength(line, i)
            var k = i + open
            var close = -1
            while (k < line.length) {
                if (line[k] == '`') {
                    val length = runLength(line, k)
                    if (length == open) { close = k; break }
                    k += length
                } else {
                    k++
                }
            }
            if (close < 0) {
                i += open
            } else {
                for (x in i until close + open) mask[x] = true
                i = close + open
            }
        }
        return mask
    }

    private fun runLength(line: String, from: Int): Int {
        var n = 0
        while (from + n < line.length && line[from + n] == '`') n++
        return n
    }

    private fun isTagChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '/'

    /** Collapses inner whitespace and lowercases; null for names that aren't really tags. */
    private fun normalize(raw: String): String? {
        val name = raw.trim().replace(WHITESPACE, " ")
        // All-digit names would turn `see issue #1` into a tag.
        if (name.isEmpty() || name.all(Char::isDigit)) return null
        return name.lowercase(Locale.ROOT)
    }

    private const val MAX_INDENT = 3
    private const val MIN_FENCE = 3
    private const val MAX_HEADING_LEVEL = 6
    private val WHITESPACE = Regex("\\s+")
}
