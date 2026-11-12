package app.duck.notes.core.files

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardCopyOption

/** Resolves portable and pre-backup absolute keys inside this installation's attachment directory. */
internal class AttachmentFiles(filesDir: File) {
    private val files = filesDir.canonicalFile

    fun resolve(location: String): File? {
        return try {
            val supplied = File(location)
            val id = when {
                location.startsWith("$DIRECTORY/") && !supplied.isAbsolute -> location.removePrefix("$DIRECTORY/")
                supplied.isAbsolute && supplied.parentFile?.name == DIRECTORY &&
                    supplied.parentFile?.parentFile?.name == "files" &&
                    location.replace('\\', '/').split('/').none { it == "." || it == ".." } -> supplied.name
                else -> return null
            }
            target(id)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    fun target(id: String): File? {
        if (id.length != 36 || runCatching { java.util.UUID.fromString(id).toString().equals(id, ignoreCase = true) }.getOrDefault(false).not()) return null
        val directory = directory() ?: return null
        val target = File(directory, id)
        return target.takeIf { it.canonicalFile == it.absoluteFile && !it.isDirectory }
    }

    fun temporary(): File {
        val directory = directory() ?: throw IOException("Attachment directory is unavailable")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Attachment directory is unavailable")
        val pending = File(directory, PENDING)
        if (pending.canonicalFile != pending.absoluteFile ||
            (!pending.isDirectory && !pending.mkdirs() && !pending.isDirectory)) throw IOException("Attachment temporary directory is unavailable")
        // createTempFile creates exclusively; two imports must never share/truncate a temporary name.
        return File.createTempFile("image-", ".part", pending)
    }

    fun publish(temporary: File, destination: File) = synchronized(publicationLock) {
        val current = target(destination.name)
        if (current != destination || temporary.canonicalFile.parentFile != File(destination.parentFile, PENDING) ||
            !temporary.isFile) throw IOException("Attachment publication is outside owned storage")
        if (destination.exists()) throw FileAlreadyExistsException(destination.path)
        // Android denies hard links. All publishers share this lock; only complete files are renamed.
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    fun deletionMarker(id: String, createDirectory: Boolean = true): File? {
        target(id) ?: return null
        val parent = directory() ?: return null
        val directory = File(parent, ".deletions")
        if (directory.canonicalFile != directory.absoluteFile || directory.isFile) return null
        if (!directory.isDirectory && (!createDirectory || !directory.mkdirs() && !directory.isDirectory)) return null
        return File(directory, id).takeIf { it.canonicalFile == it.absoluteFile && !it.isDirectory }
    }

    fun pendingDeletions(): List<String> {
        val parent = directory() ?: return emptyList()
        val directory = File(parent, ".deletions")
        if (directory.canonicalFile != directory.absoluteFile) return emptyList()
        return directory.listFiles().orEmpty().filter {
            it.isFile && it.canonicalFile == it.absoluteFile && target(it.name) != null
        }.map { key(it.name) }
    }

    private fun directory(): File? {
        val directory = File(files, DIRECTORY)
        return directory.takeIf { it.canonicalFile == it.absoluteFile && !it.isFile }
    }

    companion object {
        // shortcut: publication is single-process; use an OS lock before adding another app process.
        private val publicationLock = Any()
        const val DIRECTORY = "attachments"
        const val PENDING = ".pending"
        fun key(id: String) = "$DIRECTORY/$id"
    }
}

/** Limits apply to NEW imports. Existing owned files are resolved without deleting or rewriting them. */
internal object AttachmentImageLimits {
    const val MAX_BYTES = 20L * 1024 * 1024
    const val MAX_DIMENSION = 32_768
    const val MAX_PIXELS = 100_000_000L
    const val DECODE_SIDE = 2_048
    const val THUMBNAIL_SIDE = 256

    fun acceptsDimensions(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width <= MAX_DIMENSION && height <= MAX_DIMENSION &&
            width.toLong() * height <= MAX_PIXELS

    fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while ((width + sample - 1) / sample > DECODE_SIDE || (height + sample - 1) / sample > DECODE_SIDE) {
            sample *= 2
        }
        return sample
    }
}
