package app.duck.notes.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.duck.notes.data.transfer.*
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveManifestTest {
    private lateinit var directory: File
    private val first = "00000000-0000-0000-0000-000000000001"
    private val second = "00000000-0000-0000-0000-000000000002"
    private val valid get() = """{"version":1,"kind":"markdown","notes":[{"id":"$first","created":1,"modified":2,"pinned":true,"archived":false,"trashed":false,"trashedAt":null}],"images":[],"tags":[{"name":"research","pinned":true,"icon":"book","order":3}]}"""
    @Before fun setUp() { directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "manifest-${UUID.randomUUID()}").apply { check(mkdir()) } }
    @After fun close() { directory.deleteRecursively() }
    private fun read(text: String) = ArchiveManifestCodec.read(File(directory, "manifest.json").apply { writeText(text, Charsets.UTF_8) })
    private fun reject(text: String) {
        try { read(text); fail("Malformed manifest accepted") } catch (_: IOException) { }
    }

    @Test fun literalManifestAndWriterPreserveEveryMetadataField() {
        val expected = ArchiveManifest("markdown", listOf(ArchiveNote(first, 1, 2, true, false, false, null)), emptyList(), listOf(ArchiveTag("research", true, "book", 3)))
        assertEquals(expected, read(valid))
        val output = File(directory, "written.json")
        ArchiveManifestCodec.write(output, expected)
        assertEquals(expected, ArchiveManifestCodec.read(output))
    }

    /** Plants: omit seen-field check, unknown-field rejection, version check or UUID validation. */
    @Test fun ambiguousOrUnsupportedMetadataIsRejected() {
        reject(valid.replace("\"version\":1", "\"version\":1,\"version\":1"))
        reject(valid.dropLast(1) + ",\"unknown\":0}")
        reject(valid.replace("\"version\":1", "\"version\":2"))
        reject(valid.replace(first, "../notes"))
        reject(valid.replace("\"created\":1", "\"created\":-1"))
        reject(valid.replace("\"created\":1", "\"created\":253402300800000"))
        assertEquals(first, read(valid).notes.single().id)
    }

    /** Plant: remove owner validation or narrow Long dimensions before validating their range. */
    @Test fun imagesRequireARealOwnerAndCannotWrapTheirDimensions() {
        val image = """{"id":"$second","note":"$first","mime":"image/png","width":1,"height":1,"bytes":1,"created":1}"""
        val manifest = valid.replace("\"images\":[]", "\"images\":[$image]")
        assertEquals("assets/$second.png", read(manifest).images.single().entry)
        reject(manifest.replace("\"note\":\"$first\"", "\"note\":\"00000000-0000-0000-0000-000000000003\""))
        reject(manifest.replace("\"width\":1", "\"width\":4294967297"))
        reject(manifest.replace("\"width\":1", "\"width\":32769"))
        reject(manifest.replace("image/png", "image/svg+xml"))
    }

    /** Plant: replace malformed UTF-8 rather than reporting it; an otherwise valid icon then parses. */
    @Test fun malformedUtf8CannotHideInsideAnOptionalString() {
        val bytes = valid.replace("book", "bo?ok").toByteArray(Charsets.UTF_8)
        bytes[bytes.indexOf('?'.code.toByte())] = 0xff.toByte()
        val input = File(directory, "invalid.json").apply { writeBytes(bytes) }
        try { ArchiveManifestCodec.read(input); fail("Invalid UTF-8 accepted") } catch (_: IOException) { }
        assertEquals("book", read(valid).tags.single().icon)
    }

    @Test fun wrongJsonShapesProduceAnInputRejection() {
        reject("[]")
        reject("""{"version":1,"kind":"markdown","notes":{},"images":[],"tags":[]}""")
        assertEquals(first, read(valid).notes.single().id)
    }
}
