package app.duck.notes.domain.repository

/**
 * Copies picked image bytes into durable app storage, so an attachment outlives the picker's
 * revocable `content://` grant.
 *
 * **String in, string out, on purpose.** `domain/` may not import `android.net.Uri` (DomainPurityTest),
 * so the source is passed as its uri string and the ContentResolver/`filesDir` work lives in the
 * `data/` implementation. [store] returns the durable location (a path the app owns), or **null** if
 * the source could not be read — the one failure `AttachImage` must not turn into a recorded row.
 */
interface AttachmentStorage {

    /** Copies [sourceUri]'s bytes into durable storage keyed by [id]. Returns the durable uri, or null. */
    suspend fun store(sourceUri: String, id: String): String?

    /** Removes the durable file at [uri]. True if a file was deleted. */
    suspend fun delete(uri: String): Boolean

    /** Whether a durable file still exists at [uri] — the orphan-file half of DeleteAttachment's guard. */
    suspend fun exists(uri: String): Boolean
}
