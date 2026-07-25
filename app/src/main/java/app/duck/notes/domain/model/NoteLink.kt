package app.duck.notes.domain.model

/** A resolved `[[WikiLink]]` from one note to another. [linkText] is the title as it was typed. */
data class NoteLink(
    val sourceId: String,
    val targetId: String,
    val linkText: String,
)
