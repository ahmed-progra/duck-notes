package app.duck.notes.domain.markdown

/**
 * Carry state between lines. A fenced block is the only construct in Duck's dialect whose meaning
 * depends on preceding lines, so this is all the context a line needs to be scanned in isolation —
 * which is what makes the incremental rescan in [MarkdownTextCache] possible.
 */
data class LineCarry(val fenceChar: Char? = null, val fenceLength: Int = 0) {
    val inFence get() = fenceChar != null
}

/**
 * Duck's markdown, scanned one line at a time.
 *
 * Deliberately not CommonMark. This runs on every keystroke, so it trades the spec's full emphasis
 * resolution for a single left-to-right pass that never looks past the current line. `DuckMarkdown`
 * is the real parser and handles export; this handles typing. Where they can disagree — tags — both
 * call [TagScanner], so they cannot.
 *
 * Known and accepted divergences from CommonMark: emphasis does not span lines, nested emphasis of
 * the same character is not resolved by run length, and link destinations are not validated. All
 * three are invisible while typing and corrected on export.
 */
object MarkdownScanner {

    /**
     * Does [body] contain at least one **open** task line? The sidebar's Todo filter, Phase 6.
     *
     * A third view over [scanLine] rather than a `- [ ]` regex beside it. The regex would be shorter
     * and would be a second implementation of one syntax — which is the defect Phase 9 documents for
     * `[[WikiLink]]`, where `ResolveWikiLinks` and this scanner disagree about fences. Going through
     * [scanLine] means the filter is fence-correct for free: a `- [ ]` inside a fenced block yields
     * `FENCE_BODY` and never reaches the block prefix, so it does not count. It also means the rule
     * can only ever change in one place.
     *
     * Short-circuits on the first hit, so the common case reads a few lines rather than the note.
     */
    fun hasOpenTask(body: String): Boolean {
        var carry = LineCarry()
        var base = 0
        for (line in body.lineSequence()) {
            val (tokens, next) = scanLine(line, base, carry)
            if (tokens.any { it.kind == TokenKind.TASK_OPEN }) return true
            carry = next
            base += line.length + 1
        }
        return false
    }

    /** Scans [line], which starts at absolute [base], under [carry]. Returns its tokens and the next carry. */
    fun scanLine(line: String, base: Int, carry: LineCarry): Pair<List<Token>, LineCarry> {
        val tokens = mutableListOf<Token>()

        TagScanner.fenceAt(line)?.let { (char, length) ->
            val next = when {
                !carry.inFence -> LineCarry(char, length)
                char == carry.fenceChar && length >= carry.fenceLength -> LineCarry()
                else -> carry
            }
            // A fence line that neither opens nor closes is just body text inside the block.
            //
            // FENCE_MARKER carries no conceal ranges on purpose, so ``` stays visible. The phase
            // spec says to hide backticks, and for inline code we do — but hiding a fence would
            // leave two blank lines where the block's boundary was, because a SpanStyle cannot draw
            // a block background to replace it. Visible fences read better than invisible ones until
            // the code block gets real decoration drawing in Phase 5/8. Pinned by
            // GoldenSpanTest.fencedBlockIsNotStyledAsMarkdown.
            return if (next != carry) {
                listOf(Token(TokenKind.FENCE_MARKER, base, base + line.length)) to next
            } else {
                listOf(Token(TokenKind.FENCE_BODY, base, base + line.length)) to carry
            }
        }

        if (carry.inFence) {
            return listOf(Token(TokenKind.FENCE_BODY, base, base + line.length)) to carry
        }

        val bodyStart = scanBlockPrefix(line, base, tokens)
        scanInline(line, base, bodyStart, tokens)
        return tokens to carry
    }

