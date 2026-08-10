package app.duck.notes.domain.model

/**
 * A resolved `[[WikiLink]]` from one note to another. [linkText] is the title as it was typed.
 *
 * [sourceTitle] is the linking note's *current* title, joined at read time rather than stored.
 * Storing it would make the row stale the moment that note's first line changed — a backlink panel
 * listing a title nobody can find. [linkText] is deliberately the opposite: it is what was typed
 * inside the brackets, frozen, because that is what the source note still says on screen.
 */
data class NoteLink(
    val sourceId: String,
    val targetId: String,
    val linkText: String,
    val sourceTitle: String = "",
)
