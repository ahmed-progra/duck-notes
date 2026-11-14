package app.duck.notes.data.transfer

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import app.duck.notes.domain.model.TransferLimits
import app.duck.notes.core.files.AttachmentImageLimits
import app.duck.notes.core.files.attachmentImageExtension
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal data class ArchiveNote(val id: String, val createdAt: Long, val modifiedAt: Long,
    val pinned: Boolean, val archived: Boolean, val trashed: Boolean, val trashedAt: Long?) {
    val entry: String get() = "notes/$id.md"
}
internal data class ArchiveImage(val id: String, val noteId: String, val mime: String,
    val width: Int, val height: Int, val bytes: Long, val createdAt: Long) {
    val entry: String get() = "assets/$id.${archiveImageExtension(mime)}"
}
internal data class ArchiveTag(val name: String, val pinned: Boolean, val icon: String?, val order: Int)
internal data class ArchiveManifest(val kind: String, val notes: List<ArchiveNote>, val images: List<ArchiveImage>, val tags: List<ArchiveTag>)

internal fun canonicalTransferId(id: String): Boolean = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
internal fun archiveImageExtension(mime: String): String = attachmentImageExtension(mime) ?: throw IOException("Unsupported image type")

/** Count bytes as written; declared sizes never exempt a stream from its limit. */
internal class BoundedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
    var count = 0L
        private set
    override fun write(value: Int) {
        if (count >= limit) throw IOException("Transfer limit exceeded")
        out.write(value)
        count++
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length < 0 || count > limit - length) throw IOException("Transfer limit exceeded")
        out.write(bytes, offset, length)
        count += length
    }
}

/** Version one has no caller-supplied file paths: entry names derive from canonical UUIDs. */
internal object ArchiveManifestCodec {
    fun write(file: File, manifest: ArchiveManifest) {
        FileOutputStream(file).use { output ->
            val writer = JsonWriter(OutputStreamWriter(BoundedOutput(output, TransferLimits.MAX_MANIFEST_BYTES), Charsets.UTF_8))
            writer.beginObject().name("version").value(1).name("kind").value(manifest.kind)
            writer.name("notes").beginArray()
            manifest.notes.forEach { note ->
                writer.beginObject().name("id").value(note.id).name("created").value(note.createdAt).name("modified").value(note.modifiedAt)
                    .name("pinned").value(note.pinned).name("archived").value(note.archived).name("trashed").value(note.trashed)
                    .name("trashedAt").value(note.trashedAt).endObject()
            }
            writer.endArray().name("images").beginArray()
            manifest.images.forEach { image ->
                writer.beginObject().name("id").value(image.id).name("note").value(image.noteId).name("mime").value(image.mime)
                    .name("width").value(image.width).name("height").value(image.height).name("bytes").value(image.bytes)
                    .name("created").value(image.createdAt).endObject()
            }
            writer.endArray().name("tags").beginArray()
            manifest.tags.forEach { tag ->
                writer.beginObject().name("name").value(tag.name).name("pinned").value(tag.pinned)
                    .name("icon").value(tag.icon).name("order").value(tag.order).endObject()
            }
            writer.endArray().endObject()
            writer.flush()
            output.fd.sync()
        }
        read(file)
    }

    fun read(file: File): ArchiveManifest {
        if (file.length() !in 1..TransferLimits.MAX_MANIFEST_BYTES) throw IOException("Invalid manifest size")
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        JsonReader(InputStreamReader(file.inputStream(), decoder)).use { reader ->
            var version: Long? = null
            var kind: String? = null
            var notes: List<ArchiveNote>? = null
            var images: List<ArchiveImage>? = null
            var tags: List<ArchiveTag>? = null
            reader.obj { key ->
                when (key) {
                    "version" -> version = reader.integer()
                    "kind" -> kind = reader.string()
                    "notes" -> notes = reader.array(TransferLimits.MAX_NOTES) { readNote(reader) }
                    "images" -> images = reader.array(TransferLimits.MAX_IMAGES) { readImage(reader) }
                    "tags" -> tags = reader.array(10_000) { readTag(reader) }
                    else -> throw IOException("Unsupported manifest field")
                }
            }
            if (version != 1L || kind !in setOf("markdown", "backup") || notes == null || images == null || tags == null || reader.peek() != JsonToken.END_DOCUMENT) {
                throw IOException("Unsupported manifest")
            }
            val result = ArchiveManifest(requireNotNull(kind), requireNotNull(notes), requireNotNull(images), requireNotNull(tags))
            val noteIds = result.notes.map { it.id }.toSet()
            if (noteIds.size != result.notes.size || result.images.map { it.id }.toSet().size != result.images.size ||
                result.tags.map { it.name }.toSet().size != result.tags.size || result.images.any { it.noteId !in noteIds }) throw IOException("Inconsistent manifest")
            return result
        }
    }

