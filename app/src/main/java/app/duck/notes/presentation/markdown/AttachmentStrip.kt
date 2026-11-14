package app.duck.notes.presentation.markdown

import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.duck.notes.R
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.core.files.AttachmentImageLimits
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.ui.DuckDivider
import app.duck.notes.domain.model.Attachment
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Precision

/**
 * The attachment strip — a horizontal row of thumbnails under the editor, with a leading add tile.
 *
 * **Not inline rendering.** The phase spec's "images in the note" is a strip beside the body, not a
 * bitmap spliced into the rendered markdown. Inline would mean an `InlineTextContent` keyed off a
 * `TokenKind.IMAGE` span, sized from the decoded bitmap, re-measured inside the same
 * `OutputTransformation` the caret and every overlay already contend over. The reference in the body
 * stays a plain `![alt](attachment:id)` — visible source, one home for the literal in `MarkdownScanner`
 * — and this strip is the *view* of the files those references point at. Recorded as a deviation in
 * `DUCK_NOTES_BUILD_PROMPT.md`.
 *
 * ## The placeholder is a bordered tile, not a muted glyph
 *
 * Every tile is `surfaceElevated` under a `divider` hairline, and that fill is what shows while Coil
 * decodes or if the file is gone. There is deliberately **no** placeholder icon or text: the token
 * for image placeholders is [proseMuted], but a 4.5:1 glyph on a 64dp chrome tile is heavier than the
 * thumbnail it stands in for, and [chromeMuted] — the lighter one — is barred from carrying anything
 * readable at all (`DuckColors.chromeMuted`). The empty bordered tile owes neither, so it uses
 * neither. Recorded with the token deviation in the phase notes.
 *
 * ## RTL is the framework's, not ours
 *
 * `LazyRow` lays its items start-to-end, so under `LayoutDirection.Rtl` the add tile (first item) sits
 * at the **right** and thumbnails run leftward — the mirror a user reading right-to-left expects, for
 * free. This composable adds nothing to make that happen; `RtlLayoutTest.theAttachmentStripMirrors…`
 * is what proves the framework actually does, rather than us assuming it.
 */
@Composable
fun AttachmentStrip(
    attachments: List<Attachment>,
    onAddImage: () -> Unit,
    onDeleteAttachment: (Attachment) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
) {
    val colors = DuckTheme.colors
    var zoomed by remember(attachments) { mutableStateOf<Attachment?>(null) }
    var menu by remember(attachments) { mutableStateOf<Attachment?>(null) }
    var deleting by remember(attachments) { mutableStateOf<Attachment?>(null) }

    Column(modifier.fillMaxWidth().background(colors.surfaceElevated)) {
        // Separates the strip from the editor body above. surfaceElevated on surface is 1.06 in
        // SepiaLight, same argument the format toolbar's own leading divider makes.
        DuckDivider()
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(DuckTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(DuckTheme.spacing.sm),
        ) {
            if (editable) item(key = "add") { AddTile(onClick = onAddImage) }
            items(attachments, key = { it.id }) { attachment ->
                Thumbnail(
                    attachment = attachment,
                    onClick = { zoomed = attachment },
                    onLongClick = if (editable) ({ menu = attachment }) else null,
                )
            }
        }
    }

    DropdownMenu(expanded = menu != null && editable, onDismissRequest = { menu = null }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.attachment_view)) }, onClick = { zoomed = menu; menu = null })
        DropdownMenuItem(text = { Text(stringResource(R.string.attachment_delete)) }, onClick = { deleting = menu; menu = null })
    }
    deleting?.takeIf { editable }?.let { selected ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.attachment_delete)) },
            text = { Text(stringResource(R.string.attachment_delete_confirmation)) },
            confirmButton = { TextButton(onClick = { deleting = null; onDeleteAttachment(selected) }) { Text(stringResource(R.string.tag_delete_confirm)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.tag_cancel)) } },
        )
    }
    zoomed?.let { ZoomDialog(it, onDismiss = { zoomed = null }) }
}

/** The leading tile that opens the system photo picker. */
@Composable
private fun AddTile(onClick: () -> Unit) {
    val colors = DuckTheme.colors
    val shape = DuckTheme.shapes.small
    val label = stringResource(R.string.attachment_add)
    Box(
        modifier = Modifier
            .size(TILE)
            .clip(shape)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.divider, shape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // onSurface, not a muted token: "+" is the tile's content, not chrome. clearAndSetSemantics
        // is unneeded — the parent's contentDescription wins over a single-glyph child on merge.
        Text(text = "+", style = DuckTheme.type.noteTitle, color = colors.onSurface)
    }
}

/** One thumbnail. Tap views it; long-press opens its action menu. */
@Composable
private fun Thumbnail(
    attachment: Attachment,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val colors = DuckTheme.colors
    val shape = DuckTheme.shapes.small
    AsyncImage(
        model = attachmentRequest(attachment.uri, AttachmentImageLimits.THUMBNAIL_SIDE),
        contentDescription = stringResource(R.string.attachment_image),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(TILE)
            .clip(shape)
            .background(colors.surfaceElevated)
            .border(1.dp, colors.divider, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = stringResource(R.string.attachment_actions)),
    )
}

/** Bounded pan/zoom keeps the image reachable; explicit controls also work without gestures. */
@Composable
private fun ZoomDialog(attachment: Attachment, onDismiss: () -> Unit) {
    var scale by remember(attachment.id) { mutableStateOf(1f) }
    var pan by remember(attachment.id) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transform = rememberTransformableState { zoom, movement, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        val maxX = size.width * (scale - 1f) / 2f
        val maxY = size.height * (scale - 1f) / 2f
        pan = Offset((pan.x + movement.x).coerceIn(-maxX, maxX), (pan.y + movement.y).coerceIn(-maxY, maxY))
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(DuckTheme.shapes.medium).background(DuckTheme.colors.surface)) {
            Box(Modifier.fillMaxWidth().height(360.dp).clip(DuckTheme.shapes.medium).onSizeChanged { size = it }.transformable(transform)) {
                AsyncImage(
                    model = attachmentRequest(attachment.uri, AttachmentImageLimits.DECODE_SIDE),
                    contentDescription = stringResource(R.string.attachment_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize().graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = pan.x; translationY = pan.y
                    },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { scale = (scale - 1f).coerceAtLeast(1f); pan = Offset.Zero }, enabled = scale > 1f) { Text(stringResource(R.string.attachment_zoom_out)) }
                TextButton(onClick = { scale = (scale + 1f).coerceAtMost(5f) }, enabled = scale < 5f) { Text(stringResource(R.string.attachment_zoom_in)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.attachment_close)) }
            }
        }
    }
}

/** Legacy absolute paths are rebound to this installation; Coil never receives an arbitrary file. */
@Composable
private fun attachmentRequest(location: String, side: Int): ImageRequest {
    val context = LocalContext.current
    return remember(context, location, side) {
        val file = try { AttachmentFiles(context.filesDir).resolve(location) } catch (_: Exception) { null }
        ImageRequest.Builder(context)
            .data(file)
            .size(side, side)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .build()
    }
}

/** Every tile is this square: the strip lays out at a fixed size, so no bitmap is measured yet. */
private val TILE = 64.dp

@Preview(showBackground = true)
@Composable
private fun AttachmentStripPreview() {
    // Empty list, so the preview needs no real File on disk — it shows the add tile, the one thing
    // always present. A populated preview would need a fixture image the debug gallery does not have.
    DuckTheme {
        AttachmentStrip(attachments = emptyList(), onAddImage = {}, onDeleteAttachment = {})
    }
}
