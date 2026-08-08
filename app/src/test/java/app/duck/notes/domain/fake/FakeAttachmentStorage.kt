package app.duck.notes.domain.fake

import app.duck.notes.domain.repository.AttachmentStorage

/**
 * In-memory [AttachmentStorage].
 *
 * [store] returns a durable uri **distinct from the source** — that distinctness is the JVM half of
 * the durability property `AttachImageTest` guards (the recorded uri is the copy, never the picker's
 * revocable `content://`). The real byte-copy that survives the source going away is
 * `AttachmentDurabilityTest`'s, on device.
 *
 * [files] is the set of durable uris currently stored, so `DeleteAttachmentTest` can assert a delete
 * leaves no orphan file behind.
 */
class FakeAttachmentStorage(
    /** When false, [store] returns null — the unreadable-source case `AttachImage` must not record. */
    var storeSucceeds: Boolean = true,
) : AttachmentStorage {

    val files = mutableSetOf<String>()

    /** Every (sourceUri, id) [store] was called with, in order — proves the source was actually read. */
    val stored = mutableListOf<Pair<String, String>>()

    override suspend fun store(sourceUri: String, id: String): String? {
        stored += sourceUri to id
        if (!storeSucceeds) return null
        val durable = DURABLE + id
        files += durable
        return durable
    }

    override suspend fun delete(uri: String): Boolean = files.remove(uri)

    override suspend fun exists(uri: String): Boolean = uri in files

    companion object {
        /** The prefix that marks a uri as this fake's durable copy, never a source. */
        const val DURABLE = "durable://"
    }
}
