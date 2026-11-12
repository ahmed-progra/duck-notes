package app.duck.notes.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.files.AttachmentFiles
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentSecurityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = AttachmentStorageImpl(context)
    private fun image() = File(context.cacheDir, "test-${UUID.randomUUID()}.png").apply {
        outputStream().use { output ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            finally { bitmap.recycle() }
        }
    }
    /** Plant IMAGE_BYTE_LIMIT_RAISED: raise the production limit from20 to21MiB. */
    @Test fun actualByteLimitAccepts20MiBAndRejectsOneByteMoreWithoutResidue() = runBlocking<Unit> {
        val source = image()
        val id = UUID.randomUUID().toString()
        try {
            java.io.RandomAccessFile(source, "rw").use { it.setLength(20L * 1024 * 1024) }
            val key = requireNotNull(storage.store(Uri.fromFile(source).toString(), id))
            assertTrue(storage.exists(key))
            assertEquals(20L * 1024 * 1024, requireNotNull(AttachmentFiles(context.filesDir).resolve(key)).length())
            java.io.RandomAccessFile(source, "rw").use { it.setLength(20L * 1024 * 1024 + 1) }
            val rejected = UUID.randomUUID().toString()
            assertNull(storage.store(Uri.fromFile(source).toString(), rejected))
            assertFalse(storage.exists("attachments/$rejected"))
            assertTrue(File(context.filesDir, "attachments/.pending").listFiles().orEmpty().isEmpty())
            storage.delete(key)
        } finally { source.delete() }
    }
    /** Plant IMAGE_VALIDATION_BYPASS: remove validImage(pending) rejection. */
    @Test fun corruptContentAndTraversalCannotPublishOrDeleteOutsideFiles() = runBlocking<Unit> {
        val source = File(context.cacheDir, "private-test-${UUID.randomUUID()}").apply { writeText("SYNTHETIC_PRIVATE_SENTINEL") }
        try {
            assertNull(storage.store(Uri.fromFile(source).toString(), UUID.randomUUID().toString()))
            assertNull(storage.store(Uri.fromFile(source).toString(), "../escape"))
            assertFalse(storage.delete(source.absolutePath))
            assertFalse(storage.exists(source.absolutePath))
            assertTrue(source.isFile)
            val valid = image()
            try {
                val id = UUID.randomUUID().toString()
                val key = requireNotNull(storage.store(Uri.fromFile(valid).toString(), id))
                assertNull(storage.store(Uri.fromFile(source).toString(), id))
                assertTrue(storage.exists(key))
                assertTrue(storage.exists("/old-install/files/attachments/$id"))
                storage.delete(key)
            } finally { valid.delete() }
        } finally { source.delete() }
    }

    /** Plants IMAGE_DIMENSION_CHECK_UNUSED and IMAGE_SAMPLING_UNUSED in the real decoder wiring. */
    @Test fun actualDecoderRejectsOverlongSidesAndAcceptsSampledWideAndTallImages() = runBlocking<Unit> {
        for ((width, height) in listOf(32_769 to 1, 1 to 32_769, 8192 to 2, 2 to 8192)) {
            val source = File.createTempFile("decoder-", ".png", context.cacheDir)
            val id = UUID.randomUUID().toString()
            try {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try { source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                val key = storage.store(Uri.fromFile(source).toString(), id)
                if (width == 32_769 || height == 32_769) assertNull("long side $width x $height", key)
                else {
                    assertEquals("attachments/$id", key)
                    assertTrue(storage.exists(requireNotNull(key)))
                }
            } finally { source.delete(); storage.delete("attachments/$id") }
        }
    }

    /** Plants ANDROID_HARDLINK_PUBLICATION and ATTACHMENT_OVERWRITE in AttachmentFiles.publish. */
    @Test fun completedImagePublishesExclusivelyInsideAndroidFilesDirectory() {
        val files = AttachmentFiles(context.filesDir)
        val id = UUID.randomUUID().toString()
        val target = requireNotNull(files.target(id))
        val temporary = files.temporary()
        val source = image()
        try {
            source.copyTo(temporary, overwrite = true)
            files.publish(temporary, target)
            assertTrue(target.isFile)
            assertArrayEquals(source.readBytes(), target.readBytes())
            assertFalse("publication consumes the temporary name", temporary.exists())
            val duplicate = files.temporary().apply { writeText("different bytes") }
            try {
                try { files.publish(duplicate, target); fail("published target overwritten") }
                catch (_: java.nio.file.FileAlreadyExistsException) { }
                assertArrayEquals(source.readBytes(), target.readBytes())
            } finally { duplicate.delete() }
        } finally { temporary.delete(); target.delete(); source.delete() }
    }

    /** Plant RETURN_CANCEL_LEAK: remove the outer cancellation catch's published-file deletion. */
    @Test fun cancellationWhileCompletedCopyWaitsForCallerDispatchCleansPublishedFile() {
        val queue = LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val source = image()
        val id = UUID.randomUUID().toString()
        val target = requireNotNull(AttachmentFiles(context.filesDir).target(id))
        val importing = scope.async { storage.store(Uri.fromFile(source).toString(), id) }
        try {
            requireNotNull(queue.poll(10, TimeUnit.SECONDS)).run()
            val completedCopy = requireNotNull(queue.poll(10, TimeUnit.SECONDS))
            assertTrue("positive control: IO published before return dispatch", target.isFile)
            importing.cancel()
            completedCopy.run()
            while (!importing.isCompleted) requireNotNull(queue.poll(10, TimeUnit.SECONDS)).run()
            assertTrue(importing.isCancelled)
            assertFalse("discarded return value must not orphan the published file", target.exists())
            assertTrue(File(context.filesDir, "attachments/.pending").listFiles().orEmpty().isEmpty())
        } finally { scope.cancel(); source.delete(); target.delete() }
    }
}
