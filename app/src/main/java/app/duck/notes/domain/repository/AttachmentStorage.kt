package app.duck.notes.domain.repository

/**
 * Copies picked image bytes into durable app storage, so an attachment outlives the picker's
 * revocable `content://` grant.
 *
 * **String in, string out, on purpose.** `domain/` may not import `android.net.Uri` (DomainPurityTest),
 * so the source is passed as its uri string and the ContentResolver/`filesDir` work lives in the
 * `data/` implementation. [store] returns a portable owned key, or null for a rejected/unreadable
 * source or failed write. Cancellation propagates. No failure may publish a partial image.
 */
interface AttachmentStorage {

    /** Validates and copies a new image. Real storage returns `attachments/<UUID>`, independent of install path. */
    suspend fun store(sourceUri: String, id: String): String?

    /** Removes owned bytes; failed unlinks retain a durable retry intent when storage permits. */
    suspend fun delete(uri: String): Boolean

    /** Persist cleanup intent before removing a row; it survives process death and Android backup. */
    suspend fun markForDeletion(uri: String)

    /** Portable owned keys whose deletion is still pending. A referenced file must be retained. */
    suspend fun pendingDeletions(): List<String>

    /** Whether a durable file still exists at [uri] — the orphan-file half of DeleteAttachment's guard. */
    suspend fun exists(uri: String): Boolean
}
