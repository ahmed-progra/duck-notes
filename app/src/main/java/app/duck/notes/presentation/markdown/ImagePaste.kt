package app.duck.notes.presentation.markdown

import android.content.ClipData
import android.net.Uri
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.hasMediaType

/**
 * The paste side of images: a [ReceiveContentListener] that pulls image items out of a clipboard
 * paste (or drag-and-drop, or keyboard rich content) and attaches them, exactly as the picker does —
 * the reference is written by the same `AttachImage` path, so nothing here spells `![` and there is no
 * second implementation of image syntax.
 *
 * ## Why the decision is split out of the listener
 *
 * `TransferableContent`'s only constructor is **private** (verified against Foundation 1.9.4's
 * bytecode), so a test cannot synthesise one to drive [imagePasteReceiver.onReceive]. The classifying
 * decision therefore lives in [shouldAttach] and [isAttachableImage], which take ordinary
 * `ClipData.Item` / `String` a test *can* build. The listener's remaining body — the `hasMediaType`
 * gate and `consume` mechanics — is three framework lines with no branch of its own, covered by a
 * one-time manual on-device paste recorded in the phase's gate report rather than by an automated test
 * that would have to fake an unconstructable type.
 */

/** An attachable image is any `image/` MIME (`image/png`, `image/jpeg`, …). Pure, so a test can pin it. */
internal fun isAttachableImage(mimeType: String?): Boolean = mimeType?.startsWith("image/") == true

/**
 * The per-item paste decision: the item's `(uri, mime)` if it resolves to an attachable image — else
 * null. Returns the resolved mime alongside the uri so the caller neither re-resolves nor guesses.
 *
 * [mimeOf] resolves a content uri to its MIME (the `ContentResolver` in production). Driven with real
 * `ClipData.Item`s by `ImagePasteReceiverTest`; the real production symbol, not a re-implementation.
 */
internal fun shouldAttach(item: ClipData.Item, mimeOf: (Uri) -> String?): Pair<Uri, String>? {
    val uri = item.uri ?: return null
    val mime = mimeOf(uri) ?: return null
    return if (isAttachableImage(mime)) uri to mime else null
}

/**
 * Builds the editor's paste listener. Image items go to [onAttachImages] (as `uri to mime` pairs, in
 * order); everything else is returned unconsumed for the platform to insert as normal text.
 *
 * The `content://` grant a pasted image carries is the same revocable, process-scoped grant the picker
 * gives — `AttachmentStorage` copies the bytes out of it immediately, so the copy outlives the grant.
 */
internal fun imagePasteReceiver(
    mimeOf: (Uri) -> String?,
    onAttachImages: (List<Pair<String, String>>) -> Unit,
): ReceiveContentListener = object : ReceiveContentListener {
    override fun onReceive(content: TransferableContent): TransferableContent? {
        // Cheap gate: nothing to do for a text-only paste, and it avoids a resolver call per item.
        if (!content.hasMediaType(MediaType.Image)) return content
        val attached = mutableListOf<Pair<String, String>>()
        val remaining = content.consume { item ->
            val hit = shouldAttach(item, mimeOf)
            if (hit != null) {
                attached += hit.first.toString() to hit.second
                true
            } else {
                false
            }
        }
        if (attached.isNotEmpty()) onAttachImages(attached)
        return remaining
    }
}
