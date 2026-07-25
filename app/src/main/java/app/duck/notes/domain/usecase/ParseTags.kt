package app.duck.notes.domain.usecase

import java.util.Locale
import javax.inject.Inject

/**
 * Extracts tag names from a note body. The single source of truth for tag syntax — Phase 4's
 * CommonMark extension must agree with this or one of the two is wrong.
 *
 * Recognised: `#tag`, `#multi word tag#`, `#nested/tag`. Names are lowercased (the `tags.name`
 * UNIQUE index is a byte comparison, so `#Work` and `#work` must collapse to one row) and returned
 * deduped in first-appearance order. Nested names stay whole: `#a/b/c` is one tag, not three — the
 * tree is derived from the separators by `ObserveTagTree`.
 *
 * Works in two passes, mask then scan, because `#` is overloaded: it also starts ATX headings, and
 * appears inside code and URL fragments.
 */
class ParseTags @Inject constructor() {

    operator fun invoke(body: String): List<String> {
        val names = LinkedHashSet<String>()
        var fenceChar: Char? = null
        var fenceLength = 0

        for (line in body.lineSequence()) {
            val fence = fenceAt(line)
            if (fence != null) {
                val (char, length) = fence
                when {
                    fenceChar == null -> { fenceChar = char; fenceLength = length }
                    // A closing fence must use the same character and be at least as long.
                    char == fenceChar && length >= fenceLength -> { fenceChar = null; fenceLength = 0 }
                }
                continue
            }
            if (fenceChar == null) scan(line, names)
        }
        return names.toList()
    }

    /** The ``` ``` ``` / `~~~` run opening or closing a fenced block, or null if this isn't a fence line. */
    private fun fenceAt(line: String): Pair<Char, Int>? {
        var i = 0
        while (i < MAX_INDENT && i < line.length && line[i] == ' ') i++
        val char = line.getOrNull(i)?.takeIf { it == '`' || it == '~' } ?: return null
        var length = 0
        while (i + length < line.length && line[i + length] == char) length++
        return if (length >= MIN_FENCE) char to length else null
    }

    private fun scan(line: String, out: MutableSet<String>) {
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
                normalize(line.substring(i + 1, close))?.let(out::add)
                i = close + 1
                continue
            }

            var end = i + 1
            while (end < line.length && isTagChar(line[end])) end++
            normalize(line.substring(i + 1, end).trimEnd('/', '-'))?.let(out::add)
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
    private fun headingBodyStart(line: String): Int {
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
    private fun codeSpanMask(line: String): BooleanArray {
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

    private companion object {
        const val MAX_INDENT = 3
        const val MIN_FENCE = 3
        const val MAX_HEADING_LEVEL = 6
        val WHITESPACE = Regex("\\s+")
    }
}
