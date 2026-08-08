package app.duck.notes.data.repository

import android.content.Context
import androidx.core.net.toUri
import app.duck.notes.domain.repository.AttachmentStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * The one Android-touching half of attachments: copies the picker's `content://` bytes into
 * `filesDir/attachments/<id>` and hands back the durable path.
 *
 * This is where the durability guarantee is actually made. The picker returns a revocable read grant
 * on a `content://`; by the next launch it is gone. Copying the bytes now — inside app-private storage,
 * so no `FileProvider` and no runtime permission — is what makes the attachment outlive the grant. A
 * source that cannot be opened returns null, and `AttachImage` records nothing for it.
 *
 * All three methods hop to [Dispatchers.IO]: a file copy on the main thread is exactly the jank the
 * editor's 60fps target cannot afford.
 */
class AttachmentStorageImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : AttachmentStorage {

    private fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    override suspend fun store(sourceUri: String, id: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val target = File(dir(), id)
            val input = context.contentResolver.openInputStream(sourceUri.toUri())
                ?: return@runCatching null
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            target.absolutePath
        }.getOrNull()
    }

    override suspend fun delete(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(uri).takeIf { it.exists() }?.delete() == true }.getOrDefault(false)
    }

    override suspend fun exists(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { File(uri).exists() }.getOrDefault(false)
    }

    private companion object {
        const val DIR = "attachments"
    }
}
