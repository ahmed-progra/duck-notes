package app.duck.notes.domain.model

/**
 * A file attached to a note, as the rest of the app thinks about it.
 *
 * [uri] is a **durable** location the app owns — a path under `filesDir/attachments` written by
 * `AttachmentStorage`, never the picker's revocable `content://`. The copy is the point: a picker
 * grant does not survive the process, so storing it would make the image unreadable after a relaunch.
 * Same durability argument autosave makes for writing to Room rather than trusting a live buffer.
 *
 * [width]/[height]/[size] are nullable and unset this phase — the strip lays out at a fixed tile size,
 * so measuring the bitmap buys nothing yet.
 */
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
