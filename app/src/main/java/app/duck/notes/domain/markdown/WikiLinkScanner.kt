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
        forEachContentLine(body) { line ->
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

    /**
     * Runs [action] on each **non-fenced** line of [body], tracking ``` / `~~~` fences exactly as
     * [TagScanner.scan] does — a fence opens on the first run and closes on a same-or-longer run of
     * the same character, and lines inside never reach [action].
     */
    private inline fun forEachContentLine(body: String, action: (String) -> Unit) {
        var fenceChar: Char? = null
        var fenceLength = 0
        for (line in body.lineSequence()) {
            val fence = TagScanner.fenceAt(line)
            if (fence != null) {
                val (char, length) = fence
                when {
                    fenceChar == null -> { fenceChar = char; fenceLength = length }
                    char == fenceChar && length >= fenceLength -> { fenceChar = null; fenceLength = 0 }
                }
            } else if (fenceChar == null) {
                action(line)
            }
        }
    }
}