    /**
     * Consumes the leading block marker and appends its token. Returns the index within [line] where
     * inline scanning should begin — markers are not themselves scanned for emphasis.
     */
    private fun scanBlockPrefix(line: String, base: Int, out: MutableList<Token>): Int {
        var i = 0
        while (i < MAX_INDENT && i < line.length && line[i] == ' ') i++

        if (isDivider(line, i)) {
            out += Token(TokenKind.DIVIDER, base, base + line.length)
            return line.length
        }

        if (line.getOrNull(i) == '>') {
            val content = skipSpace(line, i + 1)
            out += Token(TokenKind.QUOTE, base + i, base + line.length, listOf(base + i until base + content))
            // Markers stack: `> - [ ] text` is a task inside a quote.
            return scanBlockPrefix(line.substring(content), base + content, out) + content
        }

        val heading = TagScanner.headingBodyStart(line)
        if (heading > 0) {
            val content = skipSpace(line, heading)
            out += Token(
                kind = TokenKind.HEADING,
                start = base + i,
                end = base + line.length,
                markers = listOf(base + i until base + content),
                level = heading - i,
            )
            return content
        }

        val bullet = line.getOrNull(i)
        if ((bullet == '-' || bullet == '*' || bullet == '+') && line.getOrNull(i + 1) == ' ') {
            val content = skipSpace(line, i + 1)
            val task = taskAt(line, content)
            if (task != null) {
                val (kind, afterBox) = task
                // Through the space after `]`, not just the box — every other block marker eats its
                // trailing space, and leaving this one would indent concealed task lines by one.
                val body = skipSpace(line, afterBox)
                out += Token(kind, base + i, base + line.length, listOf(base + i until base + body))
                return body
            }
            out += Token(TokenKind.BULLET, base + i, base + line.length, listOf(base + i until base + content))
            return content
        }

        val ordered = orderedMarkerEnd(line, i)
        if (ordered > 0) {
            val content = skipSpace(line, ordered)
            out += Token(TokenKind.ORDERED, base + i, base + line.length, listOf(base + i until base + content))
            return content
        }
        return i
    }

    /** `[ ]` / `[x]` at [at], returning its kind and the index just past the closing bracket. */
    private fun taskAt(line: String, at: Int): Pair<TokenKind, Int>? {
        if (at + 2 >= line.length || line[at] != '[' || line[at + 2] != ']') return null
        return when (line[at + 1]) {
            ' ' -> TokenKind.TASK_OPEN to at + 3
            'x', 'X' -> TokenKind.TASK_DONE to at + 3
            else -> null
        }
    }

    private fun orderedMarkerEnd(line: String, from: Int): Int {
        var i = from
        while (i < line.length && line[i].isDigit()) i++
        if (i == from) return 0
        if (line.getOrNull(i) != '.' && line.getOrNull(i) != ')') return 0
        return if (line.getOrNull(i + 1) == ' ') i + 1 else 0
    }

    /** `---`, `***` or `___`, three or more, nothing else on the line. */
    private fun isDivider(line: String, from: Int): Boolean {
        val char = line.getOrNull(from)?.takeIf { it == '-' || it == '*' || it == '_' } ?: return false
        var count = 0
        for (i in from until line.length) {
            when (line[i]) {
                char -> count++
                ' ' -> Unit
                else -> return false
            }
        }
        return count >= MIN_DIVIDER
    }

    private fun skipSpace(line: String, from: Int): Int {
        var i = from
        while (i < line.length && line[i] == ' ') i++
        return i
    }

