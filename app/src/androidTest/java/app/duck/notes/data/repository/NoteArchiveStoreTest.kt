package app.duck.notes.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.data.local.DuckDatabase
import app.duck.notes.data.local.DuckDatabaseCallback
import app.duck.notes.data.local.entity.AttachmentEntity
import app.duck.notes.data.local.entity.NoteEntity
import app.duck.notes.data.local.entity.TagEntity
import app.duck.notes.data.transfer.*
import app.duck.notes.data.transfer.ArchiveNote
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.usecase.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteArchiveStoreTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var db: DuckDatabase
    private lateinit var store: NoteArchiveStore
    private lateinit var source: NoteEntity
    private lateinit var image: AttachmentEntity
    private fun id() = UUID.randomUUID().toString()
    @Before fun setUp() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.noBackupFilesDir, "store-test-${id()}").apply { check(mkdir()) }
        context = object : ContextWrapper(base) { override fun getFilesDir() = File(root, "files").apply { mkdirs() } }
        db = Room.inMemoryDatabaseBuilder(base, DuckDatabase::class.java).setDriver(BundledSQLiteDriver()).addCallback(DuckDatabaseCallback()).build()
        store = NoteArchiveStore(context, db, AttachmentStorageImpl(context), DeriveTitle(), ParseTags(), ResolveWikiLinks())
        val noteId = id()
        val imageId = id()
        val file = File(context.filesDir, "attachments/$imageId").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        source = NoteEntity(id = noteId, title = "Paper", body = "# Paper\n#research/topic\n[[Paper]]\n" + MarkdownScanner.imageRef(imageId) + "\n`" + MarkdownScanner.imageRef(imageId) + "`", createdAt = 1, modifiedAt = 2, isPinned = true, isArchived = true)
        image = AttachmentEntity(imageId, noteId, AttachmentFiles.key(imageId), "image/png", 2, 3, file.length(), 1)
        db.noteDao().saveNoteWithAttachment(source, listOf("research/topic"), listOf("Paper"), image)
        val tag = requireNotNull(db.tagDao().getByName("research/topic"))
        db.tagDao().upsert(tag.copy(isPinned = true, iconKey = "book", sortOrder = 4))
    }
    @After fun close() { db.close(); root.deleteRecursively() }
    private suspend fun snapshot() = store.snapshot(listOf(source.id), File(root, id()).apply { check(mkdir()) })
    private suspend fun portable(): StagedArchive {
        val archive = File(root, "${id()}.zip")
        MarkdownArchive.write(snapshot(), archive)
        return MarkdownArchive.read(archive, File(root, id()).apply { check(mkdir()) })
    }

    /** Plant: reuse source identities, fail to rewrite parsed references, or overwrite source metadata. */
    @Test fun realRoomAndActualImageRoundTripCreateNewIdentitiesWithoutOverwriting() = runBlocking {
        val prepared = portable()
        assertTrue(File(prepared.directory, "notes/${source.id}.md").readText().contains("assets/${image.id}.png"))
        val report = store.import(prepared)
        assertEquals(1, report.addedNoteIds.size)
        assertEquals(1, report.duplicateTitles)
        val addedId = report.addedNoteIds.single()
        assertNotEquals(source.id, addedId)
        val added = requireNotNull(db.noteDao().getById(addedId))
        assertFalse(added.isPinned || added.isArchived || added.isTrashed)
        assertEquals(source, db.noteDao().getById(source.id)?.copy(rowid = 0))
        val copy = db.attachmentDao().forNote(addedId).single()
        assertNotEquals(image.id, copy.id)
        assertTrue(added.body.contains(MarkdownScanner.imageRef(copy.id)))
        assertTrue(added.body.contains("`" + MarkdownScanner.imageRef(image.id) + "`"))
        assertArrayEquals(File(context.filesDir, image.uri).readBytes(), File(context.filesDir, copy.uri).readBytes())
        assertEquals(listOf("research/topic"), db.tagDao().observeAll().first().map { it.name })
        assertEquals(2, db.noteDao().search("Paper").size)
        assertEquals(2, db.noteLinkDao().observeBacklinks(addedId).first().size)
        assertEquals("book", db.tagDao().getByName("research/topic")?.iconKey)
    }

    /** Plants: skip an unreadable file or accept a reference owned by a different note. */
    @Test fun missingOrForeignSourceImagesFailRatherThanOmittingOrLeakingBytes() = runBlocking {
        val foreign = source.copy(id = id(), body = MarkdownScanner.imageRef(image.id))
        db.noteDao().saveNoteWithTagsAndLinks(foreign, emptyList())
        try { store.snapshot(listOf(foreign.id), File(root, id()).apply { mkdir() }); fail("Foreign image accepted") } catch (_: IOException) { }
        File(context.filesDir, image.uri).delete()
        try { snapshot(); fail("Missing image silently omitted") } catch (_: IOException) { }
        assertEquals(source.body, db.noteDao().getById(source.id)?.body)
    }

    /** Plant: trust a row's owner while its URI points at a different attachment identity. */
    @Test fun mismatchedStoredImageLocationCannotExportAnotherImagesBytes() = runBlocking {
        val otherId = id()
        val otherFile = File(context.filesDir, "attachments/$otherId")
        File(context.filesDir, image.uri).copyTo(otherFile)
        db.attachmentDao().insert(image.copy(uri = AttachmentFiles.key(otherId)))
        try { snapshot(); fail("Mismatched physical identity accepted") } catch (_: IOException) { }
        assertTrue(otherFile.isFile)
    }

    /** Plant: commit notes individually instead of using one transaction. */
    @Test fun actualDatabaseFailureRollsBackTheEntireImportAndItsNewFiles() = runBlocking {
        val prepared = portable()
        val second = ArchiveNote(id(), 1, 2, false, false, false, null)
        File(prepared.directory, second.entry).writeText("# Reject", Charsets.UTF_8)
        val batch = prepared.copy(manifest = prepared.manifest.copy(notes = prepared.manifest.notes + second))
        db.useWriterConnection { connection -> connection.usePrepared("CREATE TRIGGER reject_import BEFORE INSERT ON notes WHEN new.title = 'Reject' BEGIN SELECT RAISE(ABORT, 'test import failure'); END") { it.step() } }
        val failure = runCatching { store.import(batch) }.exceptionOrNull()
        assertTrue("The real SQLite trigger must abort this transaction", failure is androidx.sqlite.SQLiteException)
        assertTrue(failure?.message.orEmpty().contains("test import failure"))
        assertEquals(1, db.noteDao().count())
        assertEquals(setOf(image.id), File(context.filesDir, "attachments").listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet())
        assertEquals(source.body, db.noteDao().getById(source.id)?.body)
        assertTrue(File(context.filesDir, image.uri).isFile)
    }

    /** Plants: permit a 257-character tag or 33 tag levels; their immediate lower bounds stay valid. */
    @Test fun importedTagBoundsRejectOversizedNamesAndDepthWithoutChangingExistingNotes() = runBlocking {
        suspend fun importing(tag: String) {
            val directory = File(root, id()).apply { mkdir() }
            File(directory, "notes").mkdir(); File(directory, "assets").mkdir()
            val note = ArchiveNote(id(), 1, 2, false, false, false, null)
            File(directory, note.entry).writeText("# Tag boundary\n#$tag", Charsets.UTF_8)
            store.import(StagedArchive(ArchiveManifest("markdown", listOf(note), emptyList(), emptyList()), directory))
        }
        try { importing("t".repeat(257)); fail("Oversized tag accepted") } catch (_: IOException) { }
        try { importing(List(33) { "t" }.joinToString("/")); fail("Oversized tag depth accepted") } catch (_: IOException) { }
        assertEquals(1, db.noteDao().count())
        importing("t".repeat(256))
        importing(List(32) { "t" }.joinToString("/"))
        assertEquals(3, db.noteDao().count())
        assertEquals(source.body, db.noteDao().getById(source.id)?.body)
    }
}
