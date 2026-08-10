package app.duck.notes.domain.markdown

/**
 * The one and only implementation of Duck's `[[wiki link]]` syntax.
 *
 * Three views depend on this agreeing with itself: `ResolveWikiLinks` (the titles written to
 * `note_links` on save), the editor's `MarkdownScanner` (what gets painted accent-red as you type),
 * and `DeriveTitle` (title stripping). They do not each parse `[[...]]` — they all call this. A
 * second wiki-link parser anywhere in the codebase is the exact defect Phase 9 was opened to close,
 * the same one `TagScanner` closed for tags.
 *
 * Fence- and code-span-aware via [TagScanner.fenceAt] and [TagScanner.codeSpanMask]: a `[[link]]`
 * inside a code fence, or `` `[[link]]` `` in an inline span, is **not** a link. That was the whole
 * divergence — `ResolveWikiLinks` and `DeriveTitle` were bare regexes over the raw body that
 * resolved masked links the editor correctly refused, which from Phase 9 would have written a
 * `note_links` row the user can neither see nor click.
 *
 * Recognised: `[[title]]`, non-empty, containing no inner `[` or `]`. Whitespace inside is kept in
 * the span and trimmed by title callers. No backslash escaping, no `[[title|alias]]` — that matches
 * the pre-existing rule exactly; this extraction changes code-masking behaviour and nothing else.
 */
object WikiLinkScanner {

    /**
     * The `[[...]]` opening at [i] in [line], or null. Returns the index just past the closing `]]`.
     *
     * The pure bracket rule, with no fence or mask awareness — the caller owns that (the editor
     * scans a masked line; [titlesIn] and [stripInline] apply the mask before calling here). Kept
     * as the single character rule so `MarkdownScanner` is a thin view rather than a second copy.
     */
    fun matchAt(line: String, i: Int): Int? {
        if (!line.startsWith("[[", i)) return null
        val close = line.indexOf("]]", i + 2)
        if (close < 0) return null
        for (k in i + 2 until close) if (line[k] == '[' || line[k] == ']') return null
        return if (close > i + 2) close + 2 else null
    }

    /**
     * Every link title in [body], deduped in first-appearance order. Fence-aware and code-masked,
     * so it surfaces exactly the links the editor paints.
     */
    fun titlesIn(body: String): List<String> {
        val titles = LinkedHashSet<String>()
        forEachContentLine(body) { line, _ ->
            val masked = TagScanner.codeSpanMask(line)
            var i = 0
            while (i < line.length) {
                val end = if (!masked[i]) matchAt(line, i) else null
                if (end != null) {
                    val title = line.substring(i + 2, end - 2).trim()
                    if (title.isNotEmpty()) titles += title
                    i = end
                } else {
                    i++
                }
            }
        }
        return titles.toList()
    }

