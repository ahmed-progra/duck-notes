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

    // --- rewriting bodies ---------------------------------------------------

    /**
     * Rewrites every occurrence of [from] **and its descendants** to [to]. Phase 7's rename.
     *
     * Descendants are included because a child's name literally contains its parent's:
     * `#projects/duck` after `projects` -> `work` has to become `#work/duck`, or the tree keeps a
     * `work` root beside an orphaned `projects/duck` branch. [scan] finds them all in one pass, so
     * this is `to + name.removePrefix(from)` rather than a second traversal.
     *
     * **This is why rename is not a regex.** A regex over `#work\b` resolves a URL fragment
     * (`/x#work`), an escaped `\#work`, an ATX heading marker, an inline code span and a fenced
     * block as tags — six body-corrupting disagreements with what the editor paints and what
     * `ParseTags` writes. Going through [scan] makes rename fence-correct and mask-correct for free,
     * and means the rule can only ever change in one place. Same argument as `MarkdownScanner`
     * reusing this for the Todo filter, and the inverse of the `[[WikiLink]]` defect Phase 9
     * documents.
     */
    fun rename(body: String, from: String, to: String): String =
        rewrite(body, from) { name -> to + name.removePrefix(from) }

    /**
     * Strips [name] from [body] — the exact tag only, **never its descendants**.
     *
     * Rename cascades and delete does not, and the asymmetry is forced rather than chosen: a child's
     * name contains its parent's, so renaming a parent without its children breaks the names, while
     * `projects/duck` stays a perfectly valid name after `projects` is deleted — it simply becomes an
     * intermediate segment nobody tagged directly, which the tree already models with a null
     * [app.duck.notes.domain.model.TagNode.tag]. Cascading here would strip `#projects/duck/ui` from
     * notes the user never named.
     */
    fun remove(body: String, name: String): String = rewrite(body, name, exact = true) { null }

    /**
     * The literal that scans back as [name] — `#name`, or `#name#` when the name contains a space.
     *
     * Callers must not build this themselves. The closed form is not decoration: `#multi word tag`
     * written open scans as `multi`, so an unclosed multi-word tag is a silently truncated one.
     */
    fun token(name: String): String = if (' ' in name) "#$name#" else "#$name"

    /**
     * Whether [name] is a tag name this scanner would produce.
     *
     * A **round trip**, not a validator: `scan(token(name))` must yield exactly one span named
     * [name]. Writing the rules out a second time is how the two drift, so the check is the
     * implementation checking its own output — the same trick `AddTagToNote` uses to catch a tag
     * appended into an unclosed fence.
     *
     * Falls out of it, with no rule restated here: `""`, `"123"` (all-digit names are not tags),
     * `"Work"` ([normalize] lowercases, so it cannot round-trip), `"a/"` and `"a-"` (trimmed),
     * `"a#b"` (closes early) and `" a"` (trimmed) are all rejected; `"a"`, `"a/b/c"` and
     * `"multi word"` are accepted.
     */
    fun isValidName(name: String): Boolean =
        scan(token(name)).singleOrNull()?.name == name

    /**
     * The one body-rewriting pass. [replacement] returns the new name, or null to delete the tag.
     *
     * Two things here are load-bearing:
     *
     * 1. **Spans are applied in reverse source order.** [scan] returns them ascending, and replacing
     *    an earlier span shifts every later offset — so a note with two tags on one line corrupts the
     *    second one if this walks forwards.
     * 2. **The original token's form is preserved.** A [TagSpan] covers the closing `#` when there is
     *    one, and `#` is not a tag character, so `body[end - 1] == '#'` distinguishes `#multi word
     *    tag#` from `#work` with no extra state. A new name containing a space forces the closed form
     *    regardless — [token] owns that, or the rewrite would truncate the tag it just wrote.
     */
    private inline fun rewrite(
        body: String,
        from: String,
        exact: Boolean = false,
        replacement: (String) -> String?,
    ): String {
        val spans = scan(body).filter {
            it.name == from || (!exact && it.name.startsWith("$from/"))
        }
        if (spans.isEmpty()) return body

        val out = StringBuilder(body)
        for (span in spans.asReversed()) {
            val name = replacement(span.name)
            if (name == null) {
                out.deleteTag(span.start, span.end)
            } else {
                val closed = body[span.end - 1] == '#'
                out.replace(span.start, span.end, if (closed) "#$name#" else token(name))
            }
        }
        return out.toString()
    }

    /**
     * Removes `[start, end)` and the whitespace it would strand.
     *
     * Deleting the token alone turns `buy milk #work` into `buy milk ` and `a #work b` into `a  b`.
     * Neither is visible, and both are the kind of drift that shows up as a diff nobody can explain
     * three commits later. So one adjacent space is eaten — the leading one when there is one, which
     * also leaves a tag alone on its own line as an empty line rather than a line of spaces.
     */
    private fun StringBuilder.deleteTag(start: Int, end: Int) {
        val leadingSpace = start > 0 && this[start - 1] == ' '
        val trailingSpace = end < length && this[end] == ' '
        when {
            leadingSpace -> delete(start - 1, end)
            trailingSpace -> delete(start, end + 1)
            else -> delete(start, end)
        }
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
