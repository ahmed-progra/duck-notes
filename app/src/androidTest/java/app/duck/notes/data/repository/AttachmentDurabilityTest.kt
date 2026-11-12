package app.duck.notes.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * `AttachmentStorageImpl` makes a real, independent copy — the durability guarantee the whole feature
 * rests on, and the one half a JVM fake cannot exercise (there is no `ContentResolver`, no filesystem).
 *
 * ## What this proves, and what it honestly does not
 *
 * It proves **copy-independence**: after `store`, deleting the *source* leaves the durable copy intact
 * and byte-for-byte equal. That is the property that matters — an attachment must not vanish when the
 * thing it was copied from does.
 *
 * It does **not** exercise the picker's `content://` **grant revocation**. That grant is an OS
 * mechanism tied to the real `PickVisualMedia` flow; an instrumented test cannot mint or revoke one,
 * so this uses a `file://` source it controls and deletes by hand. Deleting the source file is a
 * *stronger* teardown than a revoked grant in one sense — the bytes are actually gone — but it is a
 * different mechanism, and calling it "grant revocation" would be the kind of overclaim
 * `DUCK_NOTES_BUILD_PROMPT.md` records under *misdirected controls*. The copy runs synchronously right
 * after the pick in production, well inside the grant's lifetime, so the untested gap is small; it is
 * named here rather than papered over.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentDurabilityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = AttachmentStorageImpl(context)

    // runBlocking<Unit>: the block ends in File.delete() (a Boolean), and a JUnit @Test must be void.
    @Test
    fun theCopySurvivesTheSourceBeingDeleted() = runBlocking<Unit> {
        val source = File(context.cacheDir, "src-${UUID.randomUUID()}").apply { writeBytes(PAYLOAD) }
        val id = UUID.randomUUID().toString()

        val durable = storage.store(Uri.fromFile(source).toString(), id)
            ?: error("store returned null for a readable source")
        assertTrue("stored under filesDir/attachments, not left at the source", "attachments/" in durable)
        assertFalse("the durable path is not the source path", durable == source.absolutePath)

        // Delete the SOURCE. See the class KDoc: this is copy-independence, not grant revocation.
        assertTrue("source deleted", source.delete())

        assertTrue("the copy must still exist after the source is gone", storage.exists(durable))
        assertArrayEquals("the copy must hold the original bytes", PAYLOAD, requireNotNull(app.duck.notes.core.files.AttachmentFiles(context.filesDir).resolve(durable)).readBytes())

        storage.delete(durable)
    }

    /** The one failure `AttachImage` must not turn into a recorded row: an unreadable source → null. */
    @Test
    fun anUnreadableSourceReturnsNull() = runBlocking {
        val missing = Uri.fromFile(File(context.cacheDir, "does-not-exist-${UUID.randomUUID()}")).toString()
        assertNull(storage.store(missing, UUID.randomUUID().toString()))
    }

    private companion object {
        val PAYLOAD: ByteArray get() = java.io.ByteArrayOutputStream().use { output ->
            val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            try { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
            finally { bitmap.recycle() }
        }
    }
}
