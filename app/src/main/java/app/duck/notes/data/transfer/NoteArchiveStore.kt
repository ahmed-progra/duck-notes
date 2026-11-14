package app.duck.notes.data.transfer

import android.content.Context
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.core.files.AttachmentImageLimits
import app.duck.notes.core.files.attachmentImageInfo
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.entity.AttachmentEntity
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.local.entity.TagEntity
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.model.TransferLimits
import app.duck.notes.domain.model.TransferReport
import app.duck.notes.domain.repository.AttachmentStorage
import app.duck.notes.domain.repository.AttachmentWorkLock
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class NoteArchiveStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: DuckDatabase,
    private val storage: AttachmentStorage,
    private val deriveTitle: DeriveTitle,
    private val parseTags: ParseTags,
    private val links: ResolveWikiLinks,
) {
    private val files get() = AttachmentFiles(context.filesDir)

    suspend fun snapshotBackup(directory: File): StagedArchive = snapshot(null, directory, backup = true)

    suspend fun restore(contents: StagedArchive): TransferReport = receive(contents, restoring = true)

    suspend fun snapshot(noteIds: List<String>, directory: File): StagedArchive = snapshot(noteIds, directory, backup = false)

    private suspend fun snapshot(requestedIds: List<String>?, directory: File, backup: Boolean): StagedArchive = withContext(Dispatchers.IO) {
        AttachmentWorkLock.mutex.withLock {
            db.useWriterConnection { connection -> connection.immediateTransaction {
                val noteIds = requestedIds ?: db.noteDao().allIds()
                if ((!backup && noteIds.isEmpty()) || noteIds.size > TransferLimits.MAX_NOTES || noteIds.toSet().size != noteIds.size) throw IOException("Invalid note collection")
                if (!File(directory, "notes").mkdir() || !File(directory, "assets").mkdir()) throw IOException("Temporary storage is unavailable")
                val notes = mutableListOf<ArchiveNote>()
                val images = mutableListOf<ArchiveImage>()
                var textBytes = 0L
                var expandedBytes = 0L
                for (id in noteIds) {
                    currentCoroutineContext().ensureActive()
                    if (!canonicalTransferId(id)) throw IOException("Invalid note identity")
                    val note = db.noteDao().getById(id) ?: throw IOException("Note is unavailable")
                    val owned = db.attachmentDao().forNote(id)
                    val ownedIds = owned.map { it.id }.toSet()
                    if (images.size + owned.size > TransferLimits.MAX_IMAGES || MarkdownScanner.attachmentIdsIn(note.body).any { it !in ownedIds }) throw IOException("Inconsistent image ownership")
                    val destinations = mutableMapOf<String, String>()
                    for (image in owned) {
                        if (!canonicalTransferId(image.id)) throw IOException("Invalid image identity")
                        val source = files.resolve(image.uri) ?: throw IOException("Invalid image location")
                        if (source.name != image.id) throw IOException("Inconsistent image location")
                        if (source.length() !in 1..AttachmentImageLimits.MAX_BYTES) throw IOException("Missing or oversized image")
                        val info = attachmentImageInfo(source) ?: throw IOException("Invalid image")
                        val metadata = ArchiveImage(image.id, id, info.mime, info.width, info.height, info.size, image.createdAt)
                        expandedBytes += info.size
                        if (expandedBytes + textBytes > TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Collection exceeds 1 GiB")
                        val target = File(directory, metadata.entry)
                        source.inputStream().use { input -> FileOutputStream(target).use { output ->
                            copyTransfer(input, output, AttachmentImageLimits.MAX_BYTES)
                            output.fd.sync()
                        } }
                        images += metadata
                        destinations[MarkdownScanner.ATTACHMENT_SCHEME + image.id] = metadata.entry
                    }
                    val metadata = ArchiveNote(id, note.createdAt, note.modifiedAt, note.isPinned, note.isArchived, note.isTrashed, note.trashedAt)
                    val body = if (backup) note.body else MarkdownScanner.rewriteImageDestinations(note.body, destinations)
                    val target = File(directory, metadata.entry)
                    writeNoteText(target, body)
                    textBytes += target.length()
                    if (textBytes > TransferLimits.MAX_TEXT_BYTES || expandedBytes + textBytes > TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Collection exceeds transfer limits")
                    notes += metadata
                }
                val tags = if (backup) db.tagDao().all().filter { it.isPinned || it.iconKey != null || it.sortOrder != 0 }
                    .map { ArchiveTag(it.name, it.isPinned, it.iconKey, it.sortOrder) } else emptyList()
                val manifest = ArchiveManifest(if (backup) "backup" else "markdown", notes, images, tags)
                ArchiveManifestCodec.write(File(directory, "manifest.json"), manifest)
                StagedArchive(manifest, directory).also { MarkdownArchive.validate(it) }
            } }
        }
    }

    suspend fun import(contents: StagedArchive): TransferReport = receive(contents, restoring = false)

    private suspend fun receive(contents: StagedArchive, restoring: Boolean): TransferReport = withContext(Dispatchers.IO) {
        if (contents.manifest.kind != if (restoring) "backup" else "markdown") throw IOException("Unexpected archive kind")
        ArchiveManifestCodec.write(File(contents.directory, "manifest.json"), contents.manifest)
        MarkdownArchive.validate(contents)
        val noteIds = contents.manifest.notes.associate { it.id to if (restoring) it.id else UUID.randomUUID().toString() }
        val imageIds = contents.manifest.images.associate { it.id to if (restoring) it.id else UUID.randomUUID().toString() }
        val imagesByNote = contents.manifest.images.groupBy { it.noteId }
        val rewritten = File(contents.directory, "import-notes")
        fun bodyFile(note: ArchiveNote) = if (restoring) File(contents.directory, note.entry) else File(rewritten, noteIds.getValue(note.id))
        if (!restoring && !rewritten.mkdir()) throw IOException("Import was already attempted")
        if (!restoring) for (note in contents.manifest.notes) {
            currentCoroutineContext().ensureActive()
            val destinations = imagesByNote[note.id].orEmpty().associate { it.entry to MarkdownScanner.ATTACHMENT_SCHEME + imageIds.getValue(it.id) }
            val original = readNoteText(File(contents.directory, note.entry))
            val body = MarkdownScanner.rewriteImageDestinations(original, destinations)
            val target = bodyFile(note)
            writeNoteText(target, body)
        }
        AttachmentWorkLock.mutex.withLock {
            val selected = contents.manifest.notes.filter { !restoring || db.noteDao().getById(it.id) == null }
            val selectedIds = selected.map { it.id }.toSet()
            val selectedImages = contents.manifest.images.filter { it.noteId in selectedIds }
            val addedTextBytes = selected.sumOf { bodyFile(it).length() }
            if (restoring) {
                for (image in contents.manifest.images) {
                    currentCoroutineContext().ensureActive()
                    val existing = db.attachmentDao().getById(image.id) ?: continue
                    if (existing.noteId != image.noteId || existing.createdAt != image.createdAt) throw IOException("Image identity conflict")
                    val file = files.resolve(existing.uri) ?: throw IOException("Invalid image location")
                    if (file.name != image.id || file.length() !in 1..AttachmentImageLimits.MAX_BYTES) throw IOException("Image identity conflict")
                    val info = attachmentImageInfo(file) ?: throw IOException("Invalid existing image")
                    if (info.mime != image.mime || info.width != image.width || info.height != image.height || info.size != image.bytes ||
                        !digest(file).contentEquals(digest(File(contents.directory, image.entry)))) throw IOException("Image identity conflict")
                }
            }
            val published = mutableListOf<String>()
            var committed = false
            try {
                currentCoroutineContext().ensureActive()
                for (image in selectedImages) {
                    val imageId = imageIds.getValue(image.id)
                    if (db.attachmentDao().getById(imageId) != null) throw IOException("Image identity conflict")
                    val destination = files.target(imageId) ?: throw IOException("Attachment storage is unavailable")
                    val pending = files.transferPending(imageId) ?: throw IOException("Attachment storage is unavailable")
                    if (destination.exists() || pending.exists()) throw IOException("Image file conflict")
                    withContext(NonCancellable) {
                        val key = AttachmentFiles.key(imageId)
                        storage.markForDeletion(key)
                        published += key
                    }
                    val temporary = files.transferTemporary(imageId)
                    try {
                        File(contents.directory, image.entry).inputStream().use { input -> FileOutputStream(temporary).use { output ->
                            copyTransfer(input, output, AttachmentImageLimits.MAX_BYTES)
                            output.fd.sync()
                        } }
                        withContext(NonCancellable) {
                            files.publish(temporary, destination)
                        }
                    } finally { temporary.delete() }
                }
                currentCoroutineContext().ensureActive()
                // Complete or roll back the database commit even if cancellation arrives here.
                withContext(NonCancellable) {
                    db.useWriterConnection { connection -> connection.immediateTransaction {
                        val existingImages = db.attachmentDao().all()
                        val existingText = db.noteDao().totalBodyBytes()
                        val existingBytes = existingImages.sumOf { image -> files.resolve(image.uri)?.takeIf { it.isFile }?.length() ?: image.size ?: 0L }
                        val existingTags = if (restoring) db.tagDao().all().map { it.name }.toSet() else emptySet()
                        val newBytes = selectedImages.sumOf { it.bytes }
                        if (db.noteDao().count() + selected.size > TransferLimits.MAX_NOTES || existingImages.size + selectedImages.size > TransferLimits.MAX_IMAGES ||
                            existingText + addedTextBytes > TransferLimits.MAX_TEXT_BYTES || existingBytes + newBytes + existingText + addedTextBytes > TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Collection exceeds transfer limits")
                        var duplicates = 0
                        val now = System.currentTimeMillis()
                        for (note in selected) {
                            val id = noteIds.getValue(note.id)
                            if (db.noteDao().getById(id) != null) throw IOException("Note identity conflict")
                            val body = readNoteText(bodyFile(note))
                            val title = deriveTitle(body)
                            if (db.noteDao().countWithTitle(title) > 0) duplicates++
                            val entity = if (restoring) NoteEntity(id = id, title = title, body = body, createdAt = note.createdAt, modifiedAt = note.modifiedAt,
                                isPinned = note.pinned, isArchived = note.archived, isTrashed = note.trashed, trashedAt = note.trashedAt)
                                else NoteEntity(id = id, title = title, body = body, createdAt = now, modifiedAt = now)
                            db.noteDao().saveNoteWithTagsAndLinks(entity, parseTags(body), links(body), pruneOrphans = false)
                            for (image in imagesByNote[note.id].orEmpty()) {
                                val imageId = imageIds.getValue(image.id)
                                if (db.attachmentDao().getById(imageId) != null) throw IOException("Image identity conflict")
                                val key = AttachmentFiles.key(imageId)
                                db.attachmentDao().insertNew(AttachmentEntity(imageId, id, key, image.mime, image.width, image.height, image.bytes, if (restoring) image.createdAt else now))
                            }
                        }
                        if (restoring) for (tag in contents.manifest.tags.filter { it.name !in existingTags }) {
                            val row = db.tagDao().getByName(tag.name) ?: TagEntity(UUID.randomUUID().toString(), tag.name)
                            db.tagDao().upsert(row.copy(isPinned = tag.pinned, iconKey = tag.icon, sortOrder = tag.order))
                        }
                        TransferReport(selected.map { noteIds.getValue(it.id) }, skipped = contents.manifest.notes.size - selected.size, duplicateTitles = duplicates)
                    } }.also { committed = true }
                }
            } finally {
                if (!committed) withContext(NonCancellable) { published.forEach { storage.delete(it) } }
            }
        }
    }

    private suspend fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }
}

internal fun writeNoteText(file: File, body: String) {
    val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    FileOutputStream(file).use { output ->
        OutputStreamWriter(object : FilterOutputStream(BoundedOutput(output, TransferLimits.MAX_NOTE_BYTES)) {
            override fun close() { flush() }
        }, encoder).use { it.write(body) }
        output.fd.sync()
    }
}
