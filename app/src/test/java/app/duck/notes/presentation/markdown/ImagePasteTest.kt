package app.duck.notes.presentation.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MIME rule behind image paste: an attachment is any `image/` type, nothing else.
 *
 * Pure strings, so it runs on the JVM. The instrumented `ImagePasteReceiverTest` drives the same
 * predicate through `shouldAttach` against real `ClipData.Item`s; both go red on the one plant that
 * inverts this function.
 */
class ImagePasteTest {

    @Test
    fun imageMimesAreAttachable() {
        assertTrue(isAttachableImage("image/png"))
        assertTrue(isAttachableImage("image/jpeg"))
        assertTrue(isAttachableImage("image/gif"))
        assertTrue(isAttachableImage("image/webp"))
    }

    @Test
    fun nonImageMimesAreNot() {
        assertFalse(isAttachableImage("text/plain"))
        assertFalse(isAttachableImage("text/html"))
        assertFalse(isAttachableImage("application/pdf"))
        assertFalse(isAttachableImage("video/mp4"))
    }

    /** The boundary: it is `image/`, not merely `image` — `imagexml` is not an image type. */
    @Test
    fun aTypeThatMerelyStartsWithImageIsNotAnImage() {
        assertFalse(isAttachableImage("imagexml"))
        assertFalse(isAttachableImage("imageboard/thread"))
    }

    @Test
    fun emptyAndNullAreNot() {
        assertFalse(isAttachableImage(""))
        assertFalse(isAttachableImage(null))
    }
}
