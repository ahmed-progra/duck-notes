package app.duck.notes.data.repository

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.core.content.FileProvider
import app.duck.notes.domain.model.PreparedDocument
import app.duck.notes.domain.model.TransferReport
import app.duck.notes.domain.repository.NoteTransferRepository
import app.duck.notes.data.transfer.*
import app.duck.notes.domain.model.TransferLimits
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

class NoteTransferRepositoryImpl @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val store: NoteArchiveStore,
) : NoteTransferRepository {
    private val jobs = TransferFiles(context)
    private val authenticatedRestores = java.util.concurrent.ConcurrentHashMap<String, StagedArchive>()

    override suspend fun prepareBackup(passphrase: CharArray): PreparedDocument = try {
        stage { job ->
            val directory = File(job.directory, "contents").apply { if (!mkdir()) throw IOException("Temporary storage unavailable") }
            val contents = store.snapshotBackup(directory)
            val archive = File(job.directory, "archive.zip")
            MarkdownArchive.write(contents, archive)
            val payload = File(job.directory, "payload")
            BackupEncryption.encrypt(archive, payload, passphrase)
            val archiveRemoved = archive.delete()
            val contentsRemoved = directory.deleteRecursively()
            if (!archiveRemoved || !contentsRemoved) throw IOException("Temporary cleanup unavailable")
            PreparedDocument(job.id, "Duck-Notes.duckbackup", "application/octet-stream", payload.length())
        }
    } finally { passphrase.fill('\u0000') }

    override suspend fun prepareRestore(sourceUri: String): String = stage { job ->
        withDocument(sourceUri, "r") { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(File(job.directory, "input")).use { output ->
                    copyTransfer(input, output, TransferLimits.MAX_ARCHIVE_BYTES)
                    output.fd.sync()
                }
                descriptor.checkError()
            }
        }
        job.id
    }

    override suspend fun authenticateRestore(id: String, passphrase: CharArray) {
        var directory: File? = null
        var archive: File? = null
        try {
            withContext(Dispatchers.IO) {
                val job = jobs.find(id)
                if (authenticatedRestores.containsKey(id)) throw IOException("Backup was already authenticated")
                val contents = File(job.directory, "restore-${UUID.randomUUID()}").apply { if (!mkdir()) throw IOException("Temporary storage unavailable") }
                directory = contents
                val plain = File(job.directory, "authenticated-${UUID.randomUUID()}.zip")
                archive = plain
                BackupEncryption.decrypt(File(job.directory, "input"), plain, passphrase)
                // No archive parser or database access occurs before the complete stream authenticates.
                val staged = MarkdownArchive.read(plain, contents)
                if (staged.manifest.kind != "backup") throw IOException("Not a Duck backup")
                if (!plain.delete()) throw IOException("Temporary cleanup unavailable")
                authenticatedRestores[id] = staged
            }
        } catch (failure: Throwable) {
            authenticatedRestores.remove(id)
            withContext(NonCancellable + Dispatchers.IO) {
                val archiveFailed = archive?.let { it.exists() && !it.delete() } == true
                val directoryFailed = directory?.let { it.exists() && !it.deleteRecursively() } == true
                if (archiveFailed || directoryFailed) {
                    failure.addSuppressed(IOException("Backup cleanup unavailable"))
                }
            }
            throw failure
        } finally { passphrase.fill('\u0000') }
    }

    override suspend fun restorePrepared(id: String): TransferReport = withContext(Dispatchers.IO) {
        jobs.find(id)
        val staged = authenticatedRestores.remove(id) ?: throw IOException("Backup requires authentication")
        store.restore(staged)
    }

    override suspend fun prepareExport(noteIds: List<String>): PreparedDocument = stage { job ->
            val directory = File(job.directory, "contents").apply { if (!mkdir()) throw IOException("Temporary storage unavailable") }
            val contents = store.snapshot(noteIds, directory)
            val payload = File(job.directory, "payload")
            val single = contents.manifest.notes.singleOrNull()?.takeIf { contents.manifest.images.isEmpty() }
            val name: String
            val mime: String
            if (single != null) {
                val body = readNoteText(File(directory, single.entry))
                writeNoteText(payload, body)
                name = safeName(app.duck.notes.domain.usecase.DeriveTitle()(body)).take(72).trimEnd { it.isHighSurrogate() } + ".md"
                mime = "text/markdown"
            } else {
                MarkdownArchive.write(contents, payload)
                name = "Duck-Notes.zip"
                mime = "application/zip"
            }
            PreparedDocument(job.id, name, mime, payload.length())
    }

    override suspend fun prepareImport(sourceUri: String): String = stage { job ->
        contentUri(sourceUri)
            val inputFile = File(job.directory, "input")
            var zipped = false
            withDocument(sourceUri, "r") { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { raw ->
                    val input = BufferedInputStream(raw)
                    input.mark(4)
                    val prefix = ByteArray(4)
                    var count = 0
                    while (count < prefix.size) {
                        val read = input.read(prefix, count, prefix.size - count)
                        if (read == -1) break
                        count += read
                    }
                    input.reset()
                    zipped = count == 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
                        prefix[2] in byteArrayOf(3, 5, 7) && prefix[3] in byteArrayOf(4, 6, 8)
                    FileOutputStream(inputFile).use { output ->
                        copyTransfer(input, output, if (zipped) TransferLimits.MAX_ARCHIVE_BYTES else TransferLimits.MAX_NOTE_BYTES)
                        output.fd.sync()
                    }
                    descriptor.checkError()
                }
            }
            val directory = File(job.directory, "contents").apply { if (!mkdir()) throw IOException("Temporary storage unavailable") }
            if (zipped) {
                if (MarkdownArchive.read(inputFile, directory).manifest.kind != "markdown") throw IOException("Not a Markdown package")
            } else {
                val body = readNoteText(inputFile).removePrefix("\uFEFF")
                if (!File(directory, "notes").mkdir() || !File(directory, "assets").mkdir()) throw IOException("Temporary storage unavailable")
                val now = System.currentTimeMillis()
                val note = ArchiveNote(UUID.randomUUID().toString(), now, now, false, false, false, null)
                writeNoteText(File(directory, note.entry), body)
                val manifest = ArchiveManifest("markdown", listOf(note), emptyList(), emptyList())
                ArchiveManifestCodec.write(File(directory, "manifest.json"), manifest)
                MarkdownArchive.validate(StagedArchive(manifest, directory))
            }
            job.id
    }

    override suspend fun importPrepared(id: String): TransferReport = withContext(Dispatchers.IO) {
        val directory = File(jobs.find(id).directory, "contents")
        store.import(StagedArchive(ArchiveManifestCodec.read(File(directory, "manifest.json")), directory))
    }

    override suspend fun writePrepared(id: String, destinationUri: String) = withContext(Dispatchers.IO) {
        val uri = contentUri(destinationUri)
        try {
            val payload = File(jobs.find(id).directory, "payload")
            if (!payload.isFile || payload.length() > TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Export expired")
            withDocument(destinationUri, "wt") { descriptor ->
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    payload.inputStream().use { copyTransfer(it, output, TransferLimits.MAX_ARCHIVE_BYTES) }
                    output.flush()
                    if (OsConstants.S_ISREG(Os.fstat(descriptor.fileDescriptor).st_mode)) descriptor.fileDescriptor.sync()
                    descriptor.checkError()
                }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
                    else context.contentResolver.delete(uri, null, null)
                }
            }
            throw failure
        }
    }

    override suspend fun sharePrepared(document: PreparedDocument): String = withContext(Dispatchers.IO) {
        val source = File(jobs.find(document.id).directory, "payload")
        if (!source.isFile || source.length() != document.bytes) throw IOException("Export expired")
        val root = File(context.cacheDir.canonicalFile, "exports")
        if (root.canonicalFile != root.absoluteFile || !root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("Sharing storage unavailable")
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdir()) throw IOException("Sharing storage unavailable")
        try {
            val target = File(directory, safeName(document.displayName))
            source.inputStream().use { input -> FileOutputStream(target).use { output ->
                copyTransfer(input, output, TransferLimits.MAX_ARCHIVE_BYTES)
                output.fd.sync()
            } }
            FileProvider.getUriForFile(context, "${context.packageName}.exports", target).toString()
        } catch (failure: Throwable) { directory.deleteRecursively(); throw failure }
    }

    override suspend fun discardPrepared(id: String) = withContext(Dispatchers.IO) { authenticatedRestores.remove(id); jobs.close(id) }

    /** Catch outside withContext: cancellation can discard a completed result during return dispatch. */
    private suspend fun <T> stage(block: suspend (TransferJob) -> T): T {
        var job: TransferJob? = null
        try {
            return withContext(Dispatchers.IO) { block(jobs.create().also { job = it }) }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                job?.let { current ->
                    try { jobs.close(current.id) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                }
            }
            throw failure
        }
    }

    private fun contentUri(value: String): Uri = Uri.parse(value).also {
        if (it.scheme != "content" || it.authority.isNullOrBlank()) throw IOException("A document-provider URI is required")
    }

    private fun safeName(value: String): String = value.map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }
        .joinToString("").take(96).trim().trim('.').ifBlank { "Duck-Notes" }

    private suspend fun <T> withDocument(uri: String, mode: String, block: suspend (ParcelFileDescriptor) -> T): T = coroutineScope {
        val signal = CancellationSignal()
        val descriptor = AtomicReference<ParcelFileDescriptor?>()
        val cancel = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel(); runCatching { descriptor.get()?.close() } }
        }
        try {
            val file = withContext(Dispatchers.IO) {
                (context.contentResolver.openFileDescriptor(contentUri(uri), mode, signal) ?: throw IOException("Document unavailable")).also { descriptor.set(it) }
            }
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) { block(file) }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw failure
        } finally {
            withContext(NonCancellable) { cancel.cancelAndJoin(); descriptor.getAndSet(null)?.close() }
        }
    }
}
