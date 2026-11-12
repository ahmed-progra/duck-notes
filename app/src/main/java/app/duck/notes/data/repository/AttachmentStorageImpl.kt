package app.duck.notes.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.core.files.AttachmentImageLimits
import app.duck.notes.domain.repository.AttachmentStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject

/**
 * The one Android-touching half of attachments: copies the picker's `content://` bytes into
 * `filesDir/attachments/<id>` and returns a portable `attachments/<id>` key.
 *
 * This is where the durability guarantee is actually made. The picker returns a revocable read grant
 * on a `content://`; by the next launch it is gone. Copying the bytes now — inside app-private storage,
 * so no `FileProvider` and no runtime permission — is what makes the attachment outlive the grant. A
 * New images are limited to 20MiB of ACTUAL bytes, 100MP and 32768px per side. Their bytes must
 * decode as PNG/JPEG/WebP/GIF with a validation bitmap no larger than 2048px per side. Provider MIME
 * claims and reported lengths cannot waive these checks. Existing images are not re-imported.
 *
 * Storage methods hop to [Dispatchers.IO]: a file copy on the main thread is exactly the jank the
 * editor's 60fps target cannot afford.
 */
class AttachmentStorageImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AttachmentStorage {

    private val files get() = AttachmentFiles(context.filesDir)

    override suspend fun store(sourceUri: String, id: String): String? {
        var published: File? = null
        try {
            return withContext(Dispatchers.IO) {
                var temporary: File? = null
                val job = currentCoroutineContext()
                try {
                    val owned = files
                    val target = owned.target(id) ?: return@withContext null
                    if (target.exists()) return@withContext null
                    val uri = sourceUri.toUri()
                    if (uri.scheme !in setOf("content", "file", "android.resource")) return@withContext null
                    val pending = owned.temporary().also { temporary = it }
                    runInterruptible {
                        job.ensureActive()
                        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Image source is unavailable")
                        input.use { source ->
                            FileOutputStream(pending).use { output ->
                                val buffer = ByteArray(8 * 1024)
                                var copied = 0L
                                while (true) {
                                    job.ensureActive()
                                    val count = source.read(buffer, 0, minOf(buffer.size.toLong(), AttachmentImageLimits.MAX_BYTES - copied + 1).toInt())
                                    job.ensureActive()
                                    if (count < 0) break
                                    if (count == 0) continue
                                    copied += count
                                    if (copied > AttachmentImageLimits.MAX_BYTES) throw IOException("Image exceeds import limit")
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                        }
                    }
                    job.ensureActive()
                    if (!validImage(pending)) return@withContext null
                    job.ensureActive()
                    owned.publish(pending, target)
                    published = target
                    job.ensureActive()
                    AttachmentFiles.key(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    job.ensureActive()
                    null
                } catch (_: OutOfMemoryError) {
                    job.ensureActive()
                    null
                } finally {
                    temporary?.delete()
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext can discard a completed result while dispatching back to the caller.
            withContext(NonCancellable + Dispatchers.IO) {
                published?.let { delete(AttachmentFiles.key(it.name)) }
            }
            throw cancelled
        }
    }

    override suspend fun delete(uri: String): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        try {
            val target = files.resolve(uri) ?: return@withContext false
            val deleted = !target.exists() || target.delete()
            if (deleted) files.deletionMarker(target.name, createDirectory = false)?.delete()
            else markForDeletion(uri)
            deleted
        } catch (_: IOException) { false } catch (_: SecurityException) { false }
    }

    override suspend fun markForDeletion(uri: String): Unit = withContext(Dispatchers.IO) {
        val target = files.resolve(uri) ?: throw IOException("Invalid attachment cleanup target")
        val marker = files.deletionMarker(target.name) ?: throw IOException("Attachment cleanup is unavailable")
        FileOutputStream(marker).use { it.fd.sync() }
    }

    override suspend fun pendingDeletions(): List<String> = withContext(Dispatchers.IO) {
        files.pendingDeletions()
    }

    override suspend fun exists(uri: String): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        try { files.resolve(uri)?.isFile == true } catch (_: IOException) { false } catch (_: SecurityException) { false }
    }

    private fun validImage(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outMimeType !in setOf("image/png", "image/jpeg", "image/webp", "image/gif") ||
            !AttachmentImageLimits.acceptsDimensions(bounds.outWidth, bounds.outHeight)) return false
        val options = BitmapFactory.Options().apply {
            inSampleSize = AttachmentImageLimits.sampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return false
        return try {
            bitmap.width <= AttachmentImageLimits.DECODE_SIDE && bitmap.height <= AttachmentImageLimits.DECODE_SIDE
        } finally {
            bitmap.recycle()
        }
    }
}
