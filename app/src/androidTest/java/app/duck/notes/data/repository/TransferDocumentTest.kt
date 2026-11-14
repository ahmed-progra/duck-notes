package app.duck.notes.data.repository

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.graphics.Bitmap
import androidx.room.Room
import app.duck.notes.data.local.DuckSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.transfer.NoteArchiveStore
import app.duck.notes.data.transfer.TransferFiles
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import app.duck.notes.domain.usecase.AttachImage
import app.duck.notes.domain.usecase.SaveNote
import app.duck.notes.domain.Clock
import app.duck.notes.domain.markdown.MarkdownScanner
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferDocumentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val base = Uri.parse("content://${TransferDocumentProvider.AUTHORITY}/")
    private lateinit var db: DuckDatabase
    private lateinit var repository: NoteTransferRepositoryImpl
    private val owned = mutableListOf<String>()
    private val note = NoteEntity(id = UUID.randomUUID().toString(), title = "Transfer", body = "# Transfer\n#research\nLiteral text", createdAt = 1, modifiedAt = 2)
    private fun control(method: String, bytes: ByteArray? = null) = context.contentResolver.call(base, method, null, bytes?.let { Bundle().apply { putByteArray("bytes", it) } })
    private fun uri(path: String) = base.buildUpon().appendPath(path).build().toString()
    @Before fun setUp() = runBlocking {
        control("reset"); control("grant")
        db = Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java).setDriver(DuckSQLiteDriver()).addCallback(DuckDatabaseCallback()).build()
        db.noteDao().saveNoteWithTagsAndLinks(note, listOf("research"))
        repository = NoteTransferRepositoryImpl(context, NoteArchiveStore(context, db, AttachmentStorageImpl(context), DeriveTitle(), ParseTags(), ResolveWikiLinks()))
    }
    @After fun close() = runBlocking {
        control("release")
        if (::repository.isInitialized) owned.forEach { repository.discardPrepared(it) }
        if (::db.isInitialized) {
            db.attachmentDao().all().forEach { assertTrue(AttachmentStorageImpl(context).delete(it.uri)) }
            db.close()
        }
        control("revoke")
        Unit
    }

    /** Real source/sink providers and FileProvider, including the actual exported manifest policy. */
    @Test fun markdownRoundTripAndSharingUseActualDocumentBytes() = runBlocking {
        val document = repository.prepareExport(listOf(note.id)).also { owned += it.id }
        assertEquals("text/markdown", document.mime)
        repository.writePrepared(document.id, uri("output"))
        val bytes = requireNotNull(control("output")?.getByteArray("bytes"))
        assertArrayEquals(note.body.toByteArray(Charsets.UTF_8), bytes)
        control("source", bytes)
        val input = repository.prepareImport(uri("source")).also { owned += it }
        val report = repository.importPrepared(input)
        assertEquals(1, report.addedNoteIds.size)
        assertNotEquals(note.id, report.addedNoteIds.single())
        assertEquals(note.body, db.noteDao().getById(report.addedNoteIds.single())?.body)
        val shared = Uri.parse(repository.sharePrepared(document))
        try {
            assertEquals("app.duck.notes.exports", shared.authority)
            assertFalse(shared.toString().contains("transfers"))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(shared)?.use { it.readBytes() })
            @Suppress("DEPRECATION") val provider = requireNotNull(context.packageManager.resolveContentProvider(shared.authority ?: "", 0))
            assertFalse(provider.exported)
            assertTrue(provider.grantUriPermissions)
            val escape = Uri.parse("content://${shared.authority}/exports/../transfers/private")
            try { context.contentResolver.openInputStream(escape)?.close(); fail("Share provider exposed private staging") } catch (_: SecurityException) { } catch (_: IllegalArgumentException) { }
        } finally { context.contentResolver.delete(shared, null, null) }
        assertEquals(note.body, db.noteDao().getById(note.id)?.body)
    }

    @Test fun exportKeepsStoredMarkersWhileStandaloneImportNormalizesOnlyItsBom() = runBlocking {
        val body = "\uFEFF" + note.body
        db.noteDao().saveNoteWithTagsAndLinks(note.copy(body = body), listOf("research"))
        assertEquals(body, db.noteDao().getById(note.id)?.body)
        val document = repository.prepareExport(listOf(note.id)).also { owned += it.id }
        repository.writePrepared(document.id, uri("output"))
        assertArrayEquals(body.toByteArray(Charsets.UTF_8), control("output")?.getByteArray("bytes"))
        control("source", body.toByteArray(Charsets.UTF_8))
        val input = repository.prepareImport(uri("source")).also { owned += it }
        val report = repository.importPrepared(input)
        assertEquals(note.body, db.noteDao().getById(report.addedNoteIds.single())?.body)
    }

    /** Plant: suppress provider write/close failure or skip partial-document cleanup. */
    @Test fun realKernelWriteFailureIsNotSuccessAndAttemptsPartialDocumentRemoval() = runBlocking {
        val document = repository.prepareExport(listOf(note.id)).also { owned += it.id }
        try { repository.writePrepared(document.id, uri("full")); fail("ENOSPC reported as success") } catch (_: IOException) { }
        assertEquals(1, control("status")?.getInt("deleted"))
        assertEquals(1, db.noteDao().count())
        repository.writePrepared(document.id, uri("output"))
        assertArrayEquals(note.body.toByteArray(), control("output")?.getByteArray("bytes"))
    }

    /** Plants: permit file:// destinations, ignore revoked grants, or replace malformed UTF-8. */
    @Test fun revokedGrantsUnsafeSchemesAndMalformedUtf8LeaveNotesUnchanged() = runBlocking {
        control("source", byteArrayOf(0xff.toByte()))
        try { repository.prepareImport(uri("source")); fail("Malformed UTF-8 accepted") } catch (_: IOException) { }
        control("revoke")
        try { repository.prepareImport(uri("source")); fail("Revoked grant accepted") } catch (_: IOException) { } catch (_: SecurityException) { }
        val document = repository.prepareExport(listOf(note.id)).also { owned += it.id }
        val sentinel = File(context.noBackupFilesDir, "transfer-sentinel-${UUID.randomUUID()}").apply { writeText("keep") }
        try {
            try { repository.writePrepared(document.id, Uri.fromFile(sentinel).toString()); fail("Private file destination accepted") } catch (_: IOException) { }
            assertEquals("keep", sentinel.readText())
        } finally { sentinel.delete() }
        assertEquals(1, db.noteDao().count())
        assertEquals(note.body, db.noteDao().getById(note.id)?.body)
        assertTrue(TransferFiles(context).find(document.id).directory.isDirectory)
    }

    /** Plants: ignore cancellation at provider-open/read boundaries or leak failed private stages. */
    @Test fun cancellationAndInterruptedProvidersLeaveNoStagedImportOrDatabaseChange() = runBlocking {
        val directory = File(context.noBackupFilesDir, "transfers")
        fun jobs() = directory.listFiles().orEmpty().map { it.name }.toSet()
        val before = jobs()
        for (path in listOf("blocked", "blockedstream")) {
            control("reset")
            val importing = async(Dispatchers.IO) { repository.prepareImport(uri(path)) }
            try {
                withTimeout(10_000) { while ((control("status")?.getInt("opened") ?: 0) == 0) delay(10) }
                importing.cancel()
                val stopped = withTimeoutOrNull(5_000) { importing.join(); true } ?: false
                assertTrue("$path import did not cancel within 5000 ms", stopped)
                assertEquals(before, jobs())
                assertEquals(1, db.noteDao().count())
            } finally { control("release"); importing.cancelAndJoin() }
        }
        try { repository.prepareImport(uri("interrupted")); fail("Broken provider EOF accepted") } catch (_: IOException) { }
        assertEquals(before, jobs())
        assertEquals(note.body, db.noteDao().getById(note.id)?.body)
    }

    /** Plant: a partial provider write or revoked write grant must never produce success. */
    @Test fun partialProviderWriteAndRevokedWriteGrantAreVisibleFailures() = runBlocking {
        val body = "# Transfer\n" + "a".repeat(256 * 1024)
        db.noteDao().saveNoteWithTagsAndLinks(note.copy(body = body), emptyList())
        val document = repository.prepareExport(listOf(note.id)).also { owned += it.id }
        try { repository.writePrepared(document.id, uri("partial")); fail("Partial pipe write reported as success") } catch (_: IOException) { }
        assertEquals(1, control("status")?.getInt("deleted"))
        control("revoke")
        try { repository.writePrepared(document.id, uri("output")); fail("Revoked write permission accepted") } catch (_: SecurityException) { } catch (_: IOException) { }
        assertEquals(body, db.noteDao().getById(note.id)?.body)
    }

    /** Plant: catch staging failure only inside withContext, missing prompt cancellation on return. */
    @Test fun cancellationBeforePreparedResultDeliveryDoesNotLeakPrivateStages() = runBlocking {
        val root = File(context.noBackupFilesDir, "transfers")
        fun jobs() = root.listFiles().orEmpty().map { it.name }.toSet()
        control("source", note.body.toByteArray(Charsets.UTF_8))
        for (importing in listOf(false, true)) {
            val before = jobs()
            val callbacks = LinkedBlockingQueue<Runnable>()
            val caller = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { callbacks.add(block) }
            }
            val lifetime = SupervisorJob()
            val operation = CoroutineScope(lifetime + caller).async {
                if (importing) repository.prepareImport(uri("source")) else repository.prepareExport(listOf(note.id)).id
            }
            try {
                checkNotNull(callbacks.poll(10, TimeUnit.SECONDS)).run()
                val delivery = checkNotNull(callbacks.poll(10, TimeUnit.SECONDS)) { "Real IO did not finish" }
                operation.cancel()
                delivery.run()
                withTimeout(10_000) {
                    while (!operation.isCompleted) {
                        callbacks.poll()?.run()
                        delay(5)
                    }
                }
                assertEquals("Prepared result discarded by cancellation leaked a job (import=$importing)", before, jobs())
            } finally {
                lifetime.cancel()
                (jobs() - before).forEach { TransferFiles(context).close(it) }
            }
        }
    }

    /** Actual image capture/storage, ZIP export, provider write/read and atomic import form one path. */
    @Test fun imagesRoundTripThroughTheActualDocumentProviderWithNewIdentities() = runBlocking {
        val source = File.createTempFile("portable-image-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        try {
            val notes = NoteRepositoryImpl(db.noteDao())
            val attachments = AttachmentRepositoryImpl(db.attachmentDao())
            val storage = AttachmentStorageImpl(context)
            val clock = Clock { 100L }
            val save = SaveNote(notes, DeriveTitle(), ParseTags(), ResolveWikiLinks(), clock)
            val original = requireNotNull(AttachImage(storage, attachments, notes, save, clock)(note.id, Uri.fromFile(source).toString(), "image/png", "Synthetic image"))
            val originalBody = notes.getNote(note.id)?.body
            val document = repository.prepareExport(listOf(note.id)).also { owned += it.id }
            assertEquals("application/zip", document.mime)
            repository.writePrepared(document.id, uri("output"))
            control("source", requireNotNull(control("output")?.getByteArray("bytes")))
            val input = repository.prepareImport(uri("source")).also { owned += it }
            val imported = repository.importPrepared(input).addedNoteIds.single()
            val image = attachments.forNote(imported).single()
            assertNotEquals(original.id, image.id)
            assertEquals(setOf(image.id), MarkdownScanner.attachmentIdsIn(requireNotNull(notes.getNote(imported)).body))
            assertArrayEquals(File(context.filesDir, original.uri).readBytes(), File(context.filesDir, image.uri).readBytes())
            assertEquals(originalBody, notes.getNote(note.id)?.body)
        } finally { source.delete() }
    }
}
