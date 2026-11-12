package app.duck.notes.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.system.Os
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.data.local.IsolatedAppDataContext
import app.duck.notes.di.DatabaseModule
import app.duck.notes.domain.Clock
import app.duck.notes.domain.usecase.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentDeletionRecoveryTest {
    private fun image(base: Context): File = File.createTempFile("cleanup-boundary-", ".png", base.cacheDir).apply {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try { outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    /** Plants DELETE_INTENT_MISSING and DELETE_FAILED_UNLINK_NO_INTENT at independent boundaries. */
    @Test fun committedRemovalHasIntentBeforeCleanupAndFailedUnreferencedUnlinkAlsoQueuesRecovery() = runBlocking<Unit> {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = IsolatedAppDataContext(base, File(base.cacheDir, "delete-boundary-${UUID.randomUUID()}"))
        val database = DatabaseModule.provideDatabase(context)
        val storage = AttachmentStorageImpl(context)
        val source = image(base)
        val directory = File(context.filesDir, "attachments")
        try {
            val notes = NoteRepositoryImpl(database.noteDao())
            val attachments = AttachmentRepositoryImpl(database.attachmentDao())
            val save = SaveNote(notes, DeriveTitle(), ParseTags(), ResolveWikiLinks(), Clock { 100L })
            val note = save(null, "cleanup boundary")
            val row = requireNotNull(AttachImage(storage, attachments, notes, save, Clock { 100L })(
                note.id, Uri.fromFile(source).toString(), "image/png"))
            val stoppedBeforeUnlink = object : app.duck.notes.domain.repository.AttachmentStorage by storage {
                override suspend fun delete(uri: String): Boolean = throw kotlinx.coroutines.CancellationException("controlled stop after commit")
            }
            try {
                DeleteAttachment(stoppedBeforeUnlink, attachments, notes, save)(row.id, note.id)
                fail("controlled stop must propagate")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertNull(attachments.get(row.id))
            assertTrue(storage.exists(row.uri))
            assertEquals("intent must precede cleanup dispatch", listOf(row.uri), storage.pendingDeletions())
            RecoverAttachmentDeletions(attachments, AttachmentStorageImpl(context))()
            assertFalse(storage.exists(row.uri))
            assertTrue(storage.pendingDeletions().isEmpty())

            val key = requireNotNull(storage.store(Uri.fromFile(source).toString(), UUID.randomUUID().toString()))
            assertNull(attachments.get(key.substringAfterLast('/')))
            Os.chmod(directory.path, 365)
            assertFalse(storage.delete(key))
            assertTrue(storage.exists(key))
            assertEquals("failed unlink must retain a portable retry target", listOf(key), storage.pendingDeletions())
            Os.chmod(directory.path, 448)
            RecoverAttachmentDeletions(attachments, AttachmentStorageImpl(context))()
            assertFalse(storage.exists(key))
            assertTrue(storage.pendingDeletions().isEmpty())
        } finally {
            if (directory.exists()) Os.chmod(directory.path, 448)
            database.close(); source.delete()
            check(context.directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            context.directory.deleteRecursively()
        }
    }

    /** Plants ATTACH_FAILURE_LEAK and ATTACH_FAILURE_DELETES_REFERENCED in AttachImage's catch. */
    @Test fun realFailedAttachmentTransactionCleansBytesAndCancellationAfterCommitPreservesThem() = runBlocking<Unit> {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = IsolatedAppDataContext(base, File(base.cacheDir, "attach-boundary-${UUID.randomUUID()}"))
        val database = DatabaseModule.provideDatabase(context)
        val storage = AttachmentStorageImpl(context)
        val source = image(base)
        try {
            val notes = NoteRepositoryImpl(database.noteDao())
            val attachments = AttachmentRepositoryImpl(database.attachmentDao())
            val clock = Clock { 100L }
            fun save(repository: app.duck.notes.domain.repository.NoteRepository) =
                SaveNote(repository, DeriveTitle(), ParseTags(), ResolveWikiLinks(), clock)
            val note = save(notes)(null, "transaction boundary #work [[Pond]]")
            database.useWriterConnection {
                it.execSQL("CREATE TRIGGER test_reject_attachment BEFORE INSERT ON attachments " +
                    "BEGIN SELECT RAISE(ABORT, 'controlled_attachment_insert'); END")
            }
            val failure = try {
                runCatching { AttachImage(storage, attachments, notes, save(notes), clock)(
                    note.id, Uri.fromFile(source).toString(), "image/png") }.exceptionOrNull()
            } finally {
                database.useWriterConnection { it.execSQL("DROP TRIGGER test_reject_attachment") }
            }
            assertTrue("real SQLite insertion failure required", failure is androidx.sqlite.SQLiteException)
            assertTrue(failure?.message.orEmpty().contains("controlled_attachment_insert"))
            assertEquals(note.body, notes.getNote(note.id)?.body)
            assertTrue(attachments.forNote(note.id).isEmpty())
            assertEquals("failed transaction must not leave a published file", 0,
                File(context.filesDir, "attachments").listFiles().orEmpty().count { it.isFile })
            val afterCommit = object : app.duck.notes.domain.repository.NoteRepository by notes {
                override suspend fun saveWithAttachment(note: app.duck.notes.domain.model.Note, tagNames: List<String>, linkTitles: List<String>, attachment: app.duck.notes.domain.model.Attachment) {
                    notes.saveWithAttachment(note, tagNames, linkTitles, attachment)
                    throw kotlinx.coroutines.CancellationException("controlled delivery after real commit")
                }
            }
            val cancelled = runCatching { AttachImage(storage, attachments, afterCommit, save(afterCommit), clock)(
                note.id, Uri.fromFile(source).toString(), "image/png") }.exceptionOrNull()
            assertTrue(cancelled is kotlinx.coroutines.CancellationException)
            val row = attachments.forNote(note.id).single()
            assertTrue(requireNotNull(notes.getNote(note.id)).body.contains(row.id))
            assertTrue("committed reference must retain its real image", storage.exists(row.uri))
        } finally {
            database.close(); source.delete()
            check(context.directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            context.directory.deleteRecursively()
        }
    }

    /** Plant DELETE_RECOVERY_REFERENCED: remove recovery's attachment-row check. */
    @Test fun realUnlinkFailureSurvivesReopenAndRecoveryPreservesReferencedImages() = runBlocking<Unit> {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = IsolatedAppDataContext(base, File(base.cacheDir, "delete-recovery-${UUID.randomUUID()}"))
        var database = DatabaseModule.provideDatabase(context)
        val storage = AttachmentStorageImpl(context)
        val image = File.createTempFile("delete-recovery-", ".png", base.cacheDir)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try { image.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        val directory = File(context.filesDir, "attachments")
        try {
            val notes = NoteRepositoryImpl(database.noteDao())
            val attachments = AttachmentRepositoryImpl(database.attachmentDao())
            val save = SaveNote(notes, DeriveTitle(), ParseTags(), ResolveWikiLinks(), Clock { 100L })
            val note = save(null, "cleanup control")
            val attach = AttachImage(storage, attachments, notes, save, Clock { 100L })
            val removed = requireNotNull(attach(note.id, Uri.fromFile(image).toString(), "image/png"))
            val retained = requireNotNull(attach(note.id, Uri.fromFile(image).toString(), "image/png"))
            val delete = DeleteAttachment(storage, attachments, notes, save)
            delete(retained.id, "another-note")
            assertNotNull(attachments.get(retained.id))
            assertTrue(storage.pendingDeletions().isEmpty())
            storage.markForDeletion(retained.uri)
            Os.chmod(directory.path, 365) // 0555: deny unlink in the parent, leave marker directory writable.
            assertFalse("positive control: real app UID cannot unlink", requireNotNull(AttachmentFiles(context.filesDir).resolve(removed.uri)).delete())
            delete(removed.id, note.id)
            assertNull(attachments.get(removed.id))
            assertFalse(requireNotNull(notes.getNote(note.id)).body.contains(removed.id))
            assertTrue(storage.exists(removed.uri))
            assertEquals(setOf(removed.uri, retained.uri), storage.pendingDeletions().toSet())
            database.close()
            database = DatabaseModule.provideDatabase(context)
            Os.chmod(directory.path, 448) // 0700
            val reopenedStorage = AttachmentStorageImpl(context)
            RecoverAttachmentDeletions(AttachmentRepositoryImpl(database.attachmentDao()), reopenedStorage)()
            assertFalse(reopenedStorage.exists(removed.uri))
            assertTrue(reopenedStorage.exists(retained.uri))
            assertEquals(listOf(retained.uri), reopenedStorage.pendingDeletions())
            assertNotNull(database.attachmentDao().getById(retained.id))
        } finally {
            if (directory.exists()) Os.chmod(directory.path, 448)
            database.close()
            image.delete()
            check(context.directory.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            context.directory.deleteRecursively()
        }
    }
}
