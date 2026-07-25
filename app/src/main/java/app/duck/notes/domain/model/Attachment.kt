package app.duck.notes.domain.model

/** A file attached to a note. [uri] is an opaque string here — resolving it is the data layer's job. */
data class Attachment(
    val id: String,
    val noteId: String,
    val uri: String,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val size: Long? = null,
    val createdAt: Long,
)
