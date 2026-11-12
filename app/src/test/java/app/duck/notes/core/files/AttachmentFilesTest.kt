package app.duck.notes.core.files

import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    /** Plant ATTACHMENT_UNCONFINED: return File(location) directly from resolve. */
    @Test fun portableAndRestoredKeysStayInsideCurrentOwnedStorage() {
        val root = temporary.newFolder("new-install", "files")
        val owned = AttachmentFiles(root)
        val id = UUID.randomUUID().toString()
        val target = File(root.canonicalFile, "attachments/$id")
        assertEquals(target, owned.resolve("attachments/$id"))
        assertEquals(target, owned.resolve(File(temporary.root, "old-install/files/attachments/$id").absolutePath))
        for (bad in listOf("../$id", "attachments/../$id", "attachments/$id/child", "/outside/$id", "/old/files/attachments/../$id", "attachments/not-an-id")) {
            assertNull(bad, owned.resolve(bad))
        }
    }
    /** Plant ATTACHMENT_OVERWRITE: remove the destination.exists rejection in publish. */
    @Test fun publishingCannotOverwriteAFileThatAlreadyExists() {
        val owned = AttachmentFiles(temporary.newFolder("files"))
        val id = UUID.randomUUID().toString()
        val target = requireNotNull(owned.target(id))
        val first = owned.temporary().apply { writeText("first complete image") }
        owned.publish(first, target)
        assertEquals("first complete image", target.readText())
        val second = owned.temporary().apply { writeText("second image") }
        try { owned.publish(second, target); fail("existing target overwritten") }
        catch (_: java.nio.file.FileAlreadyExistsException) { }
        assertEquals("first complete image", target.readText())
    }
    /** Plant IMAGE_BOUNDS_UNBOUNDED: remove acceptsDimensions bound or return sample1. */
    @Test fun newImageBoundsAndDecodeAreIndependentlyPinned() {
        assertEquals(20L * 1024 * 1024, AttachmentImageLimits.MAX_BYTES)
        assertTrue(AttachmentImageLimits.acceptsDimensions(10_000, 10_000))
        assertFalse(AttachmentImageLimits.acceptsDimensions(10_001, 10_000))
        assertTrue(AttachmentImageLimits.acceptsDimensions(32_768, 1))
        assertFalse(AttachmentImageLimits.acceptsDimensions(32_769, 1))
        assertTrue(AttachmentImageLimits.acceptsDimensions(1, 32_768))
        assertFalse(AttachmentImageLimits.acceptsDimensions(1, 32_769))
        assertFalse(AttachmentImageLimits.acceptsDimensions(0, 1))
        assertFalse(AttachmentImageLimits.acceptsDimensions(1, -1))
        assertFalse(AttachmentImageLimits.acceptsDimensions(1, 0))
        assertEquals(1, AttachmentImageLimits.sampleSize(2048, 2048))
        val sample = AttachmentImageLimits.sampleSize(32_768, 3000)
        assertTrue((32_768 + sample - 1) / sample <= 2048)
        assertTrue((3000 + sample - 1) / sample <= 2048)
        val tallSample = AttachmentImageLimits.sampleSize(3000, 32_768)
        assertTrue((3000 + tallSample - 1) / tallSample <= 2048)
        assertTrue((32_768 + tallSample - 1) / tallSample <= 2048)
    }
}
