package app.duck.notes.presentation.markdown

import android.content.ClipData
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `shouldAttach` — the real per-item paste decision — against **real** `ClipData.Item`s.
 *
 * ## Why this drives `shouldAttach` and not the listener
 *
 * `TransferableContent`'s only constructor is private (verified against Foundation 1.9.4's bytecode),
 * so `imagePasteReceiver.onReceive` cannot be handed a synthetic input in a test. The classifying
 * decision was split into `shouldAttach` / `isAttachableImage` precisely so it *can* be driven — from
 * `ClipData.Item`, which has public constructors. The thin `hasMediaType` + `consume` wrapper around
 * it has no branch of its own and is covered by the one-time manual on-device paste recorded in the
 * 8d-c gate report, not by an automated test faking an unconstructable type.
 *
 * `Uri`/`ClipData.Item` are Android framework types (stubbed to throw in JVM unit tests), which is why
 * this is instrumented rather than a plain JUnit test.
 */
@RunWith(AndroidJUnit4::class)
class ImagePasteReceiverTest {

    private val imageUri: Uri = Uri.parse("content://media/external/images/media/42")

    @Test
    fun anImageItemYieldsItsUriAndResolvedMime() {
        val item = ClipData.Item(imageUri)
        assertEquals(imageUri to "image/png", shouldAttach(item) { "image/png" })
    }

    @Test
    fun aNonImageUriItemYieldsNull() {
        val pdf = Uri.parse("content://com.example.docs/report.pdf")
        assertNull(shouldAttach(ClipData.Item(pdf)) { "application/pdf" })
    }

    /** A plain-text clip item has no uri, so nothing is attached and it falls through to the platform. */
    @Test
    fun aTextItemYieldsNull() {
        assertNull(shouldAttach(ClipData.Item("just some pasted text")) { "image/png" })
    }

    /** Resolver can't type the uri → don't store it as an image on a guess; let the platform have it. */
    @Test
    fun anImageUriWhoseTypeIsUnknownYieldsNull() {
        assertNull(shouldAttach(ClipData.Item(imageUri)) { null })
    }
}
