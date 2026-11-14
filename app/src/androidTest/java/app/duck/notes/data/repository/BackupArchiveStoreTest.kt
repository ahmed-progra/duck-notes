package app.duck.notes.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.room.immediateTransaction
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.local.IsolatedAppDataContext
import app.duck.notes.di.DatabaseModule
import app.duck.notes.data.local.entity.AttachmentEntity
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.local.entity.TagEntity
import app.duck.notes.data.transfer.*
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import app.duck.notes.domain.usecase.RecoverAttachmentDeletions
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelAndJoin
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupArchiveStoreTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var db: DuckDatabase
    private lateinit var store: NoteArchiveStore
    private lateinit var note: NoteEntity
    private lateinit var image: AttachmentEntity
    private fun id() = UUID.randomUUID().toString()
    @Before fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = File(app.noBackupFilesDir, "restore-${id()}").apply { check(mkdir()) }
        context = object : ContextWrapper(app) { override fun getFilesDir() = File(root, "files").apply { mkdirs() } }
        db = Room.inMemoryDatabaseBuilder(context, DuckDatabase::class.java).setDriver(BundledSQLiteDriver()).addCallback(DuckDatabaseCallback()).build()
        store = NoteArchiveStore(context, db, AttachmentStorageImpl(context), DeriveTitle(), ParseTags(), ResolveWikiLinks())
        val nid = id()
        val iid = id()
        check(File(context.filesDir, "attachments").mkdir())
        val file = requireNotNull(AttachmentFiles(context.filesDir).target(iid))
        file.outputStream().use { out -> Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).let { bitmap -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle() } }
        note = NoteEntity(id = nid, title = "Paper", body = "# Paper\n#research/topic\n[[Paper]]\n${MarkdownScanner.imageRef(iid)}", createdAt = 123, modifiedAt = 456, isPinned = true, isArchived = true, isTrashed = true, trashedAt = 789)
        image = AttachmentEntity(iid, nid, AttachmentFiles.key(iid), "image/png", 2, 3, file.length(), 124)
        db.noteDao().saveNoteWithAttachment(note, listOf("research/topic"), listOf("Paper"), image)
        val tag = requireNotNull(db.tagDao().getByName("research/topic"))
        db.tagDao().upsert(tag.copy(isPinned = true, iconKey = "book", sortOrder = 4))
    }
    @After fun close() { db.close(); root.deleteRecursively() }
    private fun stage() = File(root, id()).apply { check(mkdir()) }
    private suspend fun backup() = store.snapshotBackup(stage())
    private suspend fun removeSource() {
        db.useWriterConnection { it.usePrepared("DELETE FROM notes WHERE id = ?") { statement -> statement.bindText(1, note.id); statement.step() } }
        File(context.filesDir, image.uri).delete()
    }

    /** Plants: rewrite identities, reset metadata, skip image bytes, or fail to rebuild derived rows. */
    @Test fun missingNotesKeepIdentitiesMetadataImagesAndSearchOnRestore() = runBlocking {
        val originalBytes = File(context.filesDir, image.uri).readBytes()
        val snapshot = backup()
        assertEquals("backup", snapshot.manifest.kind)
        assertEquals(note.body, File(snapshot.directory, snapshot.manifest.notes.single().entry).readText())
        assertEquals(listOf(ArchiveTag("research/topic", true, "book", 4)), snapshot.manifest.tags)
        removeSource()
        db.tagDao().deleteByName("research/topic")
        val report = store.restore(snapshot)
        assertEquals(listOf(note.id), report.addedNoteIds)
        assertEquals(0, report.skipped)
        assertEquals(note, db.noteDao().getById(note.id)?.copy(rowid = 0))
        assertEquals(image, db.attachmentDao().getById(image.id))
        assertArrayEquals(originalBytes, File(context.filesDir, image.uri).readBytes())
        assertEquals(listOf("research/topic"), db.tagDao().observeAll().first().map { it.name })
        val restoredTag = requireNotNull(db.tagDao().getByName("research/topic"))
        assertTrue(restoredTag.isPinned)
        assertEquals("book", restoredTag.iconKey)
        assertEquals(4, restoredTag.sortOrder)
        val derivedLinks = db.useWriterConnection { connection -> connection.usePrepared("SELECT COUNT(*) FROM note_links WHERE source_id = ?") { statement ->
            statement.bindText(1, note.id); check(statement.step()); statement.getLong(0)
        } }
        assertEquals(1L, derivedLinks)
        val second = store.restore(snapshot)
        assertEquals(1, second.skipped)
        assertTrue(second.addedNoteIds.isEmpty())
    }

    /** Existing content and customization win, including order-only orphan customization. */
    @Test fun existingNotesAndTagCustomizationAreNeverOverwritten() = runBlocking {
        val snapshot = backup()
        val changed = note.copy(body = "# Changed\n#research/topic", title = "Changed", modifiedAt = 900, isPinned = false)
        db.noteDao().saveNoteWithTagsAndLinks(changed, listOf("research/topic"))
        val tag = requireNotNull(db.tagDao().getByName("research/topic")).copy(isPinned = false, iconKey = "mine", sortOrder = 7)
        db.tagDao().upsert(tag)
        val report = store.restore(snapshot)
        assertEquals(1, report.skipped)
        assertEquals(changed, db.noteDao().getById(note.id)?.copy(rowid = 0))
        assertEquals(tag, db.tagDao().getByName("research/topic"))
        val ordered = TagEntity(id(), "ordered", sortOrder = 3)
        db.tagDao().upsert(ordered)
        db.noteDao().saveNoteWithTagsAndLinks(NoteEntity(id = id(), title = "Other", body = "Other", createdAt = 1, modifiedAt = 1), emptyList())
        assertEquals(ordered, db.tagDao().getByName("ordered"))
    }

    /** Reusing a live attachment identity under another note must reject the entire batch. */
    @Test fun conflictingAttachmentOwnerLeavesEveryExistingRecordAndFileUnchanged() = runBlocking {
        val snapshot = backup()
        val other = snapshot.manifest.notes.single().copy(id = id())
        File(snapshot.directory, snapshot.manifest.notes.single().entry).copyTo(File(snapshot.directory, other.entry))
        val conflict = snapshot.copy(manifest = snapshot.manifest.copy(notes = listOf(other), images = snapshot.manifest.images.map { it.copy(noteId = other.id) }))
        val before = File(context.filesDir, image.uri).readBytes()
        try { store.restore(conflict); fail("Conflicting owner accepted") } catch (_: IOException) { }
        assertEquals(1, db.noteDao().count())
        assertEquals(note, db.noteDao().getById(note.id)?.copy(rowid = 0))
        assertArrayEquals(before, File(context.filesDir, image.uri).readBytes())
    }

    /** Plant: exempt matching owner/shape/length from the actual attachment byte-identity check. */
    @Test fun sameOwnerAndImageMetadataCannotVouchForDifferentBytes() = runBlocking {
        val file = File(context.filesDir, image.uri)
        file.appendBytes(byteArrayOf(0x41))
        val snapshot = backup()
        val original = file.readBytes()
        val changed = original.copyOf().apply { this[lastIndex] = 0x42 }
        file.writeBytes(changed)
        val actual = requireNotNull(app.duck.notes.core.files.attachmentImageInfo(file))
        val saved = snapshot.manifest.images.single()
        assertEquals(saved.bytes, actual.size)
        assertEquals(saved.width, actual.width)
        assertEquals(saved.height, actual.height)
        assertEquals(saved.mime, actual.mime)
        try { store.restore(snapshot); fail("Conflicting attachment bytes accepted") } catch (_: IOException) { }
        assertArrayEquals(changed, file.readBytes())
        assertEquals(note, db.noteDao().getById(note.id)?.copy(rowid = 0))
    }

    /** Plant: publish database rows individually, or retain new files after a rolled-back commit. */
    @Test fun realSqliteFailureRollsBackRestoredNotesAndNewImages() = runBlocking {
        val snapshot = backup()
        removeSource()
        db.useWriterConnection { it.usePrepared("CREATE TRIGGER reject_restore BEFORE INSERT ON attachments BEGIN SELECT RAISE(ABORT, 'restore rejection'); END") { s -> s.step() } }
        var rejected = false
        try { store.restore(snapshot) } catch (e: androidx.sqlite.SQLiteException) { rejected = e.message.orEmpty().contains("restore rejection") }
        assertTrue("Actual SQLite trigger did not reject restore", rejected)
        assertEquals(0, db.noteDao().count())
        assertFalse(File(context.filesDir, image.uri).exists())
    }

    @Test fun emptyLibraryHasAValidEmptyBackup() = runBlocking {
        removeSource()
        db.tagDao().deleteByName("research/topic")
        val snapshot = backup()
        assertTrue(snapshot.manifest.notes.isEmpty())
        assertTrue(snapshot.manifest.images.isEmpty())
        assertTrue(store.restore(snapshot).addedNoteIds.isEmpty())
    }

    /** Plant: remove the real recovery mutex; recovery then unlinks a published, not-yet-committed image. */
    @Test fun startupRecoveryCannotRacePublicationWhileTheRealDatabaseWriterIsBusy() = runBlocking {
        val snapshot = backup()
        val base = ApplicationProvider.getApplicationContext<Context>()
        val targetContext = IsolatedAppDataContext(base, File(root, "race-target"))
        val targetDb = DatabaseModule.provideDatabase(targetContext)
        val storage = AttachmentStorageImpl(targetContext)
        val target = NoteArchiveStore(targetContext, targetDb, storage, DeriveTitle(), ParseTags(), ResolveWikiLinks())
        val recover = RecoverAttachmentDeletions(AttachmentRepositoryImpl(targetDb.attachmentDao()), storage)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = async(Dispatchers.IO) {
            targetDb.useWriterConnection { connection -> connection.immediateTransaction { entered.complete(Unit); release.await() } }
        }
        var restoring: kotlinx.coroutines.Deferred<*>? = null
        var recovering: kotlinx.coroutines.Deferred<*>? = null
        try {
            withTimeout(10_000) { entered.await() }
            val restore = async(Dispatchers.IO) { target.restore(snapshot) }
            restoring = restore
            val file = File(targetContext.filesDir, image.uri)
            withTimeout(10_000) { while (!file.isFile) delay(10) }
            assertTrue(image.uri in storage.pendingDeletions())
            val recovery = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) { recover() }
            recovering = recovery
            assertNull("Recovery crossed the restore publication lock", withTimeoutOrNull(1_000) { recovery.await(); true })
            assertTrue("Published image disappeared before its transaction", file.isFile)
            release.complete(Unit)
            withTimeout(10_000) { writer.await(); restore.await(); recovery.await() }
            assertEquals(note, targetDb.noteDao().getById(note.id)?.copy(rowid = 0))
            assertArrayEquals(File(context.filesDir, image.uri).readBytes(), file.readBytes())
            val orphan = id()
            val orphanKey = AttachmentFiles.key(orphan)
            val orphanFile = requireNotNull(AttachmentFiles(targetContext.filesDir).target(orphan)).apply { writeText("Synthetic unreferenced bytes", Charsets.UTF_8) }
            storage.markForDeletion(orphanKey)
            recover()
            assertFalse("Positive control: unreferenced marked image was not removed", orphanFile.exists())
            assertTrue(file.isFile)
        } finally {
            release.complete(Unit)
            writer.cancelAndJoin(); restoring?.cancelAndJoin(); recovering?.cancelAndJoin()
            targetDb.close()
        }
    }
}
