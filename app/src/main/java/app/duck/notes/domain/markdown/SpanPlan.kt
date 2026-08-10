package app.duck.notes.domain.markdown

/**
 * What a range should look like. Resolved to real Compose styles by `MarkdownStyles`.
 *
 * **Nothing pins the size of this enum, and adding a pin would be the wrong instinct.**
 * `MarkdownStyles.spanStyle` is an exhaustive `when` over it, so a key that nobody painted does not
 * compile — a guarantee strictly stronger than an assertion, and one that cannot go stale. Contrast
 * `ContrastTest.duckPairs`, which *is* pinned by count: that is a hand-maintained list of pairs with
 * no compiler behind it, so a row silently dropped is invisible without the count. Here the compiler
 * is the count. `StyleKey.entries` and `.values` appear nowhere in `src/`; keep it that way.
 */
enum class StyleKey {
    HEADING,
    QUOTE,
    BULLET,
    ORDERED,
    TASK_OPEN,
    TASK_DONE,

    /**
     * The `[ ]` / `[x]` inside a task marker, painted rather than concealed — Phase 8c-b.
     *
     * Two keys rather than one because [TASK_DONE] paints the whole token with a `LineThrough`, and
     * a struck-through checkbox is not a checkbox. The box's own style has to overrule it, and a
     * single key could not carry both the open and done colour.
     */
    TASK_BOX_OPEN,
    TASK_BOX_DONE,

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

