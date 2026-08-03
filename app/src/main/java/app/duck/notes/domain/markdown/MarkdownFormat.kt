package app.duck.notes.domain.markdown

/**
 * What the format toolbar can apply, and the [TokenKind] each one produces.
 *
 * Carrying the kind rather than the literal syntax is what keeps this from becoming a second
 * declaration of Duck's markdown: the literal comes from [MarkdownScanner.markerFor] /
 * [MarkdownScanner.prefixFor], which the scanner round-trip-checks against its own parser.
 */
enum class FormatAction(val kind: TokenKind) {
    BOLD(TokenKind.BOLD),
    ITALIC(TokenKind.ITALIC),
    CODE(TokenKind.CODE_SPAN),
    HIGHLIGHT(TokenKind.HIGHLIGHT),
    HEADING(TokenKind.HEADING),
    BULLET(TokenKind.BULLET),
    QUOTE(TokenKind.QUOTE),
    TASK(TokenKind.TASK_OPEN),
    LINK(TokenKind.LINK),
}

/** A rewritten body and where the selection lands in it. `start == end` is a caret. */
data class FormatResult(val body: String, val start: Int, val end: Int)

/**
 * The format toolbar's transform: a body, a selection and an action in; a body and a selection out.
 *
 * Pure and Compose-free, so all nine actions are pinned by plain JVM tests rather than by driving a
 * `BasicTextField` on an emulator. The composable above it does nothing but call this and hand the
 * result to `TextFieldState.edit`.
 *
 * ## It declares no syntax of its own
 *
 * Every literal it writes comes from [MarkdownScanner], and every "is this already formatted?"
 * question is answered by **scanning**, not by `startsWith`. That is not fastidiousness: `- [x] a` is
 * a task line, and a `startsWith("- [ ] ")` check would say it is not and prepend a second marker.
 * Scanning also makes removal exact — the marker range the scanner reports is what gets deleted, so
 * `##   Heading` and `>  quote` lose their whole marker rather than a hardcoded prefix length.
 *
 * ## Fences are refused, not worked around
 *
 * [isWritable] is false whenever the selection touches a fenced block, and the toolbar disables its
 * buttons on that. Applying "outside the fence" would split one action into several edits — several
 * undo entries — and a `**` written inside a code sample is literal text, not emphasis. Refusing is
 * both smaller and the only answer that cannot corrupt a code block.
 */
object MarkdownFormat {

    /**
     * Whether the toolbar may act on `[start, end]`.
     *
     * False when the range touches a fence, by asking the scanner which tokens are fence tokens
     * rather than by looking for backticks. Endpoints count as touching: a caret resting at the end
     * of a fence body is inside the block, and `[start, end]` is inclusive for exactly that reason.
     */
    fun isWritable(body: String, start: Int, end: Int): Boolean =
        isWritable(MarkdownTextCache.rescan(ScanResult.Empty, body), start, end)

    /**
     * The same question against a scan the caller already has.
     *
     * **This overload is the one the toolbar uses, and it is not a micro-optimisation.** The buttons'
     * enabled state is recomputed on every keystroke and every caret move, so going through the
     * `body` overload would run a *full* rescan of the note per frame — on the one surface already
     * carrying the unresolved 10k-word/60fps question. The editor's `MarkdownTextCache` is
     * incremental and already scanning that exact text for rendering, so handing its result in makes
     * the check free.
     *
     * It walks [ScanResult.lineTokens] rather than [ScanResult.tokens] for the same reason:
     * `tokens` is a `get()` that **flattens on every access**, so reading it once per caret move
     * would allocate a fresh list of every token in the note. The nesting is two loops instead of
     * one; the allocation is zero.
     */
    fun isWritable(scan: ScanResult, start: Int, end: Int): Boolean {
        val from = minOf(start, end)
        val to = maxOf(start, end)
        return scan.lineTokens.none { line ->
            line.any { it.kind in FENCE_KINDS && it.start <= to && it.end >= from }
        }
    }

    /**
     * Applies [action]. Returns [body] unchanged when the range is not writable, so a caller that
     * ignores [isWritable] still cannot corrupt a fence.
     */
    fun apply(body: String, start: Int, end: Int, action: FormatAction): FormatResult {
        val from = minOf(start, end)
        val to = maxOf(start, end)
        if (!isWritable(body, from, to)) return FormatResult(body, start, end)

        return when (action) {
            FormatAction.LINK -> link(body, from, to)
            else -> MarkdownScanner.markerFor(action.kind)
                ?.let { wrap(body, from, to, it) }
                ?: block(body, from, to, action.kind)
        }
    }

    // --- inline: wrap and unwrap --------------------------------------------

