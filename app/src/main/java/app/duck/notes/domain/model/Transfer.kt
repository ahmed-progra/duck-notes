package app.duck.notes.domain.model

data class PreparedDocument(val id: String, val displayName: String, val mime: String, val bytes: Long)
data class TransferReport(val addedNoteIds: List<String>, val skipped: Int = 0, val duplicateTitles: Int = 0)

/** Explicit input limits apply to streamed bytes, not a provider's claimed length. */
object TransferLimits {
    const val MAX_NOTES = 10_000
    const val MAX_IMAGES = 10_000
    const val MAX_NOTE_BYTES = 2L * 1024 * 1024
    const val MAX_TEXT_BYTES = 64L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 1024L * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    const val MAX_ENTRIES = 20_001
}