    /**
     * [line] with each `[[title]]` replaced by its inner text; a link inside an inline code span is
     * left untouched, matching the same mask [titlesIn] and the editor use. Single-line — the title
     * strip in `DeriveTitle`, which peels the first non-empty line.
     */
    fun stripInline(line: String): String {
        val masked = TagScanner.codeSpanMask(line)
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val end = if (!masked[i]) matchAt(line, i) else null
            if (end != null) {
                out.append(line, i + 2, end - 2)
                i = end
            } else {
                out.append(line[i])
                i++
            }
        }
        return out.toString()
    }

    // --- editing affordances (9c) --------------------------------------------

    /**
     * The `[[` the caret is currently typing inside, as a **source** range, or null.
     *
     * Phase 9c's autocomplete: while this is non-null the editor offers note titles matching
     * `body.substring(range.first + 2, range.last + 1)`, and completing replaces exactly [range].
     *
     * ## Source offsets, and only source offsets
     *
     * [body] is the raw buffer and [caret] is a raw buffer offset, so the range returned indexes the
     * same string `TextFieldState.edit` does. **Nothing here consults a `TextLayoutResult` or
     * `SpanPlanner`**, which is the whole reason 9c stays out of rendered space: the suggestion strip
     * is positioned by Compose rather than by an offset we computed. Anchoring the strip to the caret
     * instead would need `SpanPlanner.renderedOffset` plus `getCursorRect` — the `TaskBoxOverlay`
     * rebase, and the re-entry create-on-tap is deferred for.
     *
     * ## Four ways to be inactive, and where each answer comes from
     *
     * - **Inside a fence.** Inherited rather than written: [forEachContentLine] never hands a fenced
     *   line to the walk, so the caret's line is simply never examined. Same trick
     *   `MarkdownScanner.hasOpenTask` uses to be fence-correct for free.
     * - **Inside an inline code span.** [TagScanner.codeSpanMask], the same mask [titlesIn] applies —
     *   so what the editor refuses to paint is what autocomplete refuses to offer.
     * - **The link is already closed.** [matchAt] succeeding at the opening means `[[Pond]]` is a
     *   finished link; a caret in the middle of one is ordinary text editing, not a half-typed link.
     *   Offering there would rewrite text the user did not ask to change, and leaves the span to
     *   replace ambiguous.
     * - **A `]]` sits between the opening and the caret.** Catches `[[a[b]] |`, which [matchAt]
     *   rejects for its inner bracket and would otherwise read as still open.
     *
     * Nothing is remembered between calls. "Typed `[[` and moved the caret away" needs no cleanup,
     * because the answer is recomputed from [body] and [caret] every time.
     */
    fun activeAt(body: String, caret: Int): IntRange? {
        if (caret < 0 || caret > body.length) return null
        var found: IntRange? = null
        forEachContentLine(body) { line, start ->
            // The caret's own line, end-inclusive: a caret may sit one past the last character.
            if (found == null && caret >= start && caret <= start + line.length) {
                val local = caret - start
                val masked = TagScanner.codeSpanMask(line)
                val open = line.lastIndexOf("[[", local - 2)
                if (open >= 0 && !masked[open] &&
                    matchAt(line, open) == null &&
                    line.indexOf("]]", open + 2).let { it < 0 || it >= local }
                ) {
                    found = start + open until caret
                }
            }
        }
        return found
    }

    /**
     * The markdown for a link to [title] — **the one place `[[` and `]]` are written for output.**
     *
     * The mirror of `MarkdownScanner.imageRef`, and round-tripped the same way: `MarkdownFormatTest`
     * scans this back through [matchAt] and [titlesIn], so the literal the completion inserts and the
     * rule the scanner reads cannot drift apart. It is also why the completion string is built here
     * rather than in the composable — `MarkdownSyntaxSourceTest` bans both literals above this layer.
     */
    fun linkTo(title: String): String = "[[$title]]"

    /**
     * [titles] that could complete [prefix], in the order given, deduped.
     *
     * **Matching folds case; the caller inserts the title verbatim.** Those are not two answers to
     * one question. `NoteDao.noteIdByTitle` resolves a stored link *exactly* because the same body
     * must re-resolve to the same note; this asks the different question of what the user might mean,
     * and then hands back the note's real title — so what lands in the body is exactly what exact
     * resolution matches. A case-sensitive picker would simply fail to find `Pond` from `pond`.
     *
     * Kotlin's `ignoreCase` rather than SQL: SQLite's `NOCASE` folds ASCII only, which is the wrong
     * answer for a project whose own tests carry Hebrew, Arabic and a surrogate-pair duck. Filtering
     * here also sidesteps `LIKE`'s `_` and `%` wildcards — the trap `NoteDao.notesByTagPath` already
     * documents for tag names, which titles are every bit as free to contain.
     *
     * Ordering is the query's (most recently modified first) and is preserved, not re-sorted.
     */
    fun suggest(titles: List<String>, prefix: String): List<String> =
        titles.filter { it.startsWith(prefix, ignoreCase = true) }.distinct()

    /**
     * Runs [action] on each **non-fenced** line of [body] with that line's absolute start offset,
     * tracking ``` / `~~~` fences exactly as [TagScanner.scan] does — a fence opens on the first run
     * and closes on a same-or-longer run of the same character, and lines inside never reach [action].
     *
     * Offsets come from [MarkdownTextCache.splitLinesWithOffsets] rather than being accumulated from
     * line lengths, for the reason that function's own KDoc gives: a CRLF break is two characters, and
     * deriving the offsets would silently shift everything after the first Windows line ending.
     */
    private inline fun forEachContentLine(body: String, action: (line: String, start: Int) -> Unit) {
        var fenceChar: Char? = null
        var fenceLength = 0
        val (lines, starts) = MarkdownTextCache.splitLinesWithOffsets(body)
        for (i in lines.indices) {
            val line = lines[i]
            val fence = TagScanner.fenceAt(line)
            if (fence != null) {
                val (char, length) = fence
                when {
                    fenceChar == null -> { fenceChar = char; fenceLength = length }
                    char == fenceChar && length >= fenceLength -> { fenceChar = null; fenceLength = 0 }
                }
            } else if (fenceChar == null) {
                action(line, starts[i])
            }
        }
    }
}
