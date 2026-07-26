package app.duck.notes.core.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 5's headline Do-not — *"hardcode a color anywhere outside the theme file"* — asserted by code,
 * plus the structural half of Phase 14's font guarantee.
 *
 * Four things are banned in `src/main/java/**/*.kt` outside `core/theme/`:
 *
 * | banned | why |
 * |---|---|
 * | a colour literal | the Do-not, verbatim |
 * | a raw brand token (`BrandAccent` …) | not a literal, but it pins the caller to Duck Red under every theme — the same defect wearing a name |
 * | `FontFamily.` | Phase 14 swaps [DuckFonts]; a call site naming a family opts itself out of the swap |
 * | an absolute `.sp` | likewise for the font-size setting. `.em` stays legal — relative is the point |
 *
 * A file scan can fail silently: a wrong path finds nothing and reports green. So, in the idiom
 * `DomainPurityTest` established, this class also proves it is doing work — that the directory
 * resolved, that it saw the whole tree, and that the detector catches planted offenders. Only then
 * does a clean result mean anything.
 */
class TokenSourceTest {

    @Test
    fun mainSourceDirectoryResolves() {
        assertTrue(
            "no sources at ${MAIN_DIR.absolutePath} — unit tests run with the module dir as the " +
                "working directory, so this path is relative to `app/`",
            MAIN_DIR.isDirectory,
        )
    }

    @Test
    fun scansEveryFileOutsideTheThemePackage() {
        val scanned = sources().size
        assertTrue(
            "scanned $scanned Kotlin files outside core/theme, expected at least $MIN_SOURCES — a " +
                "scan this small means the path or the exclusion is wrong, not that the app shrank",
            scanned >= MIN_SOURCES,
        )
    }

    @Test
    fun detectorFlagsPlantedOffenders() {
        val dirty =
            """
            package app.duck.notes.presentation

            import androidx.compose.ui.text.font.FontFamily
            val a = Color(0xFF123456)
            val b = SolidColor(BrandAccent)
            val c = SpanStyle(color = BrandTextMuted)
            val d = TextStyle(fontSize = 16.sp, fontFamily = FontFamily.Monospace)
            val e = Color.White
            """.trimIndent()

        assertEquals(
            listOf(
                "Color(0xFF123456)",
                "BrandAccent",
                "BrandTextMuted",
                "16.sp",
                "FontFamily.Monospace",
                "Color.White",
            ).sorted(),
            offenders(dirty).sorted(),
        )
    }

    @Test
    fun detectorAcceptsTokenizedSource() {
        val clean =
            """
            package app.duck.notes.presentation

            val a = DuckTheme.colors.accent
            val b = MaterialTheme.colorScheme.onSurface
            val c = DuckTheme.type.noteBody
            val d = SpanStyle(fontFamily = type.mono)
            val e = 1.6.em
            val f = 16.dp
            val g = Color.Unspecified
            val h = DuckThemeSpec.DuckRed
            """.trimIndent()

        assertEquals(emptyList<String>(), offenders(clean))
    }

