package app.duck.notes.data.repository

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.DuckApp
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.domain.Clock
import app.duck.notes.domain.markdown.MarkdownFormat
import app.duck.notes.domain.model.AppSettings
import app.duck.notes.domain.model.Attachment
import app.duck.notes.domain.model.SettingsReadStatus
import app.duck.notes.domain.model.ThemePreference
import app.duck.notes.domain.usecase.ArchiveNote
import app.duck.notes.domain.usecase.DeleteAttachment
import app.duck.notes.domain.usecase.DeleteNote
import app.duck.notes.domain.usecase.ParseTags
import app.duck.notes.domain.usecase.ResolveWikiLinks
import app.duck.notes.startup.WelcomeStartupEntryPoint
import app.duck.notes.startup.WelcomeStartupState
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real app-data fixtures for external LocalTransport bmgr runs. This test never invokes a transport.
 * Seed needs pm clear first; verify needs restore into freshly cleared data. verify_oversize follows
 * a quota rejection without clearing local data. Footers alone do not prove backup/restore/quota.
 */
@RunWith(AndroidJUnit4::class)
class BackupTransportTest {
    private val app get() = ApplicationProvider.getApplicationContext<DuckApp>()
    private val graph get() = EntryPointAccessors.fromApplication(app, WelcomeStartupEntryPoint::class.java)
    private val database get() = graph.database()
    private val storage get() = AttachmentStorageImpl(app)

    /** Plants BACKUP_DROP_DATABASE, BACKUP_DROP_IMAGES, BACKUP_DROP_SETTINGS in packaged backup XML. */
    @Test fun realApplicationTransportFixture() = runBlocking<Unit> {
        assertEquals(DuckApp::class.java, app.javaClass)
        assertEquals(WelcomeStartupState.Ready, withTimeout(10_000) {
            graph.startup().state.first { it != WelcomeStartupState.Loading }
        })
        val scenario = requireNotNull(InstrumentationRegistry.getArguments().getString("backupScenario"))
        when (scenario) {
            "seed" -> seed()
            "verify" -> {
                verify(oversize = false)
                assertFalse("pending import must be excluded by the actual transport", File(app.filesDir,
                    "attachments/.pending/transport-excluded.part").exists())
            }
            "oversize" -> {
                verify(oversize = false)
                importImage(LARGE_A, 14L * 1024 * 1024)
                importImage(LARGE_B, 14L * 1024 * 1024)
                verify(oversize = true)
            }
            "verify_oversize" -> verify(oversize = true)
            "cleanup" -> {
                verify(oversize = true)
                val remove = DeleteAttachment(storage, AttachmentRepositoryImpl(database.attachmentDao()),
                    NoteRepositoryImpl(database.noteDao()), graph.saveNote())
                remove(LARGE_B, SOURCE)
                remove(LARGE_A, SOURCE)
                graph.saveNote()(SOURCE, expectedBody(false), timestampMillis = STAMP)
                verify(oversize = false)
            }
            else -> error("Unknown backupScenario: $scenario")
        }
        println("BACKUP_TRANSPORT_FIXTURE: $scenario; notes=8; images=${if (scenario in setOf("oversize", "verify_oversize")) 4 else 2}")
    }

    private suspend fun seed() {
        assertEquals("seed requires a freshly cleared installation with four welcome notes", 4, database.noteDao().count())
        listOf(SOURCE, TARGET, ARCHIVE, TRASH).forEach { assertNull(database.noteDao().getById(it)) }
        graph.saveNote()(TARGET, "Transport pond", timestampMillis = STAMP)
        graph.saveNote()(SOURCE, BODY, timestampMillis = STAMP)
        graph.saveNote()(ARCHIVE, "Archived transport entry", timestampMillis = STAMP)
        graph.saveNote()(TRASH, "Trashed transport entry", timestampMillis = STAMP)
        val notes = NoteRepositoryImpl(database.noteDao())
        ArchiveNote(notes, ParseTags(), ResolveWikiLinks(), Clock { 234_567L })(ARCHIVE, true)
        DeleteNote(notes, ParseTags(), ResolveWikiLinks(), Clock { 345_678L })(TRASH)
        importImage(PORTABLE)
        importImage(LEGACY)
        graph.settings().apply {
            setTheme(ThemePreference.Dark)
            setLockEnabled(true)
            setLockTimeoutMs(300_000L)
            setHideRecents(false)
        }
        withTimeout(5_000) { graph.settings().state.first { it.settings == AppSettings(ThemePreference.Dark, true, 300_000L, false) } }
        File(app.filesDir, "attachments/.pending/transport-excluded.part").apply {
            parentFile?.mkdirs()
            writeText("excluded pending import")
            assertTrue("exclusion control must physically exist before backup", isFile)
        }
        verify(oversize = false)
    }

