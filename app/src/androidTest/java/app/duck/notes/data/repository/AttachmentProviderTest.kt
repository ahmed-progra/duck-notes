package app.duck.notes.data.repository

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.files.AttachmentFiles
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = AttachmentStorageImpl(context)
    private val base = Uri.parse("content://${ImageSourceProvider.AUTHORITY}/")
    private fun control(method: String) = context.contentResolver.call(base, method, null, null)
    private fun source(path: String) = base.buildUpon().appendPath(path).build().toString()
    private fun pending() = File(context.filesDir, "attachments/.pending").listFiles().orEmpty()
    private suspend fun awaitOpen() {
        withTimeout(10_000) { while (control("status")?.getBoolean("opened") != true) delay(10) }
    }

    @Before fun resetProviderFixture() { control("reset") }

    /** Real OS grant/revoke across different UIDs, then independent bytes after revocation. */
    @Test fun revokedProviderGrantRejectsNewCopiesButPreservesTheCommittedCopy() = runBlocking<Unit> {
        control("revoke")
        assertNull(storage.store(source("valid"), UUID.randomUUID().toString()))
        control("grant")
        val key = requireNotNull(storage.store(source("valid"), UUID.randomUUID().toString()))
        try {
            control("revoke")
            try { context.contentResolver.openInputStream(Uri.parse(source("valid")))?.close(); fail("OS grant was not revoked") }
            catch (_: SecurityException) { }
            assertTrue(storage.exists(key))
            assertNull(storage.store(source("valid"), UUID.randomUUID().toString()))
        } finally { storage.delete(key); control("revoke") }
    }

    /** Plant COPY_CANCEL_PUBLISHED: omit cooperative checks and cancellation cleanup in store. */
    @Test fun cancellationDuringProviderOpenAndInterruptedStreamLeaveNoPublishedFiles() = runBlocking<Unit> {
        control("grant")
        val id = UUID.randomUUID().toString()
        val importing = async(Dispatchers.IO) { storage.store(source("blocked"), id) }
        try {
            awaitOpen()
            importing.cancel()
            control("release")
            importing.join()
            assertFalse(storage.exists(AttachmentFiles.key(id)))
            assertTrue(pending().isEmpty())
            assertNull(storage.store(source("interrupted"), UUID.randomUUID().toString()))
            assertTrue(pending().isEmpty())
        } finally { control("release"); importing.cancelAndJoin(); control("revoke") }
    }

    /** Kernel ENOSPC via /dev/full at the copy boundary, without filling the emulator disk. */
    @Test fun fullStorageWriteFailureCleansTemporaryDataAndKeepsExistingAttachment() = runBlocking<Unit> {
        control("grant")
        val existing = requireNotNull(storage.store(source("valid"), UUID.randomUUID().toString()))
        val id = UUID.randomUUID().toString()
        val importing = async(Dispatchers.IO) { storage.store(source("blocked"), id) }
        try {
            awaitOpen()
            val temporary = pending().single()
            assertTrue(temporary.delete())
            Os.symlink("/dev/full", temporary.path)
            control("release")
            assertNull(importing.await())
            assertTrue(pending().isEmpty())
            assertFalse(storage.exists(AttachmentFiles.key(id)))
            assertTrue(storage.exists(existing))
        } finally { control("release"); importing.cancelAndJoin(); storage.delete(existing); control("revoke") }
    }
}
