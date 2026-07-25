package app.duck.notes.domain.markdown

/** What a range should look like. Resolved to real Compose styles by `MarkdownStyles`. */
enum class StyleKey {
    HEADING,
    QUOTE,
    BULLET,
    ORDERED,
    TASK_OPEN,
    TASK_DONE,
    DIVIDER,
    CODE_BLOCK,
    BOLD,
    ITALIC,
    STRIKE,
    HIGHLIGHT,
    CODE_SPAN,
    LINK,
    IMAGE,
    WIKI_LINK,
    TAG,

    /** Revealed syntax — the `**` you can see because the caret is inside the node. Muted, not hidden. */
    MARKER,
}

/** [start, end) painted with [style]. [level] carries heading depth 1..6, else 0. */
data class StyledRange(val start: Int, val end: Int, val style: StyleKey, val level: Int = 0)

/**
 * Everything the editor needs to render one frame: what to paint, and what to hide.
 *
 * [conceal] is sorted **descending** and must be non-overlapping. That ordering is a correctness
 * requirement, not a performance tweak — see [SpanPlanner].
 */
data class SpanPlan(val styles: List<StyledRange>, val conceal: List<IntRange>)

/**
 * Turns a scan plus a caret into a frame.
 *
 * Pure and free of Compose, so the golden and invariant suites are plain JVM tests that run in
 * milliseconds without an emulator. The Compose-facing half is `MarkdownOutputTransformation`, which
 * does nothing but replay what this produces.
 */
object SpanPlanner {

    /**
     * A node's markers are hidden unless the selection touches the node.
     *
     * Endpoints count as touching, so a caret resting immediately before `**` or immediately after
     * the closing `**` still reveals it — otherwise the markers would vanish out from under you at
     * the exact moment you were trying to type next to them.
     */
    fun plan(tokens: List<Token>, selectionStart: Int, selectionEnd: Int): SpanPlan {
        val styles = mutableListOf<StyledRange>()
        val conceal = mutableListOf<IntRange>()
        val from = minOf(selectionStart, selectionEnd)
        val to = maxOf(selectionStart, selectionEnd)

        for (token in tokens) {
            styles += StyledRange(token.start, token.end, styleFor(token.kind), token.level)
            if (token.markers.isEmpty()) continue

            val revealed = from <= token.end && to >= token.start
            if (revealed) {
                // Painted after the node style so the muted marker colour wins over it.
                token.markers.forEach { styles += StyledRange(it.first, it.last + 1, StyleKey.MARKER) }
            } else {
                conceal += token.markers
            }
        }
        // Descending, so that applying a deletion never shifts a range still waiting to be applied.
        conceal.sortByDescending { it.first }
        return SpanPlan(styles, conceal)
    }

    /**
     * [SpanPlan.styles] rewritten into *rendered* coordinates — offsets into the text as it looks
     * once [SpanPlan.conceal] has been removed.
     *
     * This exists so the transformation can delete first and style afterwards. `TextFieldBuffer`
     * does not document whether a style added before an edit is carried through it, and rather than
     * depend on an answer we cannot unit test, we hand it offsets that are already final. Correct
     * either way, and the arithmetic is testable on the JVM.
     *
     * Note this is *not* the offset mapping we avoided by picking this API: it is a one-way shift of
     * our own spans through our own deletions. Compose still owns the bidirectional caret mapping.
     */
    fun renderedStyles(plan: SpanPlan): List<StyledRange> {
        if (plan.conceal.isEmpty()) return plan.styles
        val cuts = plan.conceal.sortedBy { it.first }

        fun rebase(offset: Int): Int {
            var removed = 0
            for (cut in cuts) {
                if (cut.first >= offset) break
                // Only the part of the cut that lies before `offset` shifts it.
                removed += minOf(offset, cut.last + 1) - cut.first
            }
            return offset - removed
        }

        return plan.styles.mapNotNull {
            val start = rebase(it.start)
            val end = rebase(it.end)
            // A node swallowed entirely by concealment has nothing left to paint.
            if (end > start) StyledRange(start, end, it.style, it.level) else null
        }
    }

    private fun styleFor(kind: TokenKind) = when (kind) {
        TokenKind.HEADING -> StyleKey.HEADING
        TokenKind.QUOTE -> StyleKey.QUOTE
        TokenKind.BULLET -> StyleKey.BULLET
        TokenKind.ORDERED -> StyleKey.ORDERED
        TokenKind.TASK_OPEN -> StyleKey.TASK_OPEN
        TokenKind.TASK_DONE -> StyleKey.TASK_DONE
        TokenKind.DIVIDER -> StyleKey.DIVIDER
        TokenKind.FENCE_MARKER, TokenKind.FENCE_BODY -> StyleKey.CODE_BLOCK
        TokenKind.BOLD -> StyleKey.BOLD
        TokenKind.ITALIC -> StyleKey.ITALIC
        TokenKind.STRIKE -> StyleKey.STRIKE
        TokenKind.HIGHLIGHT -> StyleKey.HIGHLIGHT
        TokenKind.CODE_SPAN -> StyleKey.CODE_SPAN
        TokenKind.LINK -> StyleKey.LINK
        TokenKind.IMAGE -> StyleKey.IMAGE
        TokenKind.WIKI_LINK -> StyleKey.WIKI_LINK
        TokenKind.TAG -> StyleKey.TAG
    }
}