    /**
     * Wraps `[from, to)` in [marker], or unwraps when it is already wrapped — one button, both ways.
     *
     * The caret case falls out rather than being special-cased: `from == to` inserts `****` and lands
     * the caret between the halves, and pressing the same button again sees the two markers around an
     * empty selection and removes them. Toggle for free.
     */
    private fun wrap(body: String, from: Int, to: Int, marker: String): FormatResult {
        val width = marker.length
        val wrapped = from >= width && to + width <= body.length &&
            body.regionMatches(from - width, marker, 0, width) &&
            body.regionMatches(to, marker, 0, width)

        if (wrapped) {
            // Later edit first: removing the opening marker would shift the closing one.
            val out = body.removeRange(to, to + width).removeRange(from - width, from)
            return FormatResult(out, from - width, to - width)
        }
        val out = body.substring(0, from) + marker + body.substring(from, to) + marker + body.substring(to)
        return FormatResult(out, from + width, to + width)
    }

    /**
     * `[text]()` — the caret goes where the user still has to type.
     *
     * With a selection they have the text and need the destination, so the caret lands between the
     * parens. With a bare caret they have neither, and the text comes first.
     */
    private fun link(body: String, from: Int, to: Int): FormatResult {
        val text = body.substring(from, to)
        val out = body.substring(0, from) + "[" + text + "]()" + body.substring(to)
        val caret = if (from == to) from + 1 else from + text.length + 3
        return FormatResult(out, caret, caret)
    }

    // --- block: one marker per touched line ----------------------------------

    /**
     * Adds, removes or replaces the block marker on every line the selection touches.
     *
     * Three cases per line, decided by scanning it: it already carries this kind (remove), it carries
     * a different block kind (replace — otherwise `# ` on a bullet yields `# - item`), or it carries
     * none (add). **Remove wins only when *every* touched line already has the kind**, so a partial
     * selection completes rather than clears — the behaviour every list editor has.
     *
     * `TASK` matches both `TASK_OPEN` and `TASK_DONE`: `- [x] done` is already a task, and treating it
     * as unformatted would prepend a second marker to it.
     *
     * Edits are applied in **reverse line order**, for the same reason `TagScanner.rewrite` walks its
     * spans backwards: an edit on an earlier line shifts every later offset.
     */
    private fun block(body: String, from: Int, to: Int, kind: TokenKind): FormatResult {
        val prefix = MarkdownScanner.prefixFor(kind) ?: return FormatResult(body, from, to)
        val lines = touchedLines(body, from, to)
        val markers = lines.map { markerOf(body, it.first, it.last) }
        val allHaveIt = markers.all { it != null && it.kind.matches(kind) }

        var out = body
        var start = from
        var end = to
        lines.indices.reversed().forEach { i ->
            val marker = markers[i]
            val edit = when {
                allHaveIt -> Edit(marker!!.start, marker.end - marker.start, "")
                marker != null -> Edit(marker.start, marker.end - marker.start, prefix)
                else -> Edit(lines[i].first, 0, prefix)
            }
            out = out.substring(0, edit.at) + edit.inserted + out.substring(edit.at + edit.removed)
            start = edit.shift(start)
            end = edit.shift(end)
        }
        return FormatResult(out, start, end)
    }

    /** One line's block marker in absolute offsets, or null when the line carries none. */
    private fun markerOf(body: String, lineStart: Int, lineEnd: Int): BlockMarker? {
        val line = body.substring(lineStart, lineEnd)
        val (tokens, _) = MarkdownScanner.scanLine(line, lineStart, LineCarry())
        val token = tokens.firstOrNull { it.kind in BLOCK_KINDS } ?: return null
        val range = token.markers.firstOrNull() ?: return null
        return BlockMarker(token.kind, range.first, range.last + 1)
    }

    /** `[start, end)` of every line the selection touches, ascending. A caret touches one line. */
    private fun touchedLines(body: String, from: Int, to: Int): List<IntRange> {
        val lines = mutableListOf<IntRange>()
        var start = 0
        while (true) {
            val breakAt = body.indexOf('\n', start)
            val end = if (breakAt < 0) body.length else breakAt
            // `from <= end && to >= start` — endpoints touch, so a caret at a line break formats the
            // line it visually sits at the end of rather than silently doing nothing.
            if (from <= end && to >= start) lines += start..end
            if (breakAt < 0) break
            start = breakAt + 1
        }
        return lines
    }

    private data class BlockMarker(val kind: TokenKind, val start: Int, val end: Int)

    /** A single splice, and how an offset moves across it. */
    private data class Edit(val at: Int, val removed: Int, val inserted: String) {
        fun shift(offset: Int): Int = when {
            offset < at -> offset
            offset >= at + removed -> offset + inserted.length - removed
            // Inside what was removed: collapse to the end of what replaced it.
            else -> at + inserted.length
        }
    }

    /** `TASK` addresses both halves of a checkbox — `- [x] done` is already a task line. */
    private fun TokenKind.matches(action: TokenKind): Boolean = when (action) {
        TokenKind.TASK_OPEN -> this == TokenKind.TASK_OPEN || this == TokenKind.TASK_DONE
        else -> this == action
    }

    private val BLOCK_KINDS = setOf(
        TokenKind.HEADING,
        TokenKind.QUOTE,
        TokenKind.BULLET,
        TokenKind.ORDERED,
        TokenKind.TASK_OPEN,
        TokenKind.TASK_DONE,
    )

    private val FENCE_KINDS = setOf(TokenKind.FENCE_MARKER, TokenKind.FENCE_BODY)
}