    private suspend fun importImage(id: String, bytes: Long? = null) {
        val source = File.createTempFile("transport-", ".png", app.cacheDir)
        try {
            source.outputStream().use { output ->
                val image = png()
                output.write(image)
                var remaining = (bytes ?: image.size.toLong()) - image.size
                val padding = ByteArray(8192) { 0x5a }
                while (remaining > 0) {
                    val count = minOf(remaining, padding.size.toLong()).toInt()
                    output.write(padding, 0, count)
                    remaining -= count
                }
            }
            val key = requireNotNull(storage.store(Uri.fromFile(source).toString(), id)) { "Real import failed: $id" }
            assertEquals("attachments/$id", key)
            val location = if (id == LEGACY) requireNotNull(AttachmentFiles(app.filesDir).resolve(key)).absolutePath else key
            val body = requireNotNull(database.noteDao().getById(SOURCE)).body
            graph.saveNote()(SOURCE, MarkdownFormat.appendImage(body, id), timestampMillis = STAMP,
                attachment = Attachment(id, SOURCE, location, "image/png", size = source.length(), createdAt = STAMP))
        } finally { source.delete() }
    }

    private suspend fun verify(oversize: Boolean) {
        val dao = database.noteDao()
        assertEquals("restored welcome marker must not duplicate the tour", 8, dao.count())
        val source = requireNotNull(dao.getById(SOURCE))
        assertEquals(expectedBody(oversize), source.body)
        assertEquals("Transport journal", source.title)
        assertEquals(STAMP, source.createdAt)
        assertEquals(STAMP, source.modifiedAt)
        assertFalse(source.isArchived)
        assertFalse(source.isTrashed)
        assertEquals("Transport pond", dao.getById(TARGET)?.body)
        val archived = requireNotNull(dao.getById(ARCHIVE))
        assertEquals("Archived transport entry", archived.body)
        assertTrue(archived.isArchived)
        assertFalse(archived.isTrashed)
        assertEquals(234_567L, archived.modifiedAt)
        val trashed = requireNotNull(dao.getById(TRASH))
        assertEquals("Trashed transport entry", trashed.body)
        assertTrue(trashed.isTrashed)
        assertEquals(345_678L, trashed.trashedAt)
        assertEquals(setOf("transport/work", "transport/ideas"), database.tagDao().observeNoteRefs().first()
            .filter { it.noteId == SOURCE }.map { it.name }.toSet())
        assertEquals(listOf(SOURCE), database.noteLinkDao().observeBacklinks(TARGET).first().map { it.sourceId })
        assertEquals(listOf(SOURCE), dao.search("transportuniquesentinel").map { it.id })
        val ids = if (oversize) listOf(PORTABLE, LEGACY, LARGE_A, LARGE_B) else listOf(PORTABLE, LEGACY)
        val rows = database.attachmentDao().forNote(SOURCE)
        assertEquals(ids.toSet(), rows.map { it.id }.toSet())
        for (row in rows) {
            assertEquals(SOURCE, row.noteId)
            assertEquals("image/png", row.mime)
            assertEquals(STAMP, row.createdAt)
            assertEquals(if (row.id == LEGACY) File(app.filesDir, "attachments/${row.id}").canonicalPath else "attachments/${row.id}", row.uri)
            assertTrue(storage.exists(row.uri))
            val file = requireNotNull(AttachmentFiles(app.filesDir).resolve(row.uri))
            val length = if (row.id in listOf(LARGE_A, LARGE_B)) 14L * 1024 * 1024 else png().size.toLong()
            assertEquals(length, file.length())
            assertEquals(length, row.size)
            assertArrayEquals(expectedDigest(length), file.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest()
            })
        }
        val total = rows.sumOf { requireNotNull(AttachmentFiles(app.filesDir).resolve(it.uri)).length() }
        if (oversize) assertTrue("included image payload alone exceeds the 25MiB quota", total > 25L * 1024 * 1024)
        else assertTrue("normal fixture must remain comfortably below quota", total < 1024 * 1024)
        val settings = withTimeout(5_000) { graph.settings().state.first { it.readStatus == SettingsReadStatus.Ready } }
        assertEquals(AppSettings(ThemePreference.Dark, true, 300_000L, false), settings.settings)
        assertEquals(mapOf("installation_origin_v1" to "fresh", "welcome_attempted_v1" to true),
            graph.store().data.first().asMap().entries.associate { it.key.name to it.value })
        graph.seeder()(false)
        assertEquals(8, dao.count())
    }

    private fun expectedBody(oversize: Boolean): String = BODY +
        "\n![](attachment:$PORTABLE)\n![](attachment:$LEGACY)" +
        if (oversize) "\n![](attachment:$LARGE_A)\n![](attachment:$LARGE_B)" else ""

    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(0xff267acc.toInt()); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        finally { bitmap.recycle() }
    }

    private fun expectedDigest(length: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val image = png()
        digest.update(image)
        var remaining = length - image.size
        val padding = ByteArray(8192) { 0x5a }
        while (remaining > 0) {
            val count = minOf(remaining, padding.size.toLong()).toInt()
            digest.update(padding, 0, count)
            remaining -= count
        }
        return digest.digest()
    }

    private companion object {
        const val STAMP = 123_456L
        const val SOURCE = "transport-source"
        const val TARGET = "transport-target"
        const val ARCHIVE = "transport-archive"
        const val TRASH = "transport-trash"
        const val BODY = "Transport journal\r\ntransportuniquesentinel مرحباً\n#transport/work #transport/ideas\n[[Transport pond]]"
        const val PORTABLE = "00000000-0000-0000-0000-000000000010"
        const val LEGACY = "00000000-0000-0000-0000-000000000011"
        const val LARGE_A = "00000000-0000-0000-0000-000000000020"
        const val LARGE_B = "00000000-0000-0000-0000-000000000021"
    }
}
