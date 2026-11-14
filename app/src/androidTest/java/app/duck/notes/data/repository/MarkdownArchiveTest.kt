package app.duck.notes.data.repository

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.transfer.*
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class MarkdownArchiveTest {
    private lateinit var root: File
    private val note = "00000000-0000-0000-0000-000000000001"
    private val image = "00000000-0000-0000-0000-000000000002"
    @Before fun setUp() { root = File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "archive-test-${UUID.randomUUID()}").apply { check(mkdir()) } }
    @After fun close() { root.deleteRecursively() }
    private fun fixture(): StagedArchive {
        val dir = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
        File(dir, "notes").mkdir()
        File(dir, "assets").mkdir()
        val png = File(dir, "assets/$image.png")
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        File(dir, "notes/$note.md").writeText("# Research\n#science\n![sample](assets/$image.png)\n`![example](attachment:missing)`", Charsets.UTF_8)
        val manifest = ArchiveManifest("markdown", listOf(ArchiveNote(note, 1, 2, true, false, false, null)),
            listOf(ArchiveImage(image, note, "image/png", 2, 3, png.length(), 1)), emptyList())
        ArchiveManifestCodec.write(File(dir, "manifest.json"), manifest)
        return StagedArchive(manifest, dir)
    }
    private fun zip(contents: StagedArchive, changes: Map<String, ByteArray> = emptyMap(), extra: Pair<String, ByteArray>? = null, omit: String? = null): File {
        val archive = File(root, "${UUID.randomUUID()}.zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            val names = listOf("manifest.json") + contents.manifest.notes.map { it.entry } + contents.manifest.images.map { it.entry }
            names.filterNot { it == omit }.forEach { name ->
                out.putNextEntry(ZipEntry(name))
                out.write(changes[name] ?: File(contents.directory, name).readBytes())
                out.closeEntry()
            }
            extra?.let { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
        }
        return archive
    }
    private fun read(file: File) = runBlocking { MarkdownArchive.read(file, File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }) }
    private fun reject(file: File) { try { read(file); fail("Invalid archive accepted") } catch (_: IOException) { } }

    @Test fun actualImageAndUtf8MarkdownRoundTripWithoutChangingCodeExamples() {
        val source = fixture()
        val first = read(zip(source))
        assertEquals(source.manifest, first.manifest)
        assertArrayEquals(File(source.directory, source.manifest.images.single().entry).readBytes(), File(first.directory, first.manifest.images.single().entry).readBytes())
        val written = File(root, "export.zip")
        runBlocking { MarkdownArchive.write(first, written) }
        val second = read(written)
        assertEquals(File(source.directory, "notes/$note.md").readText(Charsets.UTF_8), File(second.directory, "notes/$note.md").readText(Charsets.UTF_8))
    }

    /** Plants: remove entry allowlist, strict UTF-8, per-note byte cap, or image-byte validation. */
    @Test fun traversalUnknownEntriesMalformedTextAndFalseImageMetadataAreRejected() {
        val source = fixture()
        reject(zip(source, extra = "../escape" to byteArrayOf(1)))
        reject(zip(source, extra = "unknown.txt" to byteArrayOf(1)))
        reject(zip(source, changes = mapOf("notes/$note.md" to byteArrayOf(0xff.toByte()))))
        reject(zip(source, changes = mapOf("notes/$note.md" to ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() })))
        reject(zip(source, changes = mapOf("assets/$image.png" to byteArrayOf(1, 2, 3))))
        assertEquals(1, read(zip(source)).manifest.images.size)
        assertFalse(File(root.parentFile, "escape").exists())
    }

    /** Plant: accept unresolved local image references or another note's attachment ownership. */
    @Test fun missingImagesAndOwnedAttachmentReferencesCannotPassAsPortableContent() {
        val source = fixture()
        reject(zip(source, changes = mapOf("notes/$note.md" to "![lost](assets/missing.png)".toByteArray())))
        reject(zip(source, changes = mapOf("notes/$note.md" to "![lost](attachment:$image)".toByteArray())))
        assertEquals(1, read(zip(source)).manifest.notes.size)
    }

    /** Plants: raise the 20,001-entry or 8-MiB central-directory allocation bound. */
    @Test fun centralDirectoryIsBoundedBeforeThePlatformZipReaderAllocatesIt() {
        fun endRecord(count: Int, centralBytes: Long): File {
            val file = File(root, "${UUID.randomUUID()}.zip")
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(centralBytes + 22)
                out.seek(centralBytes)
                fun number(value: Long, bytes: Int) { repeat(bytes) { out.write((value ushr (8 * it)).toInt() and 255) } }
                number(0x06054b50, 4); number(0, 2); number(0, 2); number(count.toLong(), 2); number(count.toLong(), 2)
                number(centralBytes, 4); number(0, 4); number(0, 2)
            }
            return file
        }
        assertEquals(20_001, boundedZipEntryCount(endRecord(20_001, 8L * 1024 * 1024)))
        try { boundedZipEntryCount(endRecord(20_002, 8L * 1024 * 1024)); fail("Entry count allocation unbounded") } catch (_: IOException) { }
        try { boundedZipEntryCount(endRecord(1, 8L * 1024 * 1024 + 1)); fail("Central directory allocation unbounded") } catch (_: IOException) { }
        assertEquals(3, boundedZipEntryCount(zip(fixture())))
    }

    /** Plants: bypass CRC, allow missing entries, or bypass duplicate plus staging-collision checks. */
    @Test fun duplicateEntriesMissingBytesFalseCrcAndTruncatedDirectoriesAreRejected() {
        val source = fixture()
        reject(zip(source, omit = source.manifest.images.single().entry))
        val extraId = "00000000-0000-0000-0000-000000000003"
        val duplicate = zip(source, extra = "assets/$extraId.png" to File(source.directory, "assets/$image.png").readBytes())
        val bytes = duplicate.readBytes()
        val from = extraId.toByteArray()
        val to = image.toByteArray()
        for (offset in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[offset + it] == from[it] }) to.copyInto(bytes, offset)
        }
        duplicate.writeBytes(bytes)
        reject(duplicate)
        val crc = zip(source)
        val damaged = crc.readBytes()
        val central = (0..damaged.size - 20).first { damaged[it] == 0x50.toByte() && damaged[it + 1] == 0x4b.toByte() && damaged[it + 2] == 1.toByte() && damaged[it + 3] == 2.toByte() }
        damaged[central + 16] = (damaged[central + 16].toInt() xor 1).toByte()
        crc.writeBytes(damaged)
        reject(crc)
        val truncated = zip(source)
        RandomAccessFile(truncated, "rw").use { it.setLength(it.length() - 1) }
        reject(truncated)
        val appended = zip(source)
        appended.appendBytes(byteArrayOf(0))
        reject(appended)
        assertEquals(1, read(zip(source)).manifest.notes.size)
    }
}
