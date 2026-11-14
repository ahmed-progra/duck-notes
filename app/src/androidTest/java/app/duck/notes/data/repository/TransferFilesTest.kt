package app.duck.notes.data.repository

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.transfer.TransferFiles
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferFilesTest {
    private lateinit var fixture: File
    private lateinit var context: Context
    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        fixture = File(app.cacheDir, "transfer-files-${UUID.randomUUID()}").apply { check(mkdir()) }
        context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(fixture, "no-backup").apply { mkdirs() }
            override fun getCacheDir() = File(fixture, "cache").apply { mkdirs() }
        }
    }
    @After fun tearDown() { fixture.deleteRecursively() }

    /** Plant: omit active-job exclusion; startup cleanup deletes the current export. */
    @Test fun startupCleanupPreservesActiveJobsAcrossRepositoryInstances() {
        val owner = TransferFiles(context)
        val job = owner.create()
        val content = File(job.directory, "document").apply { writeText("synthetic note", Charsets.UTF_8) }
        val other = TransferFiles(context)
        try {
            other.cleanAbandoned()
            assertTrue("Startup cleanup removed the active payload", content.isFile)
            assertEquals("synthetic note", content.readText(Charsets.UTF_8))
            assertEquals(job.directory, other.find(job.id).directory)
        } finally { owner.close(job.id) }
        assertFalse(job.directory.exists())
    }

    /** Plants: FOLLOW_LINKS in cleanup, or validate UUIDs by length alone. */
    @Test fun abandonedCleanupDoesNotFollowLinksOrDeleteANonUuidNeighbor() {
        val root = File(context.noBackupFilesDir, "transfers").apply { mkdirs() }
        val target = File(fixture, "outside-stage").apply { mkdir() }
        val sentinel = File(target, "keep").apply { writeText("KEEP", Charsets.UTF_8) }
        val abandoned = File(root, "00000000-0000-0000-0000-000000000001").apply { mkdir() }
        File(abandoned, "temporary").writeText("discard", Charsets.UTF_8)
        val link = File(abandoned, "link")
        Os.symlink(target.path, link.path)
        val neighbor = File(root, "z".repeat(36)).apply { mkdir() }
        val neighborContent = File(neighbor, "keep").apply { writeText("KEEP", Charsets.UTF_8) }
        try {
            TransferFiles(context).cleanAbandoned()
            assertFalse(abandoned.exists())
            assertTrue(sentinel.isFile)
            assertTrue(neighborContent.isFile)
        } finally { link.delete() }
    }

    @Test fun traversalCannotResolveAStageAndMissingStagesExpire() {
        val files = TransferFiles(context)
        val job = files.create()
        try {
            try { files.find("../${job.id}"); fail("traversal accepted") } catch (_: IOException) { }
        } finally { files.close(job.id) }
        try { files.find(job.id); fail("expired job accepted") } catch (_: IOException) { }
    }

    /** Plant: change the literal 24-hour retention or treat a non-UUID directory as owned. */
    @Test fun sharedExportsAreRetainedForOneDayAndOnlyOwnedDirectoriesAreRemoved() {
        val root = File(context.cacheDir, "exports").apply { mkdirs() }
        val owned = File(root, UUID.randomUUID().toString()).apply { mkdir(); File(this, "note.md").writeText("synthetic") }
        val neighbor = File(root, "z".repeat(36)).apply { mkdir(); File(this, "keep").writeText("keep") }
        val born = 1_700_000_000_000L
        assertTrue(owned.setLastModified(born))
        assertTrue(neighbor.setLastModified(born))
        val files = TransferFiles(context)
        files.cleanSharedExports(born + 86_399_999L)
        assertTrue(owned.isDirectory)
        files.cleanSharedExports(born + 86_400_001L)
        assertFalse("Expired shared export was retained", owned.exists())
        assertTrue(File(neighbor, "keep").isFile)
    }
}