    /**
     * Focus mode: every span outside the caret's paragraph, painted `proseMuted` so the current
     * paragraph reads and the rest recedes — Phase 8d-d.
     *
     * Reuses the already-floored `proseMuted` token, so no new colour pairing enters `ContrastTest`.
     * Emitted **after** the token styles in [SpanPlan.styles] so its colour overrides theirs, and in
     * SOURCE offsets so [SpanPlanner.renderedStyles] rebases it with everything else.
     */
    DIMMED,
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
 * One tappable `[ ]` / `[x]`, in **both** coordinate spaces at once.
 *
 * The editor's overlay needs all four of these and no two come from the same place, which is why
 * this is a type rather than four parallel lists:
 *
 * - [rendered] positions the overlay, via `TextLayoutResult.getBoundingBox`, which only speaks
 *   rendered offsets.
 * - [source] is what `MarkdownScanner.toggleTaskAt` takes — the buffer is raw markdown.
 * - [done] is the `Modifier.toggleable` value, and therefore the checked state TalkBack reads.
 * - [text] is the task's own words, so the overlay announces *"count them, checkbox, not checked"*
 *   rather than an anonymous control. Empty for a task line with nothing after the marker.
 *
 * [rendered] is only valid for the [SpanPlan] it was computed from: the conceal moves with the
 * caret, so these shift as the caret enters and leaves the line. [source] does not.
 */
data class TaskBox(
    val source: IntRange,
    val rendered: IntRange,
    val done: Boolean,
    val text: String,
)

/**
 * One tappable `[[WikiLink]]` — Phase 9d, and the type that keeps the tap out of rendered space.
 *
 * The overlay built from this **carries its own [title]**, so a tap never has to be turned back into
 * an offset. That is the whole design: `SpanPlanner.renderedOffset` maps forward, source → rendered,
 * and the question *"which link was tapped"* is answered by which overlay was hit rather than by
 * inverting anything. There is no inverse of `renderedOffset` in this codebase and 9d does not add
 * one; a hand-rolled inverse would be a second implementation of a mapping that already has a home.
 *
 * - [rendered] is the **inner title only**, because the editor conceals both bracket pairs whenever
 *   the caret is outside the node. For `[[Pond]]` the source span is eight characters and the
 *   rendered span is four. An overlay built from the source span would sit two characters off at each
 *   end, which nothing on screen would look wrong enough to catch.
 * - [source] is the whole span, brackets included — what an edit would replace.
 * - [title] is what the tap resolves and, failing that, creates. Trimmed by `WikiLinkScanner.titleAt`
 *   so it matches what a save wrote to `note_links`.
 *
 * Like [TaskBox], [rendered] is only valid for the [SpanPlan] it came from.
 */
data class WikiLinkTarget(
    val source: IntRange,
    val rendered: IntRange,
    val title: String,
)

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
    fun plan(
        tokens: List<Token>,
        selectionStart: Int,
        selectionEnd: Int,
        foldedRanges: List<IntRange> = emptyList(),
        focusMode: Boolean = false,
        focusedParagraph: IntRange? = null,
        sourceLength: Int = 0,
    ): SpanPlan {
        val styles = mutableListOf<StyledRange>()
        val conceal = mutableListOf<IntRange>()
        val from = minOf(selectionStart, selectionEnd)
        val to = maxOf(selectionStart, selectionEnd)

        for (token in tokens) {
            styles += StyledRange(token.start, token.end, styleFor(token.kind), token.level)
            if (token.markers.isEmpty()) continue

            if (revealed(token, from, to)) {
                // Painted after the node style so the muted marker colour wins over it.
                token.markers.forEach { styles += StyledRange(it.first, it.last + 1, StyleKey.MARKER) }
            } else {
                token.markers.forEach { marker -> carve(marker, token.box)?.let { conceal += it } }
            }

            // The box is painted at **every** caret position, not only the concealed one. A task line
            // must not change shape as the caret arrives on it — the overlay in `MarkdownEditorScreen`
            // is positioned from this box's rendered offsets, and a box that moved when focused would
            // put the tap target somewhere the user is not looking. Painted last so it overrules both
            // the node style and a revealed MARKER.
            token.box?.let { styles += StyledRange(it.first, it.last + 1, boxStyleFor(token.kind)) }
        }
        conceal += foldedRanges
        // A folded body also contains markdown markers, so a fold range genuinely can enclose marker
        // ranges. Merge the two sources: applying two OVERLAPPING deletes back-to-front corrupts the
        // offset map, because the first delete shifts text the second was measured against.
        //
        // The test is `next.first <= previous.last`, i.e. a true overlap. It is deliberately NOT
        // `previous.last + 1`, which would also coalesce merely ADJACENT ranges. Adjacent ranges are
        // already safe — descending deletion of [6..25] then [26..27] touches disjoint text — so
        // coalescing them buys no correctness and costs something real: it changes `conceal`'s shape
        // for every note in the app, folded or not, and it does so by making the invariant suite's
        // ordering and overlap assertions *easier* to satisfy. That is the direction that hides
        // defects, which is why the wider form is not worth its convenience.
        val merged = conceal.sortedBy { it.first }.fold(mutableListOf<IntRange>()) { ranges, next ->
            val previous = ranges.lastOrNull()
            if (previous != null && next.first <= previous.last) {
                ranges[ranges.lastIndex] = previous.first..maxOf(previous.last, next.last)
            } else {
                ranges += next
            }
            ranges
        }
        // Descending, so that applying a deletion never shifts a range still waiting to be applied.
        conceal.clear()
        conceal += merged.asReversed()

        // Focus mode dims everything outside the caret's paragraph. Appended AFTER the token styles so
        // its colour overrules them, and in SOURCE offsets so renderedStyles rebases it with the rest
        // — no separate source-vs-rendered path. A null [focusedParagraph] under focus (the caret on a
        // blank line) spares nothing, which is why the two states are distinct params rather than one.
        if (focusMode) {
            if (focusedParagraph == null) {
                if (sourceLength > 0) styles += StyledRange(0, sourceLength, StyleKey.DIMMED)
            } else {
                if (focusedParagraph.first > 0) {
                    styles += StyledRange(0, focusedParagraph.first, StyleKey.DIMMED)
                }
                val afterFocus = focusedParagraph.last + 1
                if (afterFocus < sourceLength) {
                    styles += StyledRange(afterFocus, sourceLength, StyleKey.DIMMED)
                }
            }
        }
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

        return plan.styles.mapNotNull {
            val start = rebase(it.start, cuts)
            val end = rebase(it.end, cuts)
            // A node swallowed entirely by concealment has nothing left to paint.
            if (end > start) StyledRange(start, end, it.style, it.level) else null
        }
    }

