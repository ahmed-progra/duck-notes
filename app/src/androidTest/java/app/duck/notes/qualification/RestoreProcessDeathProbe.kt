package app.duck.notes.qualification

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.data.repository.AttachmentStorageImpl
import app.duck.notes.data.transfer.ArchiveImage
import app.duck.notes.data.transfer.ArchiveManifest
import app.duck.notes.data.transfer.ArchiveManifestCodec
import app.duck.notes.data.transfer.ArchiveNote
import app.duck.notes.data.transfer.NoteArchiveStore
import app.duck.notes.data.transfer.StagedArchive
import app.duck.notes.data.transfer.TransferFiles
import app.duck.notes.di.DatabaseModule
import app.duck.notes.domain.markdown.MarkdownScanner
import app.duck.notes.domain.usecase.DeriveTitle
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in external kill probe; ordinary suites exclude this class. No production crash hooks. */
class RestoreProcessDeathProbe {
    /** Plants: omit the deletion marker, or omit startup abandoned-stage cleanup. */
    @Test fun verifyAfterActualColdStartup() {
        val phase = InstrumentationRegistry.getArguments().getString("crash_phase")
        assumeTrue("Requires a preceding external process kill and real cold start", phase != null)
        require(phase == "before" || phase == "after")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val record = JSONObject(File(requireNotNull(context.getExternalFilesDir("qualification")), "crash-$phase.json").readText())
        val image = requireNotNull(AttachmentFiles(context.filesDir).target(record.getString("imageId")))
        assertEquals(record.getString("image"), image.absolutePath)
        val stage = File(record.getString("stage"))
        assertEquals(File(context.noBackupFilesDir, "transfers").canonicalFile, requireNotNull(stage.parentFile).canonicalFile)
        assertEquals(UUID.fromString(stage.name).toString(), stage.name)
        assertFalse("Startup retained an abandoned private restore stage", stage.exists())
        if (phase == "before") {
            assertFalse("Startup retained an uncommitted published image", image.exists())
            assertFalse(requireNotNull(AttachmentFiles(context.filesDir).deletionMarker(image.name, false)).exists())
        } else {
            assertTrue("Startup deleted a committed restored image", image.isFile)
            assertEquals(record.getString("imageSha256"), MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString("") { "%02x".format(it) })
        }
    }

    @Test fun holdRealRestoreForExternalProcessKill() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("crash_phase")
        assumeTrue("Requires an external process-death controller", phase != null)
        require(phase == "before" || phase == "after")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(context.applicationInfo.uid, Process.myUid())
        assertTrue(context.getDatabasePath("duck.db").isFile)
        val db = DatabaseModule.provideDatabase(context)
        val files = TransferFiles(context)
        val job = files.create()
        assertTrue(File(job.directory, "notes").mkdir())
        assertTrue(File(job.directory, "assets").mkdir())
        val noteId = UUID.randomUUID().toString()
        val imageId = UUID.randomUUID().toString()
        val source = File(job.directory, "assets/$imageId.png")
        source.outputStream().use { output ->
            val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.RED)
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            finally { bitmap.recycle() }
        }
        val body = "# Process death $phase\n#qualification/recovery\n${MarkdownScanner.imageRef(imageId)}"
        File(job.directory, "notes/$noteId.md").writeText(body, Charsets.UTF_8)
        val manifest = ArchiveManifest("backup",
            listOf(ArchiveNote(noteId, 123, 456, true, false, false, null)),
            listOf(ArchiveImage(imageId, noteId, "image/png", 2, 3, source.length(), 124)), emptyList())
        ArchiveManifestCodec.write(File(job.directory, "manifest.json"), manifest)
        val storage = AttachmentStorageImpl(context)
        val store = NoteArchiveStore(context, db, storage, DeriveTitle(), ParseTags(), ResolveWikiLinks())
        val writerEntered = CompletableDeferred<Unit>()
        val releaseWriter = CompletableDeferred<Unit>()
        if (phase == "before") {
            async(Dispatchers.IO) {
                db.useWriterConnection { connection -> connection.immediateTransaction {
                    writerEntered.complete(Unit)
                    releaseWriter.await()
                } }
            }
            withTimeout(10_000) { writerEntered.await() }
        }
        val restore = async(Dispatchers.IO) { store.restore(StagedArchive(manifest, job.directory)) }
        val image = requireNotNull(AttachmentFiles(context.filesDir).target(imageId))
        withTimeout(10_000) { while (!image.isFile) delay(10) }
        assertArrayEquals(source.readBytes(), image.readBytes())
        assertTrue(AttachmentFiles.key(imageId) in storage.pendingDeletions())
        if (phase == "before") {
            assertFalse(restore.isCompleted)
            assertNull(db.noteDao().getById(noteId))
            assertNull(db.attachmentDao().getById(imageId))
        } else {
            assertEquals(listOf(noteId), withTimeout(10_000) { restore.await() }.addedNoteIds)
            assertEquals(body, db.noteDao().getById(noteId)?.body)
            assertEquals(noteId, db.attachmentDao().getById(imageId)?.noteId)
        }
        val report = JSONObject().put("phase", phase).put("pid", Process.myPid())
            .put("noteId", noteId).put("imageId", imageId).put("body", body)
            .put("stage", job.directory.absolutePath).put("image", image.absolutePath)
            .put("imageSha256", MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString("") { "%02x".format(it) })
        val output = File(requireNotNull(context.getExternalFilesDir("qualification")), "crash-$phase.json")
        output.writeText(report.toString(), Charsets.UTF_8)
        instrumentation.sendStatus(0, Bundle().apply { putString("crash_ready", output.absolutePath) })
        // The controller verifies this durable checkpoint, kills this PID, then cold-starts Duck.
        awaitCancellation()
    }
}
