package app.duck.notes.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The accessibility criterion, asserted rather than claimed.
 *
 * Two floors here are **Duck rules, not WCAG rules**, and are labelled as such so nobody reads them
 * as conformance:
 * - `1.2:1` "visibly distinct" for the four tints. This is what stops a code block from vanishing —
 *   Phase 4's `Color(0x14000000)` composites to `#1A1A1C` on `#1C1C1E`, a ratio of 1.03:1.
 * - `divider` and `surfaceElevated` are **recorded, not floored**. WCAG 1.4.11 exempts decorative
 *   separators, and `#E5E5EA` cannot reach 3:1 on white without becoming a heavy grey line. Their
 *   measured values are asserted so a change shows up in a diff instead of passing silently.
 *
 * The WCAG maths is implemented here rather than taken from `Color.luminance()`: this is the
 * assertion, so it should not share an implementation with anything it might be asserting about.
 */
class ContrastTest {

    // --- WCAG 2.1 relative luminance ---------------------------------------

    private fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun hex(c: Color): String =
        "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    // --- palettes under test ------------------------------------------------

    private data class Palette(val name: String, val colors: DuckColors, val scheme: ColorScheme)

    private fun palettes(): List<Palette> = DuckThemeSpec.entries.flatMap { spec ->
        listOf(false, true).map { dark ->
            Palette(
                name = "${spec.name} ${if (dark) "dark" else "light"}",
                colors = spec.colors(dark),
                scheme = duckColorScheme(spec, dark),
            )
        }
    }

    // --- Duck-side floors ---------------------------------------------------

    /** foreground, background, floor. AA 4.5 = 1.4.3 text · 3.0 = 1.4.11 non-text · 1.2 = Duck rule. */
    private val duckPairs: List<Triple<String, String, Double>> = listOf(
        Triple("onSurface", "surface", 4.5),
        Triple("onSurface", "surfaceElevated", 4.5),
        // codeSurface first: it is the BINDING background, not surface. See DuckPalettes KDoc.
        Triple("proseMuted", "codeSurface", 4.5),
        Triple("proseMuted", "surface", 4.5),
        Triple("proseMuted", "surfaceElevated", 4.5),
        Triple("chromeMuted", "surface", 3.0),
        Triple("chromeMuted", "surfaceElevated", 3.0),
        Triple("accentText", "surface", 4.5),
        Triple("accentText", "surfaceElevated", 4.5),
        Triple("accentText", "tagSurface", 4.5),
        Triple("accent", "surface", 3.0),
        Triple("accentPress", "surface", 3.0),
        Triple("codeOnSurface", "codeSurface", 4.5),
        Triple("onSurface", "highlight", 4.5),
        Triple("onSurface", "selection", 4.5),
        Triple("error", "errorSurface", 4.5),
        Triple("error", "surface", 4.5),
        // Duck rule: visibly distinct from the page. Applies to fills that ARE their own boundary —
        // a code block, a highlight, a selection. A fill inside a bordered component does not owe it;
        // see [componentFillsOnElevatedContainersAreRecorded].
        Triple("codeSurface", "surface", 1.2),
        Triple("highlight", "surface", 1.2),
        Triple("tagSurface", "surface", 1.2),
        Triple("selection", "surface", 1.2),

        // --- Phase 5b component pairs ------------------------------------
        // Every component introduces (foreground, background) pairs, and an unenumerated one is how
        // the gallery's Pill shipped a 4.25:1 label. These are the pairs the five components create.
        //
        // Most were already covered above; the ones here are the additions. Note that all of them
        // appear TWICE in effect — once against `surface` (the page) and once against
        // `surfaceElevated` — because Phase 6 puts the chip, checkbox and icon button in the sidebar,
        // which is an elevated container. Measuring only against the page is the same class of miss
        // as not measuring at all.
        //
        // Boundaries are 1.4.11 at 3:1. `accentText`/`chromeMuted` are the chip's two border states;
        // `accent` is the checkbox's checked fill, whose edge has to be perceivable against whatever
        // it sits on.
        Triple("accent", "surfaceElevated", 3.0),
    )

