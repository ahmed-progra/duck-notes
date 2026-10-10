package app.duck.notes.settings

import android.graphics.Rect as AndroidRect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.duck.notes.core.theme.DuckTheme
import app.duck.notes.core.theme.DuckThemeSpec
import app.duck.notes.domain.model.Note
import app.duck.notes.domain.model.NoteLink
import app.duck.notes.presentation.shell.DuckShell
import app.duck.notes.presentation.shell.ShellActions
import app.duck.notes.presentation.shell.ShellUiState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Samples pixels painted by the real DuckShell InfoPanel action Text nodes, not token/style values.
 * Both target rows use production uiLabel (12sp); note-supplied uppercase stems give glyph interiors.
 *
 * Plants: separately change each corrected DuckInfoPanel accentText argument back to accent. The
 * corresponding row must fail GLYPH_COLOR after the mutated production code builds successfully.
 * These guards exercise the production stateless shell; no Room behavior is claimed.
 *
 * Limits: Android window capture and glyph interiors must exist. Clipping, blocked screenshots or
 * missing solid pixels fail the test; they are not counted as a color pass. This covers LTR fixtures,
 * ordinary font rasterization and two InfoPanel action roles, not every text role or physical panel.
 */
@RunWith(AndroidJUnit4::class)
class InfoPanelTextRoleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun expandedPaneActionGlyphsUseTextColorsInEightPalettes() = checkHost(expanded = true)

    // Compose ui-test 1.9.4 explicitly cannot capture a Dialog window on API26/27.
    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun compactSheetActionGlyphsUseTextColorsInEightPalettes() = checkHost(expanded = false)

    private fun checkHost(expanded: Boolean) {
        var palette by mutableStateOf(PALETTES.first())
        val buffer = TextFieldState("# $HEADING\nbody")
        val state = ShellUiState(
            notes = listOf(NOTE),
            selectedNoteId = NOTE.id,
            selectedNoteBody = "# $HEADING\nbody",
            selectedNoteBacklinks = listOf(
                NoteLink("pixel-source", NOTE.id, NOTE.title, BACKLINK),
            ),
            infoOpen = true,
        )
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                DuckTheme(spec = palette.spec, dark = palette.dark) {
                    Box(Modifier.fillMaxSize().clipToBounds()) {
                        if (expanded) {
                            // requiredWidth alone centers overflow. This wrapper instead places the
                            // actual 1200dp shell's right edge at the physical viewport's right edge.
                            Box(
                                Modifier.align(Alignment.TopEnd)
                                    .wrapContentWidth(align = Alignment.End, unbounded = true)
                                    .requiredWidth(1200.dp)
                                    .fillMaxHeight(),
                            ) {
                                DuckShell(state, buffer, ShellActions())
                            }
                        } else {
                            Box(Modifier.widthIn(max = 400.dp).fillMaxSize()) {
                                DuckShell(state, buffer, ShellActions())
                            }
                        }
                    }
                }
            }
        }
        for (expected in PALETTES) {
            rule.runOnIdle { palette = expected }
            rule.waitForIdle()
            val background = if (expanded) expected.surface else expected.elevated
            val host = if (expanded) "Expanded pane" else "Compact sheet"
            assertRowPixels(HEADING, expected, background, host)
            assertRowPixels(BACKLINK, expected, background, host)
        }
    }

    private fun assertRowPixels(text: String, expected: PaletteCase, background: Int, host: String) {
        val target = rule.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
        assertFullyVisible(target, "${expected.label}/$host/$text")
        val image = target.captureToImage()
        val foregroundSamples = samples(image, expected.foreground)
        // A connected blank trailing tile proves the surface. Scattered antialiased glyph-edge
        // pixels can accidentally equal an elevated fill even when the actual background is wrong.
        val backgroundSamples = samples(image, background, trailingBackground = true)
        assertTrue(
            "GLYPH_COLOR ${expected.label}/$host/$text: expected opaque glyph interiors " +
                "${hex(expected.foreground)}, found ${foregroundSamples.count}; capture=${image.width}x${image.height}. " +
                "Wrong role, clipping or insufficient fully painted glyph pixels is not a pass.",
            foregroundSamples.count >= 12,
        )
        assertEquals(
            "ROW_SURFACE ${expected.label}/$host/$text: expected ${hex(background)}, " +
                "found ${backgroundSamples.count} opaque pixels",
            96,
            backgroundSamples.count,
        )
        // The absence detector has its own real-render positive control above. A bright green
        // sentinel cannot be emitted by any foreground/background pair in this handwritten table.
        assertEquals("sentinel must not match this glyph crop", 0, samples(image, 0xFF00FF00.toInt()).count)
        val contrast = contrast(foregroundSamples.mean(), backgroundSamples.mean())
        assertTrue("NORMAL_TEXT ${expected.label}/$host/$text: sampled contrast=$contrast, requires 4.5", contrast >= 4.5)
    }

    private fun assertFullyVisible(target: SemanticsNodeInteraction, label: String) {
        val node = target.fetchSemanticsNode()
        val view = (node.root as ViewRootForTest).view
        val nativeVisible = rule.runOnIdle {
            AndroidRect().also { assertTrue("NO_NATIVE_VIEWPORT $label", view.getLocalVisibleRect(it)) }
        }
        val raw = target.getUnclippedBoundsInRoot()
        val unclipped = with(node.layoutInfo.density) {
            floatArrayOf(raw.left.toPx(), raw.top.toPx(), raw.right.toPx(), raw.bottom.toPx())
        }
        val clipped = node.boundsInRoot
        val actual = floatArrayOf(clipped.left, clipped.top, clipped.right, clipped.bottom)
        assertTrue("EMPTY_TARGET $label: $raw", unclipped[2] > unclipped[0] && unclipped[3] > unclipped[1])
        assertTrue(
            "COMPOSE_CLIPPED $label: unclipped=$raw, clipped=$clipped",
            actual.indices.all { abs(actual[it] - unclipped[it]) <= 1f },
        )
        assertTrue(
            "PHYSICALLY_OFFSCREEN $label: target=$raw nativeVisible=$nativeVisible",
            unclipped[0] >= nativeVisible.left - 1f && unclipped[1] >= nativeVisible.top - 1f &&
                unclipped[2] <= nativeVisible.right + 1f && unclipped[3] <= nativeVisible.bottom + 1f,
        )
    }

    private data class Samples(var count: Int = 0, var red: Long = 0L, var green: Long = 0L, var blue: Long = 0L) {
        fun mean(): Int {
            check(count > 0)
            return 0xFF000000.toInt() or
                (((red.toDouble() / count).roundToInt()) shl 16) or
                (((green.toDouble() / count).roundToInt()) shl 8) or
                (blue.toDouble() / count).roundToInt()
        }
    }

    private fun samples(image: ImageBitmap, expected: Int, trailingBackground: Boolean = false): Samples {
        val pixels = image.toPixelMap()
        val matches = Samples()
        check(image.width >= 20 && image.height >= 8) { "capture is too small for real glyph/surface sampling" }
        val xs = if (trailingBackground) (image.width - 20) until (image.width - 8) else 0 until image.width
        val ys = if (trailingBackground) (image.height / 2 - 4) until (image.height / 2 + 4) else 0 until image.height
        for (y in ys) for (x in xs) {
            val argb = pixels[x, y].toArgb()
            if ((argb ushr 24) != 255) continue
            if ((0..2).any { channel ->
                    val shift = channel * 8
                    abs(((argb ushr shift) and 255) - ((expected ushr shift) and 255)) > 2
                }) continue
            matches.count++
            matches.red += (argb ushr 16) and 255
            matches.green += (argb ushr 8) and 255
            matches.blue += argb and 255
        }
        return matches
    }

    private fun contrast(foreground: Int, background: Int): Double {
        fun luminance(argb: Int): Double {
            fun linear(shift: Int): Double {
                val s = ((argb ushr shift) and 255) / 255.0
                return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
        }
        val a = luminance(foreground)
        val b = luminance(background)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    private fun hex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    private data class PaletteCase(
        val label: String,
        val spec: DuckThemeSpec,
        val dark: Boolean,
        val foreground: Int,
        val surface: Int,
        val elevated: Int,
    )

    private companion object {
        const val HEADING = "MMMMW"
        const val BACKLINK = "WWMMM"
        val NOTE = Note("pixel-target", "Pixel note", "# $HEADING\nbody", createdAt = 0L, modifiedAt = 1L)

        // Handwritten from the approved contrast table. Never derive expectations from spec.colors,
        // DuckTheme.colors, TextStyle, semantics style metadata, or a production palette property.
        val PALETTES = listOf(
            PaletteCase("Duck Red light", DuckThemeSpec.DuckRed, false, 0xFFB93C3F.toInt(), 0xFFFFFFFF.toInt(), 0xFFF7F7F8.toInt()),
            PaletteCase("Duck Red dark", DuckThemeSpec.DuckRed, true, 0xFFF08386.toInt(), 0xFF1C1C1E.toInt(), 0xFF2A2A2D.toInt()),
            PaletteCase("Graphite light", DuckThemeSpec.Graphite, false, 0xFF4A515C.toInt(), 0xFFFFFFFF.toInt(), 0xFFF6F6F7.toInt()),
            PaletteCase("Graphite dark", DuckThemeSpec.Graphite, true, 0xFFAEB6C4.toInt(), 0xFF1A1B1D.toInt(), 0xFF27282B.toInt()),
            PaletteCase("Sepia light", DuckThemeSpec.Sepia, false, 0xFF86582A.toInt(), 0xFFFBF5E6.toInt(), 0xFFF5EEDC.toInt()),
            PaletteCase("Sepia dark", DuckThemeSpec.Sepia, true, 0xFFDCA867.toInt(), 0xFF15221F.toInt(), 0xFF1E2C29.toInt()),
            PaletteCase("Midnight light", DuckThemeSpec.Midnight, false, 0xFF2F5595.toInt(), 0xFFFFFFFF.toInt(), 0xFFF5F7FA.toInt()),
            PaletteCase("Midnight dark", DuckThemeSpec.Midnight, true, 0xFF8FB4EC.toInt(), 0xFF14181F.toInt(), 0xFF1F2530.toInt()),
        )
    }
}
