package app.duck.notes.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.transfer.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupArchiveValidationTest {
    private lateinit var directory: File
    private val id = UUID.randomUUID().toString()
    @Before fun setUp() {
        directory = File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "backup-validation-${UUID.randomUUID()}").apply { check(mkdir()) }
        check(File(directory, "notes").mkdir()); check(File(directory, "assets").mkdir())
    }
    @After fun close() { directory.deleteRecursively() }
    private suspend fun validate(body: String) {
        val manifest = ArchiveManifest("backup", listOf(ArchiveNote(id, 1, 2, false, false, false, null)), emptyList(), emptyList())
        writeNoteText(File(directory, manifest.notes.single().entry), body)
        ArchiveManifestCodec.write(File(directory, "manifest.json"), manifest)
        MarkdownArchive.validate(StagedArchive(manifest, directory))
    }
    /** Plant: allow a 257-character tag or 33 levels in the shared archive validator. */
    @Test fun backupValidationUsesTheSameRestorableTagBoundsAsImport() = runBlocking {
        for (tag in listOf("t".repeat(257), List(33) { "t" }.joinToString("/"))) {
            try { validate("# Tag bounds\n#$tag"); fail("Unrestorable tag accepted") } catch (_: IOException) { }
        }
        validate("# Tag bounds\n#${"t".repeat(256)}\n#${List(32) { "t" }.joinToString("/")}")
    }
    /** Plant: permit more than 10,000 distinct derived tags; 10,000 remains a positive control. */
    @Test fun tagCollectionLimitIsCheckedBeforeDatabasePublication() = runBlocking {
        val allowed = (0 until 10_000).joinToString(" ") { "#t$it" }
        try { validate("$allowed #overflow"); fail("Oversized tag collection accepted") } catch (_: IOException) { }
        validate(allowed)
    }
}
