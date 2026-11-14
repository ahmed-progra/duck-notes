package app.duck.notes.core.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

internal data class AttachmentImageInfo(val mime: String, val width: Int, val height: Int, val size: Long) {
    val extension: String get() = requireNotNull(attachmentImageExtension(mime))
}

internal fun attachmentImageExtension(mime: String): String? = when (mime) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    else -> null
}

/** Picker imports and portable imports validate the same actual bytes and bounded decode. */
internal fun attachmentImageInfo(file: File): AttachmentImageInfo? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val mime = bounds.outMimeType ?: return null
    if (attachmentImageExtension(mime) == null ||
        !AttachmentImageLimits.acceptsDimensions(bounds.outWidth, bounds.outHeight)) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = AttachmentImageLimits.sampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
    }
    val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
    return try {
        if (bitmap.width > AttachmentImageLimits.DECODE_SIDE || bitmap.height > AttachmentImageLimits.DECODE_SIDE) null
        else AttachmentImageInfo(mime, bounds.outWidth, bounds.outHeight, file.length())
    } finally { bitmap.recycle() }
}