    /**
     * Comment-stripping must silence *documentation* of a banned pattern without silencing the
     * pattern itself — a strip that ate too much would disable the whole detector and report green.
     */
    @Test
    fun detectorIgnoresCommentsButNotCode() {
        val documented =
            """
            package app.duck.notes.core.markdown

            /**
             * `CodeTint = Color(0x14000000)` was an 8% black overlay, and `TagTint` was
             * `DuckAccent.copy(alpha = 0.12f)` evaluated at class-init.
             */
            class Styles {
                // FontFamily.Monospace and 16.sp used to live here
                val ok = DuckTheme.colors.codeSurface
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), offenders(documented))

        val sameFileWithRealCode = documented.replace(
            "val ok = DuckTheme.colors.codeSurface",
            "val bad = Color(0x14000000)",
        )
        assertEquals(listOf("Color(0x14000000)"), offenders(sameFileWithRealCode))
    }

    /**
     * A URL in a string must not swallow the rest of its line.
     *
     * `SAMPLE_NOTE` in `MarkdownEditorScreen` already contains `https://duck.example`, so this trap is
     * live in the tree, not hypothetical — it just has not had a violation land after it yet.
     */
    @Test
    fun aUrlDoesNotHideAViolationOnTheSameLine() {
        assertEquals(
            listOf("Color(0xFF123456)"),
            offenders("""val docs = "https://example.com"; val bad = Color(0xFF123456)"""),
        )
        assertEquals(
            listOf("16.sp"),
            offenders(
                """
                val a = "https://example.com/path"
                val b = 16.sp
                """.trimIndent(),
            ),
        )
        // A URL on its own is not a violation, and must not be reported as one.
        assertEquals(emptyList<String>(), offenders("""val a = "https://example.com/x""""))
    }

    @Test
    fun mainSourceGoesThroughTheTokenLayer() {
        val found = sources().flatMap { file ->
            offenders(file.readText()).map { "${file.name}: $it" }
        }

        assertEquals(
            "colours and fonts outside core/theme must come from DuckTheme.colors / DuckTheme.type " +
                "or MaterialTheme, never from a literal or a raw brand token",
            emptyList<String>(),
            found,
        )
    }

    /**
     * The gallery gets the same scan, because it is the file people copy from.
     *
     * **This is worth doing and is not the fix.** It found zero violations when it was added: both of
     * the gallery's contrast defects — a `surface` label on an `accent` fill at 4.25:1, and
     * `chromeMuted` used for 12sp `uiLabel` text at 3.26:1 — used entirely legitimate tokens. This
     * test checks **provenance** (did the colour come from the token layer?) and cannot check
     * **fitness** (is it the right token for this role?). What closed that hole was `ContrastTest`'s
     * component pairs and the gallery calling `DuckTagChip` instead of owning a copy of it.
     *
     * It stays because a literal creeping into `src/debug` would be copied outward from there.
     */
    @Test
    fun debugSourceGoesThroughTheTokenLayerToo() {
        assertTrue(
            "no sources at ${DEBUG_DIR.absolutePath} — a wrong path here reports clean forever",
            DEBUG_DIR.isDirectory,
        )
        val scanned = debugSources().size
        assertTrue(
            "scanned $scanned debug files, expected at least $MIN_DEBUG_SOURCES",
            scanned >= MIN_DEBUG_SOURCES,
        )
        assertEquals(
            emptyList<String>(),
            debugSources().flatMap { f -> offenders(f.readText()).map { "${f.name}: $it" } },
        )
    }

    private companion object {
        val MAIN_DIR = File("src/main/java/app/duck/notes")
        val THEME_DIR = File(MAIN_DIR, "core/theme")
        val DEBUG_DIR = File("src/debug/java/app/duck/notes")

        /** Well under the real count; catches a broken path rather than tracking the codebase. */
        const val MIN_SOURCES = 40

        /** The gallery is three files. Two would mean the path is wrong. */
        const val MIN_DEBUG_SOURCES = 2

        /**
         * The seven raw brand hexes. `\b` at the end keeps `BrandText` off `BrandTextMuted`.
         *
         * They are prefixed `Brand`, not `Duck`, specifically so this detector cannot collide with a
         * component name. Phase 5b caught that the hard way: `DuckDivider` was simultaneously the raw
         * divider hex and the new divider composable, so the scan flagged the component as a raw-token
         * use. `DuckText` would have done the same to a `DuckText` composable in Phase 6. Renaming the
         * hexes retired the whole collision class instead of teaching the regex to guess.
         */
        val RAW_TOKEN = Regex(
            "\\b(BrandAccentPress|BrandAccent|BrandSurfaceDark|BrandSurface|BrandTextMuted|BrandText|BrandDivider)\\b"
        )
        val COLOR_LITERAL = Regex("\\bColor\\(0[xX][0-9A-Fa-f]+\\)")

        /**
         * `Color.Unspecified` and `Color.Transparent` are the *absence* of a colour rather than a
         * colour, so they stay legal. `DuckTagChip`'s unselected state has no fill at all — drawing
         * `surface` there measured 1.06 against an elevated container, i.e. invisible, so transparent
         * is both simpler and more honest. Every other named constant is a hardcoded colour.
         */
        val NAMED_COLOR = Regex("\\bColor\\.(?!Unspecified\\b|Transparent\\b)[A-Z]\\w*")

        /** Member access only — declaring `val mono: FontFamily` is how the token layer is consumed. */
        val FONT_FAMILY = Regex("\\bFontFamily[.(]\\w*")
        val ABSOLUTE_SP = Regex("\\b\\d+(?:\\.\\d+)?\\.sp\\b")

        val DETECTORS = listOf(COLOR_LITERAL, RAW_TOKEN, NAMED_COLOR, FONT_FAMILY, ABSOLUTE_SP)

        fun sources(): List<File> = MAIN_DIR.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.absolutePath.startsWith(THEME_DIR.absolutePath) }
            .toList()

        fun debugSources(): List<File> =
            DEBUG_DIR.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        /**
         * String literals and comments, in **one** alternation.
         *
         * Order matters and is the whole point: a string is matched before `//` can be, so a `//`
         * inside a string literal is consumed as part of the string and never mistaken for the start
         * of a comment. Stripping comments with a bare `//.*` truncates at the first `//` anywhere on
         * the line — including inside `"https://example.com"` — which silently erases any real
         * violation later on that line. That turns a guard test into a rubber stamp, and it is the
         * dangerous direction: a false negative reports green.
         *
         * **Known limit:** string contents are blanked *wholesale*, and a `"${'$'}{...}"` template
         * holds **code**, not string data. A violation written inside a template — `"${'$'}{Color(0x…)}"`
         * — is therefore missed. Nothing in this codebase interpolates a colour or a font, and a real
         * Kotlin lexer is not worth carrying for a case that does not exist. Recorded rather than
         * fixed; if templates ever carry styling, this is the line to revisit.
         */
        val STRING_OR_COMMENT = Regex(
            "\"\"\"[\\s\\S]*?\"\"\"" + // raw string
                "|\"(?:\\\\.|[^\"\\\\\\n])*\"" + // string literal
                "|'(?:\\\\.|[^'\\\\\\n])*'" + // char literal
                "|/\\*[\\s\\S]*?\\*/" + // block comment / KDoc
                "|//[^\\n]*", // line comment
        )

        fun offenders(source: String): List<String> {
            // Comments, strings and imports describe code; none of them paint anything. Stripping
            // comments is not cosmetic: the KDoc on MarkdownStyles explains the exact tints Phase 5
            // deleted, so without it the detector flags the documentation of its own fix.
            val body = source
                .replace(STRING_OR_COMMENT) { if (it.value.first() in "\"'") "\"\"" else "" }
                .lineSequence()
                .filterNot { it.trimStart().startsWith("import ") }
                .joinToString("\n")
            return DETECTORS.flatMap { d -> d.findAll(body).map { it.value.trim() } }
        }
    }
}