    /**
     * Every tappable task box in [tokens], for the frame [plan] describes.
     *
     * **This lives here rather than in the composable that consumes it**, and the reason is the one
     * this object's own KDoc gives: everything in this file is testable on the JVM in milliseconds.
     * These four values decide *where a tap target lands*, and assembling them inside
     * `MarkdownEditorScreen` would put that arithmetic somewhere only an emulator could reach.
     *
     * The alternative considered and rejected was to filter [renderedStyles] to the two `TASK_BOX`
     * keys and index-zip it against `tokens.filter { it.box != null }`. That is correct today, and
     * correct only because a box is never concealed so the two lists stay length- and order-aligned
     * — an invariant nothing states and nothing checks.
     *
     * [body] is only read for [TaskBox.text]; the offsets come from the scan.
     */
    fun taskBoxes(body: String, tokens: List<Token>, plan: SpanPlan): List<TaskBox> {
        val cuts = plan.conceal.sortedBy { it.first }
        return tokens.mapNotNull { token ->
            val box = token.box ?: return@mapNotNull null
            // The last marker, not the first: `> - [ ] x` stacks a quote marker before the task's.
            val marker = token.markers.lastOrNull() ?: return@mapNotNull null
            val renderedStart = rebase(box.first, cuts)
            val renderedEnd = rebase(box.last + 1, cuts)
            if (renderedEnd <= renderedStart) return@mapNotNull null
            TaskBox(
                source = box,
                rendered = renderedStart until renderedEnd,
                done = token.kind == TokenKind.TASK_DONE,
                text = body.substring(marker.last + 1, token.end),
            )
        }
    }

    /**
     * Every tappable wiki link in [tokens], for the frame [plan] describes — Phase 9d.
     *
     * Sits beside [taskBoxes] and for the reason that function's KDoc gives: these values decide
     * **where a tap target lands**, and assembling them inside a composable would put that arithmetic
     * somewhere only an emulator could reach. Here it is a pure function, which is why 9d's two
     * standing offset regression tests — right-to-left and non-BMP — are JVM tests. Only the
     * rendered-offset → screen-rect leg needs a device.
     *
     * ## A revealed link is deliberately absent, and that absence is the routing rule
     *
     * When the caret is inside a link its brackets are showing and the user is editing it, so no
     * target is emitted, no overlay is drawn, and the tap reaches `BasicTextField` and places the
     * caret. When it is outside, the link is concealed and the overlay navigates. **The presence of
     * the target is the decision** — there is no second rule about when a tap means what, and no
     * event consumption to get wrong. [revealed] is the same function [plan] uses to decide whether
     * to conceal, so the two can never disagree about which state a link is in.
     *
     * That also answers how a link is edited: tap *beside* it. Endpoints count as touching, so a caret
     * immediately before or after reveals the link and removes the overlay, and the next tap lands in
     * the text.
     *
     * [body] is only read for [WikiLinkTarget.title]; the offsets come from the scan.
     */
    fun wikiLinkTargets(
        body: String,
        tokens: List<Token>,
        plan: SpanPlan,
        selectionStart: Int,
        selectionEnd: Int,
    ): List<WikiLinkTarget> {
        val cuts = plan.conceal.sortedBy { it.first }
        val from = minOf(selectionStart, selectionEnd)
        val to = maxOf(selectionStart, selectionEnd)
        return tokens.mapNotNull { token ->
            if (token.kind != TokenKind.WIKI_LINK) return@mapNotNull null
            if (revealed(token, from, to)) return@mapNotNull null
            val source = token.start until token.end
            // The inner span, from the scanner rather than from `+ 2` here — see WikiLinkScanner.
            //
            // **No plant distinguishes this from passing `source`, and that is a property rather than
            // an oversight.** Under concealment the two are provably equal: `rebase(token.start)`
            // breaks on the `[[` cut before subtracting it, while `rebase(token.start + 2)` subtracts
            // exactly 2 — and the same cancellation happens at the `]]` end. Both map to the same
            // rendered offset. Swapping in `source` was run as a plant and came back green.
            //
            // It stays because it is the correct *statement* — the target is the title, not the
            // brackets — and because the equality holds only while revealed links are skipped above.
            // Emit a target for a revealed link and the two immediately diverge, which is what the
            // sibling plant on `revealed` demonstrates from the other side.
            val inner = WikiLinkScanner.innerOf(source)
            val renderedStart = rebase(inner.first, cuts)
            val renderedEnd = rebase(inner.last + 1, cuts)
            // A link swallowed by a fold has nothing left to tap. Same guard `taskBoxes` carries.
            if (renderedEnd <= renderedStart) return@mapNotNull null
            WikiLinkTarget(
                source = source,
                rendered = renderedStart until renderedEnd,
                title = WikiLinkScanner.titleAt(body, source),
            )
        }
    }