    private fun readNote(reader: JsonReader): ArchiveNote {
        var id: String? = null
        var created: Long? = null
        var modified: Long? = null
        var pinned: Boolean? = null
        var archived: Boolean? = null
        var trashed: Boolean? = null
        var trashedAt: Long? = null
        var hasTrashTime = false
        reader.obj { key -> when (key) {
            "id" -> id = reader.string()
            "created" -> created = reader.timestamp()
            "modified" -> modified = reader.timestamp()
            "pinned" -> pinned = reader.bool()
            "archived" -> archived = reader.bool()
            "trashed" -> trashed = reader.bool()
            "trashedAt" -> { hasTrashTime = true; trashedAt = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.timestamp() }
            else -> throw IOException("Unsupported note field")
        } }
        if (id == null || !canonicalTransferId(requireNotNull(id)) || created == null || modified == null || pinned == null || archived == null || trashed == null || !hasTrashTime) throw IOException("Invalid note metadata")
        return ArchiveNote(requireNotNull(id), requireNotNull(created), requireNotNull(modified), requireNotNull(pinned), requireNotNull(archived), requireNotNull(trashed), trashedAt)
    }

    private fun readImage(reader: JsonReader): ArchiveImage {
        var id: String? = null
        var noteId: String? = null
        var mime: String? = null
        var width: Long? = null
        var height: Long? = null
        var bytes: Long? = null
        var created: Long? = null
        reader.obj { key -> when (key) {
            "id" -> id = reader.string()
            "note" -> noteId = reader.string()
            "mime" -> mime = reader.string()
            "width" -> width = reader.integer()
            "height" -> height = reader.integer()
            "bytes" -> bytes = reader.integer()
            "created" -> created = reader.timestamp()
            else -> throw IOException("Unsupported image field")
        } }
        val w = width ?: throw IOException("Missing width")
        val h = height ?: throw IOException("Missing height")
        if (w !in 1L..Int.MAX_VALUE.toLong() || h !in 1L..Int.MAX_VALUE.toLong()) throw IOException("Invalid image dimensions")
        val result = ArchiveImage(id ?: throw IOException("Missing image ID"), noteId ?: throw IOException("Missing owner"), mime ?: throw IOException("Missing image type"),
            w.toInt(), h.toInt(), bytes ?: 0, created ?: throw IOException("Missing timestamp"))
        if (!canonicalTransferId(result.id) || !canonicalTransferId(result.noteId) || !AttachmentImageLimits.acceptsDimensions(result.width, result.height) ||
            result.bytes !in 1L..AttachmentImageLimits.MAX_BYTES) throw IOException("Invalid image metadata")
        archiveImageExtension(result.mime)
        return result
    }

    private fun readTag(reader: JsonReader): ArchiveTag {
        var name: String? = null
        var pinned: Boolean? = null
        var icon: String? = null
        var order: Long? = null
        var hasIcon = false
        reader.obj { key -> when (key) {
            "name" -> name = reader.string()
            "pinned" -> pinned = reader.bool()
            "icon" -> { hasIcon = true; icon = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); null } else reader.string() }
            "order" -> order = reader.integer()
            else -> throw IOException("Unsupported tag field")
        } }
        val tag = name ?: throw IOException("Missing tag name")
        if (tag.length !in 1..256 || tag.count { it == '/' } >= 32 || !app.duck.notes.domain.markdown.TagScanner.isValidName(tag) ||
            pinned == null || !hasIcon || (icon?.length ?: 0) > 256 || order == null || requireNotNull(order) !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) throw IOException("Invalid tag metadata")
        return ArchiveTag(tag, requireNotNull(pinned), icon, requireNotNull(order).toInt())
    }

    private fun JsonReader.obj(field: (String) -> Unit) {
        if (peek() != JsonToken.BEGIN_OBJECT) throw IOException("Invalid manifest object")
        beginObject()
        val seen = mutableSetOf<String>()
        while (hasNext()) {
            val key = nextName()
            if (!seen.add(key)) throw IOException("Duplicate manifest field")
            field(key)
        }
        endObject()
    }
    private fun <T> JsonReader.array(limit: Int, element: () -> T): List<T> {
        if (peek() != JsonToken.BEGIN_ARRAY) throw IOException("Invalid manifest array")
        beginArray()
        val values = mutableListOf<T>()
        while (hasNext()) {
            if (values.size >= limit) throw IOException("Transfer limit exceeded")
            values += element()
        }
        endArray()
        return values
    }
    private fun JsonReader.string(): String {
        if (peek() != JsonToken.STRING) throw IOException("Invalid string field")
        return nextString()
    }
    private fun JsonReader.bool(): Boolean {
        if (peek() != JsonToken.BOOLEAN) throw IOException("Invalid boolean field")
        return nextBoolean()
    }
    private fun JsonReader.integer(): Long {
        if (peek() != JsonToken.NUMBER) throw IOException("Invalid number field")
        return nextString().toLongOrNull() ?: throw IOException("Invalid integer field")
    }
    private fun JsonReader.timestamp(): Long = integer().also {
        if (it !in 0L..253_402_300_799_999L) throw IOException("Invalid timestamp")
    }
}