    /**
     * Single left-to-right pass over the line body. Code spans win over everything (their mask is
     * computed first), then links and wiki-links, then paired emphasis delimiters, then tags.
     */
    private fun scanInline(line: String, base: Int, from: Int, out: MutableList<Token>) {
        val masked = TagScanner.codeSpanMask(line)
        emitCodeSpans(line, base, from, masked, out)

        // Tags come from the shared scanner so the editor and note_tags can never disagree.
        val tagSpans = mutableListOf<TagSpan>()
        TagScanner.scanLine(line, 0, tagSpans)
        tagSpans.forEach { if (it.start >= from) out += Token(TokenKind.TAG, base + it.start, base + it.end) }

        var i = from
        while (i < line.length) {
            if (masked[i]) { i++; continue }

            val wiki = wikiLinkAt(line, i)
            if (wiki != null) {
                out += Token(
                    TokenKind.WIKI_LINK, base + i, base + wiki,
                    listOf(base + i until base + i + 2, base + wiki - 2 until base + wiki),
                )
                i = wiki
                continue
            }

            val link = linkAt(line, i)
            if (link != null) {
                val (end, textStart, textEnd) = link
                out += Token(
                    kind = if (line[i] == '!') TokenKind.IMAGE else TokenKind.LINK,
                    start = base + i,
                    end = base + end,
                    markers = listOf(base + i until base + textStart, base + textEnd until base + end),
                )
                i = end
                continue
            }

            val delimiter = delimiterAt(line, i)
            if (delimiter != null) {
                val (kind, width) = delimiter
                val close = findClose(line, i + width, line.substring(i, i + width), masked)
                if (close > 0) {
                    out += Token(
                        kind, base + i, base + close + width,
                        listOf(base + i until base + i + width, base + close until base + close + width),
                    )
                    i = close + width
                    continue
                }
            }
            i++
        }
    }

    private fun emitCodeSpans(
        line: String,
        base: Int,
        from: Int,
        masked: BooleanArray,
        out: MutableList<Token>,
    ) {
        var i = from
        while (i < line.length) {
            if (!masked[i]) { i++; continue }
            var end = i
            while (end < line.length && masked[end]) end++
            var ticks = 0
            while (i + ticks < line.length && line[i + ticks] == '`') ticks++
            out += Token(
                TokenKind.CODE_SPAN, base + i, base + end,
                listOf(base + i until base + i + ticks, base + end - ticks until base + end),
            )
            i = end
        }
    }

    private fun wikiLinkAt(line: String, i: Int): Int? {
        if (!line.startsWith("[[", i)) return null
        val close = line.indexOf("]]", i + 2)
        if (close < 0) return null
        // `[[a]]` may not contain a bracket, matching ResolveWikiLinks' `[^\[\]]+`.
        for (k in i + 2 until close) if (line[k] == '[' || line[k] == ']') return null
        return if (close > i + 2) close + 2 else null
    }

    /** `[text](dest)` or `![alt](dest)` at [i] → (end, textStart, textEnd-at-`]`). */
    private fun linkAt(line: String, i: Int): Triple<Int, Int, Int>? {
        val open = if (line[i] == '!') i + 1 else i
        if (line.getOrNull(open) != '[') return null
        if (line.startsWith("[[", open)) return null
        val close = line.indexOf(']', open + 1)
        if (close < 0 || line.getOrNull(close + 1) != '(') return null
        val dest = line.indexOf(')', close + 2)
        if (dest < 0) return null
        return Triple(dest + 1, open + 1, close)
    }

    private fun delimiterAt(line: String, i: Int): Pair<TokenKind, Int>? = when {
        line.startsWith("**", i) -> TokenKind.BOLD to 2
        line.startsWith("__", i) -> TokenKind.BOLD to 2
        line.startsWith("~~", i) -> TokenKind.STRIKE to 2
        line.startsWith("==", i) -> TokenKind.HIGHLIGHT to 2
        line[i] == '*' || line[i] == '_' -> TokenKind.ITALIC to 1
        else -> null
    }

    /** Index of the matching closing delimiter, or -1. An unclosed delimiter is inert, never concealed. */
    private fun findClose(line: String, from: Int, delimiter: String, masked: BooleanArray): Int {
        var i = from
        while (i <= line.length - delimiter.length) {
            if (!masked[i] && line.startsWith(delimiter, i)) return i
            i++
        }
        return -1
    }

    private const val MAX_INDENT = 3
    private const val MIN_DIVIDER = 3
}
