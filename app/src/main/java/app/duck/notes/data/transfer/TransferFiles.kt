package app.duck.notes.data.transfer

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal data class TransferJob(val id: String, val directory: File)

/** Private transfer staging is never included in Android backup or exposed through FileProvider. */
internal class TransferFiles(private val context: Context) {
    private fun root(): File {
        val root = File(context.noBackupFilesDir.canonicalFile, "transfers")
        if (root.canonicalFile != root.absoluteFile || root.isFile || (!root.isDirectory && !root.mkdirs() && !root.isDirectory)) {
            throw IOException("Temporary storage is unavailable")
        }
        return root
    }

    fun create(): TransferJob = synchronized(jobsLock) {
        val id = UUID.randomUUID().toString()
        val directory = File(root(), id)
        if (!directory.mkdir()) throw IOException("Temporary storage is unavailable")
        activeJobs.add(directory.absolutePath)
        TransferJob(id, directory)
    }

    fun find(id: String): TransferJob {
        if (!validId(id)) throw IOException("Transfer expired")
        val directory = File(root(), id)
        if (directory.canonicalFile != directory.absoluteFile || !directory.isDirectory ||
            synchronized(jobsLock) { directory.absolutePath !in activeJobs }) throw IOException("Transfer expired")
        return TransferJob(id, directory)
    }

    fun close(id: String) {
        if (!validId(id)) throw IOException("Invalid transfer cleanup target")
        val directory = File(root(), id)
        synchronized(jobsLock) { activeJobs.remove(directory.absolutePath) }
        if (directory.exists() || Files.isSymbolicLink(directory.toPath())) removeOwned(directory, root())
    }

    fun cleanAbandoned() {
        val abandoned = synchronized(jobsLock) {
            root().listFiles().orEmpty().filter { candidate ->
                candidate.absolutePath !in activeJobs && validId(candidate.name)
            }
        }
        abandoned.forEach { removeOwned(it, root()) }
    }

    fun cleanSharedExports(now: Long = System.currentTimeMillis()) {
        val parent = File(context.cacheDir.canonicalFile, "exports")
        if (!parent.exists()) return
        if (parent.canonicalFile != parent.absoluteFile || !parent.isDirectory) throw IOException("Sharing cleanup unavailable")
        parent.listFiles().orEmpty().filter { validId(it.name) && it.lastModified() < now - 86_400_000L }
            .forEach { removeOwned(it, parent) }
    }

    private fun removeOwned(directory: File, parent: File) {
        if (parent.canonicalFile != parent.absoluteFile || directory.absoluteFile.parentFile != parent || !validId(directory.name)) throw IOException("Invalid transfer cleanup target")
        // walkFileTree does not follow symbolic links, including links planted inside a failed job.
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private companion object {
        fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
        val jobsLock = Any()
        val activeJobs = mutableSetOf<String>()
    }
}
