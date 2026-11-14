package app.duck.notes.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.local.entity.AttachmentEntity
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.transfer.*
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDocumentTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val base = Uri.parse("content://${TransferDocumentProvider.AUTHORITY}/")
    private lateinit var root: File
    private lateinit var sourceContext: Context
    private lateinit var targetContext: Context
    private lateinit var sourceDb: DuckDatabase
    private lateinit var targetDb: DuckDatabase
    private lateinit var source: NoteTransferRepositoryImpl
    private lateinit var target: NoteTransferRepositoryImpl
    private lateinit var note: NoteEntity
    private lateinit var image: AttachmentEntity
    private val jobs = mutableListOf<Pair<NoteTransferRepositoryImpl, String>>()
    private val password get() = "دفتر ملاحظاتي المحمول الآمن".toCharArray()
    private fun id() = UUID.randomUUID().toString()
    private fun uri(path: String) = base.buildUpon().appendPath(path).build().toString()
    private fun control(method: String) = app.contentResolver.call(base, method, null, null)
    private fun isolated(name: String) = object : ContextWrapper(app) {
        private val directory = File(root, name).apply { check(mkdir()) }
        override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(directory, "no-backup").apply { mkdirs() }
    }
    private fun database(context: Context) = Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java)
        .setDriver(BundledSQLiteDriver()).addCallback(DuckDatabaseCallback()).build()
    private fun port(context: Context, db: DuckDatabase) = NoteTransferRepositoryImpl(context,
        NoteArchiveStore(context, db, AttachmentStorageImpl(context), DeriveTitle(), ParseTags(), ResolveWikiLinks()))
    @Before fun setUp() = runBlocking {
        control("reset"); control("grant")
        root = File(app.noBackupFilesDir, "backup-documents-${id()}").apply { check(mkdir()) }
        sourceContext = isolated("source"); targetContext = isolated("target")
        sourceDb = database(sourceContext); targetDb = database(targetContext)
        source = port(sourceContext, sourceDb); target = port(targetContext, targetDb)
        val nid = id(); val iid = id()
        check(File(sourceContext.filesDir, "attachments").mkdir())
        val file = requireNotNull(AttachmentFiles(sourceContext.filesDir).target(iid))
        file.outputStream().use { out -> Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).let { bitmap -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle() } }
        note = NoteEntity(id = nid, title = "Portable", body = "# Portable\n#research\n[[Portable]]\n${MarkdownScanner.imageRef(iid)}", createdAt = 123, modifiedAt = 456, isPinned = true, isArchived = true)
        image = AttachmentEntity(iid, nid, AttachmentFiles.key(iid), "image/png", 2, 3, file.length(), 124)
        sourceDb.noteDao().saveNoteWithAttachment(note, listOf("research"), listOf("Portable"), image)
        val tag = requireNotNull(sourceDb.tagDao().getByName("research"))
        sourceDb.tagDao().upsert(tag.copy(isPinned = true, iconKey = "book", sortOrder = 7))
    }
    @After fun close() = runBlocking {
        control("release")
        jobs.forEach { (port, id) -> port.discardPrepared(id) }
        if (::sourceDb.isInitialized) sourceDb.close()
        if (::targetDb.isInitialized) targetDb.close()
        if (::root.isInitialized) root.deleteRecursively()
        control("revoke")
        Unit
    }
    private suspend fun backup(): app.duck.notes.domain.model.PreparedDocument = source.prepareBackup(password).also { jobs += source to it.id }
    private suspend fun incoming(): String = target.prepareRestore(uri("output")).also { jobs += target to it }
    private suspend fun cannotRestore(id: String) {
        try { target.restorePrepared(id); fail("Unauthenticated backup restored") } catch (_: IOException) { }
        assertEquals(0, targetDb.noteDao().count())
    }

    /** Real document bytes cross a provider UID into an independent Room database and file root. */
    @Test fun encryptedDocumentRoundTripPreservesNotesImagesAndCustomization() = runBlocking {
        val document = backup()
        assertEquals("Duck-Notes.duckbackup", document.displayName)
        assertEquals("application/octet-stream", document.mime)
        assertEquals(setOf("payload"), TransferFiles(sourceContext).find(document.id).directory.list().orEmpty().toSet())
        source.writePrepared(document.id, uri("output"))
        val input = incoming()
        cannotRestore(input)
        val pass = password
        target.authenticateRestore(input, pass)
        assertTrue(pass.all { it == '\u0000' })
        val report = target.restorePrepared(input)
        assertEquals(listOf(note.id), report.addedNoteIds)
        assertEquals(note, targetDb.noteDao().getById(note.id)?.copy(rowid = 0))
        assertEquals(image, targetDb.attachmentDao().getById(image.id))
        assertArrayEquals(File(sourceContext.filesDir, image.uri).readBytes(), File(targetContext.filesDir, image.uri).readBytes())
        val tag = requireNotNull(targetDb.tagDao().getByName("research"))
        assertTrue(tag.isPinned); assertEquals("book", tag.iconKey); assertEquals(7, tag.sortOrder)
        val again = incoming()
        target.authenticateRestore(again, password)
        assertEquals(1, target.restorePrepared(again).skipped)
        assertEquals(note, sourceDb.noteDao().getById(note.id)?.copy(rowid = 0))
    }

    /** A failed key unwrap leaves only encrypted input; the same input can be retried safely. */
    @Test fun wrongPassphraseCannotAuthorizeRestoreAndDoesNotRetainPlaintext() = runBlocking {
        val document = backup()
        source.writePrepared(document.id, uri("output"))
        val input = incoming()
        val wrong = "wrong synthetic password".toCharArray()
        var rejected = false
        try { target.authenticateRestore(input, wrong) } catch (_: Exception) { rejected = true }
        assertTrue(rejected); assertTrue(wrong.all { it == '\u0000' })
        cannotRestore(input)
        assertEquals(setOf("input"), TransferFiles(targetContext).find(input).directory.list().orEmpty().toSet())
        target.authenticateRestore(input, password)
        assertEquals(1, target.restorePrepared(input).addedNoteIds.size)
    }

    /** Authenticated ciphertext is not permission to parse or commit an invalid inner archive. */
    @Test fun authenticatedMalformedArchiveLeavesExistingNotesUnchanged() = runBlocking {
        val plain = File(root, "not-a-zip").apply { writeText("Synthetic authenticated invalid archive", Charsets.UTF_8) }
        val encrypted = File(root, "invalid.duckbackup")
        BackupEncryption.encrypt(plain, encrypted, password)
        app.contentResolver.openOutputStream(Uri.parse(uri("output")), "wt").use { output -> encrypted.inputStream().use { it.copyTo(requireNotNull(output)) } }
        val input = incoming()
        var rejected = false
        try { target.authenticateRestore(input, password) } catch (_: IOException) { rejected = true }
        assertTrue(rejected)
        cannotRestore(input)
        assertEquals(setOf("input"), TransferFiles(targetContext).find(input).directory.list().orEmpty().toSet())
        assertEquals(note, sourceDb.noteDao().getById(note.id)?.copy(rowid = 0))
        assertTrue(File(sourceContext.filesDir, image.uri).isFile)
    }

    /** Plant: ignore a reliable-provider error, revoked grant, or non-content URI. */
    @Test fun failedProvidersAndUnsafeUrisDoNotLeaveStagesOrTouchNotes() = runBlocking {
        val directory = File(targetContext.noBackupFilesDir, "transfers")
        fun staged() = directory.list().orEmpty().toSet()
        val before = staged()
        for (path in listOf("fail", "interrupted")) {
            try { target.prepareRestore(uri(path)); fail("Provider failure accepted") } catch (_: IOException) { }
            assertEquals(before, staged())
        }
        try { target.prepareRestore("file://${File(root, "private")}"); fail("Private file URI accepted") } catch (_: IOException) { }
        control("revoke")
        try { target.prepareRestore(uri("source")); fail("Revoked grant accepted") } catch (_: IOException) { } catch (_: SecurityException) { }
        assertEquals(before, staged()); assertEquals(0, targetDb.noteDao().count())
        assertEquals(note, sourceDb.noteDao().getById(note.id)?.copy(rowid = 0))
    }
}