    /**
     * element, enabled colour, disabled colour. See
     * [everyDisabledStateIsDistinguishableFromEnabled] — this axis is foreground-vs-foreground.
     */
    private val distinguishability: List<Triple<String, String, String>> = listOf(
        Triple("icon button glyph", "onSurface", "chromeMuted"),
        Triple("checkbox border", "chromeMuted", "divider"),
    )

    private fun DuckColors.slot(name: String): Color = when (name) {
        "accent" -> accent
        "accentPress" -> accentPress
        "accentText" -> accentText
        "surface" -> surface
        "surfaceElevated" -> surfaceElevated
        "onSurface" -> onSurface
        "proseMuted" -> proseMuted
        "chromeMuted" -> chromeMuted
        "divider" -> divider
        "codeSurface" -> codeSurface
        "codeOnSurface" -> codeOnSurface
        "tagSurface" -> tagSurface
        "highlight" -> highlight
        "selection" -> selection
        "error" -> error
        "errorSurface" -> errorSurface
        else -> throw IllegalArgumentException("unknown Duck slot $name")
    }

    @Test
    fun everyDuckPairMeetsItsFloor() {
        val failures = palettes().flatMap { p ->
            duckPairs.mapNotNull { (fg, bg, floor) ->
                val r = contrast(p.colors.slot(fg), p.colors.slot(bg))
                if (r < floor) "${p.name}: $fg on $bg = %.2f < $floor".format(r) else null
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * Positive control for [everyDuckPairMeetsItsFloor], which is an absence assertion: delete every
     * entry from [duckPairs] and it reports green over an empty list.
     *
     * Pinned by count, and by the presence of the pairs that are easiest to lose — the two that were
     * discovered rather than designed. `proseMuted`/`codeSurface` came from revealed backticks;
     * `accent`/`surfaceElevated` came from noticing that Phase 6 puts the checkbox in a sidebar.
     */
    @Test
    fun theDuckPairListIsNotSilentlyEmpty() {
        assertEquals(
            "duckPairs changed size — add the pair and update this count, or explain the removal",
            22,
            duckPairs.size,
        )
        listOf(
            Triple("proseMuted", "codeSurface", 4.5),
            Triple("accent", "surfaceElevated", 3.0),
            Triple("accentText", "tagSurface", 4.5),
        ).forEach {
            assertTrue("duckPairs must still contain $it", it in duckPairs)
        }
    }

    // --- derived ColorScheme ------------------------------------------------

    /**
     * Every `Color` slot on [ColorScheme], by name.
     *
     * `Color` is a `@JvmInline value class`, so Kotlin mangles every getter that returns one —
     * `getPrimary-0d7_KjU()`, not `getPrimary()`, with the suffix hashing the return type. Name-based
     * pairing matches nothing unless the suffix is stripped first, and it fails *silently*: an empty
     * map asserts green. [reflectionFindsTheSlots] exists to make that impossible.
     */
    private fun schemeSlots(scheme: ColorScheme): Map<String, Color> =
        ColorScheme::class.java.methods
            .filter { it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE && '-' in it.name }
            .associate { m ->
                val name = m.name.substringBefore('-')
                    .removePrefix("get")
                    .replaceFirstChar { it.lowercase() }
                name to Color((m.invoke(scheme) as Long).toULong())
            }

    private val ladder = listOf(
        "surfaceBright", "surfaceDim", "surfaceContainerLowest", "surfaceContainerLow",
        "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
    )

    /**
     * Painted-where pairs Material does not encode in a name.
     *
     * **Curated, not total.** Name pairing is total by construction; this list is hand-written, so a
     * future M3 change in *where* a slot is painted will not be caught by it. `TokenTotalityTest`'s
     * pinned slot count is the tripwire that forces this to be re-read on a BOM bump.
     */
    private fun usagePairs(): List<Triple<String, String, Double>> = buildList {
        // inverseOnSurface is not "onInverseSurface", so name pairing misses the whole inverse trio
        // while two thirds of it are text-bearing. Handled here so they can never reach the allowlist.
        add(Triple("inverseOnSurface", "inverseSurface", 4.5))
        add(Triple("inversePrimary", "inverseSurface", 4.5))
        // M3 paints onSurfaceVariant on plain surface: unselected nav icons, supporting text,
        // OutlinedTextField label — not only on surfaceVariant.
        add(Triple("onSurfaceVariant", "surface", 4.5))
        ladder.forEach { add(Triple("onSurfaceVariant", it, 4.5)) }
        ladder.forEach { add(Triple("onSurface", it, 4.5)) }
        ladder.forEach { add(Triple("primary", it, 4.5)) }
        ladder.forEach { add(Triple("error", it, 4.5)) }
        add(Triple("outline", "surfaceVariant", 3.0))
        ladder.forEach { add(Triple("outline", it, 3.0)) }
        // M3's fixed accent roles. Name pairing reaches onXFixed -> XFixed but not XFixedDim (no
        // onXFixedDim exists) nor onXFixedVariant (strips to XFixedVariant, which does not exist).
        listOf("primary", "secondary", "tertiary").forEach { accent ->
            val a = accent.replaceFirstChar { it.uppercase() }
            add(Triple("on${a}FixedVariant", "${accent}Fixed", 4.5))
            add(Triple("on${a}Fixed", "${accent}FixedDim", 4.5))
            add(Triple("on${a}FixedVariant", "${accent}FixedDim", 4.5))
        }
    }

    /** `onX` → `X`, for every slot whose name declares its own background. */
    private fun namePairs(slots: Set<String>): List<Triple<String, String, Double>> =
        slots.filter { it.startsWith("on") && it.length > 2 }
            .mapNotNull { on ->
                val bg = on.removePrefix("on").replaceFirstChar { it.lowercase() }
                if (bg in slots) Triple(on, bg, 4.5) else null
            }

    @Test
    fun everySchemePairMeetsItsFloor() {
        val failures = palettes().flatMap { p ->
            val slots = schemeSlots(p.scheme)
            (namePairs(slots.keys) + usagePairs()).mapNotNull { (fg, bg, floor) ->
                val f = slots[fg] ?: return@mapNotNull "${p.name}: no such slot $fg"
                val b = slots[bg] ?: return@mapNotNull "${p.name}: no such slot $bg"
                val r = contrast(f, b)
                if (r < floor) "${p.name}: $fg on $bg = %.2f < $floor".format(r) else null
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    // --- the scan proves it is doing work -----------------------------------

    @Test
    fun reflectionFindsTheSlots() {
        val slots = schemeSlots(duckColorScheme(DuckThemeSpec.DuckRed, dark = false))
        assertTrue(
            "de-mangling found ${slots.size} ColorScheme slots — a count this low means the getter " +
                "name transform is wrong (Color is a value class: getPrimary-0d7_KjU, not getPrimary), " +
                "not that Material shrank",
            slots.size >= 30,
        )
        listOf("primary", "onPrimary", "surfaceVariant", "inverseOnSurface", "outline").forEach {
            assertTrue("expected slot $it among ${slots.keys.sorted()}", it in slots)
        }
    }

    @Test
    fun detectorCatchesAPlantedFailure() {
        // Grey on white is 1.0:1 — if the maths or the comparison were inverted this would pass.
        assertTrue(contrast(Color.White, Color.White) < 4.5)
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrast(Color.White, Color.White), 0.001)
    }

    /**
     * Every slot must appear on **either side** of an asserted pair, or be one of exactly three
     * decorative names.
     *
     * Either side, not the foreground side: the seven ladder steps and `outline` have no `onX`
     * counterpart, so counting only foregrounds would go red on eight slots and the obvious fix —
     * widening the allowlist — would drop `outline`, a real 3:1 UI component, into the decorative
     * bucket.
     */
    @Test
    fun everySlotIsCoveredOrAllowlisted() {
        val slots = schemeSlots(duckColorScheme(DuckThemeSpec.DuckRed, dark = false))
        val allowlist = setOf("surfaceTint", "scrim", "outlineVariant")
        val covered = (namePairs(slots.keys) + usagePairs())
            .flatMap { listOf(it.first, it.second) }
            .toSet()

        assertEquals("the decorative allowlist must stay exactly three", 3, allowlist.size)
        assertEquals(
            "slots neither asserted nor allowlisted — do not widen the allowlist to fix this, " +
                "add the pair that actually paints them",
            emptySet<String>(),
            slots.keys - covered - allowlist,
        )
    }

    /**
     * Component fills measured against an **elevated** container, recorded rather than floored.
     *
     * Phase 6 puts `DuckTagChip` in the sidebar, which is `surfaceElevated`, and the selected chip's
     * `tagSurface` fill is 1.04–1.16 against it — under the 1.2 visibly-distinct rule in all eight
     * palettes. That rule is deliberately **not** applied here, for three reasons:
     *
     * 1. WCAG 1.4.11 requires 3:1 for a component's *boundary*, not for its fill against the
     *    container. The selected chip carries an `accentText` border at 5.19 min on `surfaceElevated`.
     * 2. The 1.2 rule was authored for fills that ARE their own boundary — a code block, a highlight.
     *    A bordered component does not owe it.
     * 3. It is unsatisfiable in two palettes. DuckRedLight's `tagSurface` would need luminance
     *    <= 0.7673 to clear 1.2 against its own `surfaceElevated`, and >= 0.8007 to keep `accentText`
     *    at 4.5 — empty by 0.033. SepiaLight is empty by 0.019. Closing the gap means moving
     *    `accentText`, which `TokenTotalityTest` pins to `accentPress`.
     *
     * So the numbers are recorded, not enforced: the chip's perceivability comes from its border, and
     * a future change to either fill shows up here as a diff instead of passing silently.
     */
    @Test
    fun componentFillsOnElevatedContainersAreRecorded() {
        val recorded = palettes().joinToString("\n") { p ->
            "%-16s tagSurface %.3f  surface %.3f  divider %.3f".format(
                p.name,
                contrast(p.colors.tagSurface, p.colors.surfaceElevated),
                contrast(p.colors.surface, p.colors.surfaceElevated),
                contrast(p.colors.divider, p.colors.surfaceElevated),
            )
        }
        assertEquals(
            """
            DuckRed light    tagSurface 1.146  surface 1.071  divider 1.173
            DuckRed dark     tagSurface 1.036  surface 1.189  divider 1.262
            Graphite light   tagSurface 1.125  surface 1.080  divider 1.177
            Graphite dark    tagSurface 1.144  surface 1.169  divider 1.259
            Sepia light      tagSurface 1.157  surface 1.064  divider 1.177
            Sepia dark       tagSurface 1.123  surface 1.130  divider 1.220
            Midnight light   tagSurface 1.137  surface 1.073  divider 1.177
            Midnight dark    tagSurface 1.155  surface 1.157  divider 1.250
            """.trimIndent(),
            recorded,
        )
    }

    /**
     * A disabled control must be distinguishable from an enabled one.
     *
     * **This is a Duck design rule, not a WCAG one.** WCAG 1.4.11 exempts *"inactive user interface
     * components"* outright and 1.4.3 exempts incidental text, so a disabled control owes no contrast
     * floor at all — nothing below is an accessibility floor, and nothing above should be read as one.
     * The requirement is affordance: a control that looks live and does nothing invites a tap. A dim
     * control still reads as disabled; an identical one does not, which makes "no dimming" *worse* than
     * poor dimming rather than better.
     *
     * Every pair here is **foreground against foreground** — the enabled colour against the disabled
     * one for the same painted element. That is a different axis from the rest of this file, and it is
     * why none of these were caught by pairing against a background: both states passed their own
     * background check, then rendered the same.
     *
     * 1.2 is borrowed from the visibly-distinct rule the tints use, since it is the same question.
     */
    @Test
    fun everyDisabledStateIsDistinguishableFromEnabled() {
        val failures = palettes().flatMap { p ->
            distinguishability.mapNotNull { (element, enabled, disabled) ->
                val r = contrast(p.colors.slot(enabled), p.colors.slot(disabled))
                if (r < 1.2) "${p.name}: $element $enabled vs $disabled = %.4f < 1.2".format(r) else null
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * Positive control: this list is iterated, so emptying it reports green.
     *
     * The checkbox border pair is the one that must never be lost. It read `chromeMuted` vs
     * `chromeMuted` — exactly 1.0000 in all eight palettes — because `uncheckedColor` and
     * `disabledUncheckedColor` were the same token and an unchecked box has no fill, only a border.
     */
    @Test
    fun theDistinguishabilityListIsNotSilentlyEmpty() {
        assertEquals(2, distinguishability.size)
        assertTrue(
            "the checkbox border pair is the regression this list exists for",
            Triple("checkbox border", "chromeMuted", "divider") in distinguishability,
        )
        // And the regression itself, planted: the pair this replaced was a token against itself. If
        // 1.2 were a number nothing could fail, the rule above would be decoration.
        palettes().forEach { p ->
            assertTrue(
                "${p.name}: a token against itself must fail the 1.2 rule",
                contrast(p.colors.chromeMuted, p.colors.chromeMuted) < 1.2,
            )
        }
    }

    /**
     * The one disabled state that fails the rule above, recorded with its measured fix and why that fix
     * is not built.
     *
     * `DuckCheckbox`'s disabled **checked** fill is `chromeMuted` against an enabled `accent`. It clears
     * 1.2 in four palettes and fails in four: SepiaLight 1.0024, GraphiteDark 1.0872, SepiaDark 1.1014,
     * MidnightDark 1.0051. Excluded from [distinguishability] rather than floored, because a floor here
     * would be red until a slot is added for a state that has no caller.
     *
     * **The fix is known and cheap; only the slot budget is not.** M3 1.4 exposes no
     * `disabledCheckedCheckmarkColor`, so the tick is shared by both states and the disabled fill must
     * recede toward the page *and* keep that tick legible. Per mode:
     *
     * | mode | answer |
     * |---|---|
     * | dark ×4 | `accentPress` — the only palette slot inside the window, 1.31–1.47 / tick 3.06–3.87 |
     * | light ×3 | already correct: `chromeMuted` is *lighter* than `accent` and recedes |
     * | SepiaLight | window empty by 0.0241; needs an `onAccent` tick, feasible on existing values |
     *
     * One new slot fixes seven palettes, two fix all eight. Deferred until something disables a checked
     * box — see the Phase 6 prerequisite in `DUCK_NOTES_BUILD_PROMPT.md`.
     *
     * **The lesson is worth more than the checkbox.** The first search called this structurally
     * impossible, because it minimised each constraint across all eight palettes at once. "Recedes"
     * inverts with mode — darker on a dark page, lighter on a light one — so a single minimum collapsed
     * two opposite requirements into one unsatisfiable constraint and hid an answer that was already
     * sitting in three palettes. Palettes are per-mode data; solve per-mode constraints per mode.
     */
    @Test
    fun theDisabledCheckedFillIsRecordedPendingACaller() {
        val recorded = palettes().joinToString("\n") { p ->
            "%-16s accent vs chromeMuted %.4f".format(
                p.name,
                contrast(p.colors.accent, p.colors.chromeMuted),
            )
        }
        assertEquals(
            """
            DuckRed light    accent vs chromeMuted 1.3020
            DuckRed dark     accent vs chromeMuted 1.3020
            Graphite light   accent vs chromeMuted 1.8479
            Graphite dark    accent vs chromeMuted 1.0872
            Sepia light      accent vs chromeMuted 1.0024
            Sepia dark       accent vs chromeMuted 1.1014
            Midnight light   accent vs chromeMuted 1.5538
            Midnight dark    accent vs chromeMuted 1.0051
            """.trimIndent(),
            recorded,
        )
    }

    /** No floor, but pinned: a change to either must be a visible diff, not a silent drift. */
    @Test
    fun dividerAndElevationAreRecorded() {
        val recorded = palettes().joinToString("\n") { p ->
            "%-16s divider %.2f  surfaceElevated %.2f".format(
                p.name,
                contrast(p.colors.divider, p.colors.surface),
                contrast(p.colors.surfaceElevated, p.colors.surface),
            )
        }
        assertEquals(
            """
            DuckRed light    divider 1.26  surfaceElevated 1.07
            DuckRed dark     divider 1.50  surfaceElevated 1.19
            Graphite light   divider 1.27  surfaceElevated 1.08
            Graphite dark    divider 1.47  surfaceElevated 1.17
            Sepia light      divider 1.25  surfaceElevated 1.06
            Sepia dark       divider 1.38  surfaceElevated 1.13
            Midnight light   divider 1.26  surfaceElevated 1.07
            Midnight dark    divider 1.45  surfaceElevated 1.16
            """.trimIndent(),
            recorded,
        )
    }
}
