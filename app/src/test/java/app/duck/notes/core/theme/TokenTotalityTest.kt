package app.duck.notes.core.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the token layer is *complete*, as distinct from [ContrastTest] asserting it is *legible*.
 *
 * The pinned slot count is the tripwire for a Compose BOM upgrade. It has already earned itself once:
 * Material 3 1.4.0 carries twelve "fixed" accent roles that the first draft of the projection did not
 * assign, leaving them on M3's baseline purple. Nothing else would have noticed.
 */
class TokenTotalityTest {

    private fun schemeSlots(scheme: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE && '-' in it.name }
            .associate { m ->
                val name = m.name.substringBefore('-')
                    .removePrefix("get")
                    .replaceFirstChar { it.lowercase() }
                name to Color((m.invoke(scheme) as Long).toULong())
            }

    @Test
    fun everyThemeResolvesBothModes() {
        DuckThemeSpec.entries.forEach { spec ->
            listOf(false, true).forEach { dark ->
                val c = spec.colors(dark)
                assertNotEquals("${spec.name} $dark: surface and onSurface collapsed", c.surface, c.onSurface)
                assertNotEquals("${spec.name} $dark: accent and surface collapsed", c.accent, c.surface)
            }
            assertNotEquals(
                "${spec.name}: light and dark are the same palette",
                spec.light.surface,
                spec.dark.surface,
            )
        }
        assertEquals(4, DuckThemeSpec.entries.size)
    }

    /**
     * accentText and accentPress collapsing onto one hex in light DuckRed is intentional — a prose
     * glyph and a pressed fill that happen to agree. Pinned here so a future reader cannot "clean it
     * up", and so the divergence in dark mode is visible as a fact rather than an accident.
     */
    @Test
    fun accentTextAndAccentPressAgreeInLightAndDivergeInDark() {
        assertEquals(DuckRedLight.accentPress, DuckRedLight.accentText)
        assertNotEquals(DuckRedDark.accentPress, DuckRedDark.accentText)
        assertNotEquals(DuckRedDark.accent, DuckRedDark.accentText)
    }

    /**
     * `surfaceTint` must equal `surface`, making M3's tonal elevation overlay an identity operation.
     *
     * `surfaceColorAtElevation` blends `surfaceTint` into every elevated surface at 5–14%. Any accent
     * here would reach Cards, Menus and sheets through a path [ContrastTest] cannot measure, and would
     * wash Graphite and Midnight red. Elevation is [DuckColors.surfaceElevated]'s job, which *is*
     * measured.
     */
    @Test
    fun tonalElevationOverlayIsAnIdentityOperation() {
        DuckThemeSpec.entries.forEach { spec ->
            listOf(false, true).forEach { dark ->
                val scheme = duckColorScheme(spec, dark)
                assertEquals(
                    "${spec.name} ${if (dark) "dark" else "light"}: surfaceTint must equal surface",
                    scheme.surface,
                    scheme.surfaceTint,
                )
            }
        }
    }

    /** The brand red must reach no Material slot at all — it is Duck components' colour only. */
    @Test
    fun accentReachesNoMaterialSlot() {
        val scheme = duckColorScheme(DuckThemeSpec.DuckRed, dark = false)
        val offenders = schemeSlots(scheme).filterValues { it == BrandAccent }.keys
        assertEquals(
            "accent is 4.25:1 on white — it paints Duck fills, never a Material slot",
            emptySet<String>(),
            offenders,
        )
    }

    @Test
    fun everySchemeSlotCountIsPinned() {
        val slots = schemeSlots(duckColorScheme(DuckThemeSpec.DuckRed, dark = false))
        assertEquals(
            "ColorScheme gained or lost slots — re-read ContrastTest's curated usage pairs, then " +
                "update this count. Do NOT just bump the number.",
            48,
            slots.size,
        )
    }

    /**
     * The motion family's **spec** slots, pinned here beside the colour-scheme tripwire for the same
     * reason: a new one is a deliberate act and should make somebody say so.
     *
     * Deliberately a different slot class from the two `MotionTest` already pins. That file counts
     * *durations* (3) and *easings* (3) — the raw values. This counts the ready-made specs built from
     * them, which is the surface call sites actually consume and the one that grew three times in a
     * single phase: `exitSpec`, `placementSpec`, `activeBarSpec`. Nothing else was watching that
     * number, so a fourth arriving unnoticed was the realistic failure.
     *
     * Return type rather than parameter count, because `activeBarSpec` takes an argument and a filter
     * written around the others would quietly not see it.
     */
    @Test
    fun theMotionSpecSlotCountIsPinned() {
        val specs = DuckMotion::class.java.methods
            .filter { AnimationSpec::class.java.isAssignableFrom(it.returnType) }
            .map { it.name }
        assertEquals(
            "DuckMotion gained or lost a spec accessor — decide whether call sites should be using " +
                "it, then update this count. Do NOT just bump the number. Found: ${specs.sorted()}",
            7,
            specs.size,
        )
    }

    @Test
    fun spacingScaleIsTheSpecScale() {
        val s = DuckSpacing()
        assertEquals(listOf(4.dp, 8.dp, 12.dp, 16.dp, 24.dp, 32.dp), listOf(s.xs, s.sm, s.md, s.lg, s.xl, s.xxl))
    }

    @Test
    fun cornerRadiiAreTheSpecRadii() {
        val s = DuckShapes()
        assertEquals(listOf(8.dp, 12.dp, 16.dp), listOf(s.smallRadius, s.mediumRadius, s.largeRadius))
    }

    @Test
    fun typographyHasSixRolesAllFromTheFontContract() {
        val t = duckType()
        val fonts = DuckPlatformFonts
        assertEquals(fonts.reading, t.noteBody.fontFamily)
        assertEquals(fonts.reading, t.noteTitle.fontFamily)
        assertEquals(fonts.ui, t.uiTitle.fontFamily)
        assertEquals(fonts.ui, t.uiBody.fontFamily)
        assertEquals(fonts.ui, t.uiSecondary.fontFamily)
        assertEquals(fonts.ui, t.uiLabel.fontFamily)
        assertEquals(fonts.mono, t.mono)
    }

    /**
     * The Phase 14 guarantee, as a property rather than a promise: swapping [DuckFonts] must move
     * every role, with no call site involved.
     */
    @Test
    fun swappingFontsMovesEveryRole() {
        val swapped = duckType(DuckFonts(reading = FontFamily_Cursive, ui = FontFamily_Cursive, mono = FontFamily_Cursive))
        listOf(
            swapped.noteBody.fontFamily, swapped.noteTitle.fontFamily, swapped.uiTitle.fontFamily,
            swapped.uiBody.fontFamily, swapped.uiSecondary.fontFamily, swapped.uiLabel.fontFamily,
        ).forEach { assertEquals(FontFamily_Cursive, it) }
        assertEquals(FontFamily_Cursive, swapped.mono)
        assertTrue("sizes must survive a family swap", swapped.noteBody.fontSize == duckType().noteBody.fontSize)
    }

    private companion object {
        val FontFamily_Cursive = androidx.compose.ui.text.font.FontFamily.Cursive
    }
}
