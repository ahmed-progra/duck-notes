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

    suspend fun snapshot(noteIds: List<String>, directory: File): StagedArchive = withContext(Dispatchers.IO) {
        if (noteIds.isEmpty() || noteIds.size > TransferLimits.MAX_NOTES || noteIds.toSet().size != noteIds.size) throw IOException("Invalid note collection")
        AttachmentWorkLock.mutex.withLock {
            db.useWriterConnection { connection -> connection.immediateTransaction {
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
                    val body = MarkdownScanner.rewriteImageDestinations(note.body, destinations)
                    val target = File(directory, metadata.entry)
                    writeNoteText(target, body)
                    textBytes += target.length()
                    if (textBytes > TransferLimits.MAX_TEXT_BYTES || expandedBytes + textBytes > TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Collection exceeds transfer limits")
                    notes += metadata
                }
                val manifest = ArchiveManifest("markdown", notes, images, emptyList())
                ArchiveManifestCodec.write(File(directory, "manifest.json"), manifest)
                StagedArchive(manifest, directory).also { MarkdownArchive.validate(it) }
            } }
        }
    }

    suspend fun import(contents: StagedArchive): TransferReport = withContext(Dispatchers.IO) {
        if (contents.manifest.kind != "markdown") throw IOException("Not a Markdown package")
        ArchiveManifestCodec.write(File(contents.directory, "manifest.json"), contents.manifest)
        MarkdownArchive.validate(contents)
        val noteIds = contents.manifest.notes.associate { it.id to UUID.randomUUID().toString() }
        val imageIds = contents.manifest.images.associate { it.id to UUID.randomUUID().toString() }
        val imagesByNote = contents.manifest.images.groupBy { it.noteId }
        val rewritten = File(contents.directory, "import-notes")
        if (!rewritten.mkdir()) throw IOException("Import was already attempted")
        var addedTextBytes = 0L
        val tags = mutableSetOf<String>()
        for (note in contents.manifest.notes) {
            currentCoroutineContext().ensureActive()
            val destinations = imagesByNote[note.id].orEmpty().associate { it.entry to MarkdownScanner.ATTACHMENT_SCHEME + imageIds.getValue(it.id) }
            val body = MarkdownScanner.rewriteImageDestinations(readNoteText(File(contents.directory, note.entry)), destinations)
            val parsedTags = parseTags(body)
            if (parsedTags.any { it.length > 256 || it.count { c -> c == '/' } >= 32 }) throw IOException("Too many or oversized tags")
            tags += parsedTags
            if (tags.size > 10_000) throw IOException("Too many or oversized tags")
            val target = File(rewritten, noteIds.getValue(note.id))
            writeNoteText(target, body)
            addedTextBytes += target.length()
            if (addedTextBytes > TransferLimits.MAX_TEXT_BYTES) throw IOException("Too much note text")
        }
        AttachmentWorkLock.mutex.withLock {
            val published = mutableListOf<String>()
            var committed = false
            try {
                currentCoroutineContext().ensureActive()
                for (image in contents.manifest.images) {
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
                        val newBytes = contents.manifest.images.sumOf { it.bytes }
                        if (db.noteDao().count() + noteIds.size > TransferLimits.MAX_NOTES || existingImages.size + imageIds.size > TransferLimits.MAX_IMAGES ||
                            existingText + addedTextBytes > TransferLimits.MAX_TEXT_BYTES || existingBytes + newBytes + existingText + addedTextBytes > TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Collection exceeds transfer limits")
                        var duplicates = 0
                        val now = System.currentTimeMillis()
                        for (note in contents.manifest.notes) {
                            val id = noteIds.getValue(note.id)
                            if (db.noteDao().getById(id) != null) throw IOException("Note identity conflict")
                            val body = readNoteText(File(rewritten, id))
                            val title = deriveTitle(body)
                            if (db.noteDao().countWithTitle(title) > 0) duplicates++
                            db.noteDao().saveNoteWithTagsAndLinks(NoteEntity(id = id, title = title, body = body, createdAt = now, modifiedAt = now), parseTags(body), links(body))
                            for (image in imagesByNote[note.id].orEmpty()) {
                                val imageId = imageIds.getValue(image.id)
                                if (db.attachmentDao().getById(imageId) != null) throw IOException("Image identity conflict")
                                val key = AttachmentFiles.key(imageId)
                                db.attachmentDao().insertNew(AttachmentEntity(imageId, id, key, image.mime, image.width, image.height, image.bytes, now))
                            }
                        }
                        TransferReport(noteIds.values.toList(), duplicateTitles = duplicates)
                    } }.also { committed = true }
                }
            } finally {
                if (!committed) withContext(NonCancellable) { published.forEach { storage.delete(it) } }
            }
        }
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
