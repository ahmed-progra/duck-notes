package app.duck.notes.domain.markdown

/** One heading and the body that collapses beneath it. */
data class FoldSection(
    val key: String,
    val title: String,
    val level: Int,
    val headingStart: Int,
    val headingEnd: Int,
    val content: IntRange?,
)

/**
 * Heading-based folding, kept separate from Compose so the range rules stay cheap to test.
 *
 * A heading remains visible when its section is folded. The fold removes the following lines up to
 * the next heading of the same or a higher level; a nested heading therefore belongs to its parent's
 * body.
 *
 * ## What the key does and does not survive
 *
 * A fold key is `level:occurrence:title`, where occurrence counts among headings sharing the same
 * level *and* title. It survives edits to **body text** — typing in a section does not move any
 * key — and it is scoped to the note by the ViewModel's handle key.
 *
 * It does **not** survive edits to headings themselves, and an earlier version of this KDoc claimed
 * it survived "edits elsewhere in the note" without qualifying that:
 *
 * - retyping a heading changes its title, so its fold key changes and the section quietly expands
 * - inserting a heading that duplicates an existing level and title shifts the later one's
 *   occurrence, so a fold moves to a different section
 *
 * Both are acceptable for a cosmetic, session-scoped control, and neither is worth a positional or
 * synthetic id — which would have to be stored in the body or in Room to be stable at all. The claim
 * is what was wrong, not the key.
 */
object SectionFolding {

    fun sections(body: String, tokens: List<Token>): List<FoldSection> {
        val occurrences = mutableMapOf<Pair<Int, String>, Int>()
        val headings = tokens.filter { it.kind == TokenKind.HEADING }

        return headings.mapIndexed { index, heading ->
            val markerEnd = heading.markers.lastOrNull()?.last?.plus(1) ?: heading.start
            val title = body.substring(markerEnd.coerceAtMost(heading.end), heading.end).trim()
            val identity = heading.level to title
            val occurrence = occurrences.getOrDefault(identity, 0)
                .also { occurrences[identity] = it + 1 }
            val next = headings.drop(index + 1).firstOrNull { it.level <= heading.level }
            val contentStart = (heading.end + 1).takeIf { heading.end < body.length }
                ?: body.length
            val contentEnd = next?.start ?: body.length
            FoldSection(
                key = "${heading.level}:$occurrence:$title",
                title = title,
                level = heading.level,
                headingStart = heading.start,
                headingEnd = heading.end,
                content = (contentStart until contentEnd).takeIf { it.first < it.last + 1 },
            )
        }
    }

    /** Returns non-overlapping source ranges to hide for the current caret and folded keys. */
    fun ranges(
        body: String,
        tokens: List<Token>,
        foldedKeys: Set<String>,
        selectionStart: Int,
        selectionEnd: Int,
    ): List<IntRange> {
        val from = minOf(selectionStart, selectionEnd)
        val to = maxOf(selectionStart, selectionEnd)
        val chosen = mutableListOf<IntRange>()

        sections(body, tokens)
            .asSequence()
            .filter { it.key in foldedKeys }
            .mapNotNull { section ->
                val content = section.content ?: return@mapNotNull null
                if (from <= content.last && to >= content.first) return@mapNotNull null
                content
            }
            .sortedBy { it.first }
            .forEach { range ->
                if (chosen.none { it.first <= range.last && range.first <= it.last }) chosen += range
            }

        return chosen.sortedByDescending { it.first }
    }
}
