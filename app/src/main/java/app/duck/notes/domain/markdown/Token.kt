package app.duck.notes.domain.markdown

/** What a [Token] is, which decides both how it is painted and whether its markers can be hidden. */
enum class TokenKind {
    HEADING,
    QUOTE,
    BULLET,
    ORDERED,
    TASK_OPEN,
    TASK_DONE,
    DIVIDER,
    FENCE_MARKER,
    FENCE_BODY,
    BOLD,
    ITALIC,
    STRIKE,
    HIGHLIGHT,
    CODE_SPAN,
    LINK,
    IMAGE,
    WIKI_LINK,
    TAG,
}

/**
 * One styled run of markdown, in absolute offsets into the note body.
 *
 * [start]/[end] cover the whole node including its syntax markers — that span is what the caret is
 * tested against for reveal. [markers] are the sub-ranges that are *syntax*: `**`, the leading `# `,
 * the `](url)` tail of a link. Those are what get deleted when the node is concealed. A token with
 * no markers (a fence body, a bare `#tag`) is painted but never hides anything.
 *
 * [level] carries the heading depth 1..6, and is 0 for everything else.
 */
data class Token(
    val kind: TokenKind,
    val start: Int,
    val end: Int,
    val markers: List<IntRange> = emptyList(),
    val level: Int = 0,
)
