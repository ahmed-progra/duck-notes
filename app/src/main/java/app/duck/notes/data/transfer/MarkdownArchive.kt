package app.duck.notes.data.transfer

import app.duck.notes.core.files.AttachmentImageLimits
import app.duck.notes.core.files.attachmentImageInfo
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.model.TransferLimits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal data class StagedArchive(val manifest: ArchiveManifest, val directory: File)

internal object MarkdownArchive {
    suspend fun read(archive: File, directory: File): StagedArchive {
        if (archive.length() !in 1..TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Invalid archive size")
        val entryCount = boundedZipEntryCount(archive)
        ZipFile(archive).use { zip ->
            val entries = mutableListOf<ZipEntry>()
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                if (entries.size >= TransferLimits.MAX_ENTRIES) throw IOException("Too many archive entries")
                entries += enumeration.nextElement()
            }
            if (entries.size != entryCount || entries.firstOrNull()?.name != "manifest.json" ||
                entries.any { it.isDirectory } || entries.map { it.name }.toSet().size != entries.size) throw IOException("Invalid archive entries")
            var expanded = 0L
            suspend fun extract(entry: ZipEntry, target: File, limit: Long) {
                if (!target.createNewFile()) throw IOException("Archive staging collision")
                val crc = CRC32()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        expanded += copyTransfer(input, object : OutputStream() {
                            override fun write(value: Int) { output.write(value); crc.update(value) }
                            override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length); crc.update(bytes, offset, length) }
                        }, minOf(limit, TransferLimits.MAX_ARCHIVE_BYTES - expanded))
                        output.fd.sync()
                    }
                }
                if (crc.value != entry.crc) throw IOException("Damaged archive entry")
            }
            extract(entries.first(), File(directory, "manifest.json"), TransferLimits.MAX_MANIFEST_BYTES)
            val manifest = ArchiveManifestCodec.read(File(directory, "manifest.json"))
            val expected = buildSet { add("manifest.json"); manifest.notes.forEach { add(it.entry) }; manifest.images.forEach { add(it.entry) } }
            if (entries.map { it.name }.toSet() != expected) throw IOException("Missing or unsupported archive entry")
            if (!File(directory, "notes").mkdir() || !File(directory, "assets").mkdir()) throw IOException("Archive staging unavailable")
            var textBytes = 0L
            val noteEntries = manifest.notes.map { it.entry }.toSet()
            entries.drop(1).forEach { entry ->
                val isNote = entry.name in noteEntries
                val limit = if (isNote) minOf(TransferLimits.MAX_NOTE_BYTES, TransferLimits.MAX_TEXT_BYTES - textBytes) else AttachmentImageLimits.MAX_BYTES
                val target = File(directory, entry.name)
                extract(entry, target, limit)
                if (isNote) textBytes += target.length()
            }
            return StagedArchive(manifest, directory).also { validate(it) }
        }
    }

    suspend fun write(contents: StagedArchive, archive: File) {
        ArchiveManifestCodec.write(File(contents.directory, "manifest.json"), contents.manifest)
        validate(contents)
        FileOutputStream(archive).use { file ->
            val bounded = BoundedOutput(file, TransferLimits.MAX_ARCHIVE_BYTES)
            ZipOutputStream(object : FilterOutputStream(bounded) { override fun close() { flush() } }).use { zip ->
                val names = listOf("manifest.json") + contents.manifest.notes.map { it.entry } + contents.manifest.images.map { it.entry }
                for (name in names) {
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry(name))
                    File(contents.directory, name).inputStream().use { copyTransfer(it, zip, TransferLimits.MAX_ARCHIVE_BYTES) }
                    zip.closeEntry()
                }
            }
            file.fd.sync()
        }
    }

    internal suspend fun validate(contents: StagedArchive) {
        val manifest = contents.manifest
        var textBytes = 0L
        var expanded = File(contents.directory, "manifest.json").length()
        val imagesByNote = manifest.images.groupBy { it.noteId }
        val tags = manifest.tags.mapTo(mutableSetOf()) { it.name }
        for (note in manifest.notes) {
            currentCoroutineContext().ensureActive()
            val file = File(contents.directory, note.entry)
            val body = readNoteText(file)
            val names = app.duck.notes.domain.usecase.ParseTags()(body)
            if (names.any { it.length > 256 || it.count { c -> c == '/' } >= 32 }) throw IOException("Too many or oversized tags")
            tags += names
            if (tags.size > 10_000) throw IOException("Too many or oversized tags")
            textBytes += file.length()
            if (textBytes > TransferLimits.MAX_TEXT_BYTES) throw IOException("Too much note text")
            val owned = imagesByNote[note.id].orEmpty()
            val expected = owned.map { if (manifest.kind == "backup") MarkdownScanner.ATTACHMENT_SCHEME + it.id else it.entry }.toSet()
            if (MarkdownScanner.imageDestinationsIn(body).any { destination ->
                    destination !in expected && (destination.startsWith(MarkdownScanner.ATTACHMENT_SCHEME) || ':' !in destination)
                }) throw IOException("Missing or inconsistent image reference")
        }
        expanded += textBytes
        for (image in manifest.images) {
            currentCoroutineContext().ensureActive()
            val file = File(contents.directory, image.entry)
            if (file.length() !in 1..AttachmentImageLimits.MAX_BYTES) throw IOException("Invalid image size")
            val info = attachmentImageInfo(file) ?: throw IOException("Invalid image")
            if (info.size != image.bytes || info.mime != image.mime || info.width != image.width || info.height != image.height) throw IOException("Inconsistent image metadata")
            expanded += info.size
            if (expanded > TransferLimits.MAX_ARCHIVE_BYTES) throw IOException("Expanded archive is too large")
        }
    }
}

/** Duck packages need neither ZIP64 nor multi-disk metadata; bound allocation before ZipFile opens. */
internal fun boundedZipEntryCount(archive: File): Int = RandomAccessFile(archive, "r").use { input ->
    val length = input.length()
    if (length < 22) throw IOException("Truncated ZIP")
    val tail = ByteArray(minOf(length, 65_557L).toInt())
    input.seek(length - tail.size)
    input.readFully(tail)
    fun number(offset: Int, bytes: Int): Long {
        var value = 0L
        repeat(bytes) { value = value or ((tail[offset + it].toLong() and 255L) shl (8 * it)) }
        return value
    }
    val end = (tail.size - 22 downTo 0).firstOrNull {
        number(it, 4) == 0x06054b50L && it + 22 + number(it + 20, 2) == tail.size.toLong()
    } ?: throw IOException("Missing ZIP directory")
    val count = number(end + 10, 2)
    val centralSize = number(end + 12, 4)
    val centralOffset = number(end + 16, 4)
    if (number(end + 4, 2) != 0L || number(end + 6, 2) != 0L || number(end + 8, 2) != count ||
        count !in 1L..20_001L || centralSize > 8L * 1024 * 1024 ||
        centralOffset + centralSize != length - tail.size + end) throw IOException("Unsupported or oversized ZIP directory")
    count.toInt()
}

internal fun readNoteText(file: File): String {
    if (file.length() > TransferLimits.MAX_NOTE_BYTES) throw IOException("Note exceeds 2 MiB")
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    return InputStreamReader(file.inputStream(), decoder).use { it.readText() }.removePrefix("\uFEFF")
}

internal suspend fun copyTransfer(input: InputStream, output: OutputStream, limit: Long): Long {
    val bounded = BoundedOutput(output, limit)
    val buffer = ByteArray(32 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count == -1) return bounded.count
        if (count == 0) continue
        bounded.write(buffer, 0, count)
    }
}
