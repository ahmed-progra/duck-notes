package app.duck.notes.presentation.markdown

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.duck.notes.core.files.AttachmentFiles
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.domain.model.Attachment
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageViewerWindowTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var originalSize: String? = null
    private var image: File? = null
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8) }

    @Before fun setUp() {
        assumeTrue("Display override qualification uses an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        originalSize = Regex("Override size: (\\d+x\\d+)").find(shell("wm size"))?.groupValues?.get(1) ?: "reset"
        shell("wm size 720x360")
        instrumentation.waitForIdleSync()
    }
    @After fun close() { image?.delete(); originalSize?.let { shell("wm size $it") } }

    /** Plants: allocate the image height before the controls, or let the first button consume their width. */
    @Test fun actualImageAndEveryZoomControlFitAShortWindowAtTwoHundredPercentText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        File(context.filesDir, "attachments").mkdirs()
        val file = requireNotNull(AttachmentFiles(context.filesDir).target(id))
        image = file
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.rgb(215, 73, 76))
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
        val attachment = Attachment(id, "window-fixture", AttachmentFiles.key(id), "image/png", createdAt = 1)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                DuckTheme { AttachmentStrip(listOf(attachment), onAddImage = {}, onDeleteAttachment = {}) }
            }
        }
        compose.waitForIdle()
        assertEquals("Display-width positive control", 720, context.resources.displayMetrics.widthPixels)
        assertEquals("Display-height positive control", 360, context.resources.displayMetrics.heightPixels)
        compose.onNodeWithContentDescription("Attachment").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertExists()
        compose.waitUntil(10_000) {
            val frame = compose.onNode(isDialog()).captureToImage()
            frame.toPixelMap()[frame.width / 2, frame.height / 2].toArgb() and 0xffffff == 0xD7494C
        }
        compose.onNodeWithText("Zoom out").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Zoom in").assertIsDisplayed().performClick()
        compose.onNodeWithText("Zoom out").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
    }
}