    /**
     * Is [token]'s syntax showing? True when the selection `[from, to]` touches it at all.
     *
     * **Endpoints count**, which is the whole of it: a caret resting immediately before `**` or
     * immediately after the closing `**` still reveals it, because otherwise the markers would vanish
     * out from under you at the exact moment you were trying to type next to them.
     *
     * Extracted in 9d rather than left inline in [plan], because [wikiLinkTargets] needs the same
     * answer and a second copy of `from <= end && to >= start` would be two rules that agree until
     * one of them is edited. It is also the **routing rule for a tap**: a revealed link gets no
     * overlay, so the tap reaches the text field and places the caret instead of navigating.
     */
    internal fun revealed(token: Token, from: Int, to: Int): Boolean =
        from <= token.end && to >= token.start

    /**
     * [offset] shifted left by every concealed character before it. [cuts] must be **ascending**.
     *
     * Shared by [renderedStyles] and [taskBoxes] rather than written twice: an overlay positioned by
     * a second, subtly different rebase would sit next to the box instead of on it, and the two
     * would drift the first time the conceal rules changed — which is exactly what 8c-b did.
     */
    internal fun renderedOffset(offset: Int, cuts: List<IntRange>): Int = rebase(offset, cuts)

    private fun rebase(offset: Int, cuts: List<IntRange>): Int {
        var removed = 0
        for (cut in cuts) {
            if (cut.first >= offset) break
            // Only the part of the cut that lies before `offset` shifts it.
            removed += minOf(offset, cut.last + 1) - cut.first
        }
        return offset - removed
    }

    /**
     * [marker] minus the task box it contains, or [marker] unchanged when there is none.
     *
     * `- [ ] milk` has one marker range covering all six leading characters, and Phase 4 concealed it
     * whole — so the line rendered as `milk` and there was nothing on screen to tap. `TaskTapSpikeTest`
     * measured what that costs: the six source offsets collapse onto one rendered position, so no tap
     * can tell the box from the bullet. Splitting the range is the whole fix.
     *
     * What survives the cut is **the box and the space after `]`**: `- ` hides, `[ ] ` stays. Keeping
     * the space is not cosmetic — without it the line reads `[ ]milk`.
     *
     * [box] comes from [Token.box], which the scanner carried out of `taskAt`. Deriving it here as
     * `marker.first + 2` would be right on `- [ ]` and wrong on the legal `-   [ ]`, and wrong by
     * shifting the tap target onto a space rather than by failing.
     *
     * Returns null when the cut leaves nothing, so no empty range can reach [SpanPlan.conceal] —
     * `ConcealInvariantTest` rejects those, and an empty deletion would also give Compose a
     * degenerate mapping.
     */
    private fun carve(marker: IntRange, box: IntRange?): IntRange? {
        if (box == null || box.first !in marker) return marker
        return if (box.first > marker.first) marker.first until box.first else null
    }

    private fun boxStyleFor(kind: TokenKind) = when (kind) {
        TokenKind.TASK_DONE -> StyleKey.TASK_BOX_DONE
        else -> StyleKey.TASK_BOX_OPEN
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
